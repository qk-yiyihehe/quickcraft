package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.StonecuttingRecipe;

import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckRules.*;

/**
 * 切石机按本地预测连续补料和取产物，不额外处理服务端库存回包。
 */
public class QuickCraftStonecutter implements ClientModInitializer {
    private static QuickCraftStonecutter INSTANCE;
    private static long nextSessionId;
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/StonecutterCraft");
    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    private static final int MAX_PICKUP_WAIT_TICKS = 10;
    private final QuickCraftStonecutterAccess access = new QuickCraftStonecutterAccess();

    private boolean lastVDown;
    private boolean lastAltCDown;
    private boolean rapidCraftingActive;
    private boolean rapidCraftStartedByButton;
    private boolean singleCraftPending;
    private RecipeEntry<StonecuttingRecipe> lockedRecipe;
    private ItemStack lockedInputTemplate = ItemStack.EMPTY;
    private ItemStack lockedResultTemplate = ItemStack.EMPTY;
    private StonecutterScreenHandler sessionHandler;
    private ClientPlayNetworkHandler sessionNetwork;
    private boolean outputSprayMode;
    private boolean sprayInventoryCleared;
    private boolean noIngredientRescueAttempted;
    private final QuickCraftRateLimiter rateLimiter = new QuickCraftRateLimiter();
    private int pickupWaitTicks;

    enum Phase { REFILL, SELECT, OUTPUT }

    private Phase batchPhase;
    private boolean predicting;
    private boolean inputRecoveryAttempted;
    private int predictionRecipeIndex = -1;
    private int batchStartRevision;
    private ItemStack batchInput = ItemStack.EMPTY;
    private int batchSourceSlot = -1;
    private int batchSourceCount;
    private long batchSentAtNanos;
    private long sessionId;
    private long batchId;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleStonecutterCraftButton(boolean rapidCraft) {
        return INSTANCE != null && INSTANCE.handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    private void onClientTick(MinecraftClient client) {
        if (sessionNetwork != null && sessionNetwork != client.getNetworkHandler()) {
            resetAll("连接切换");
        }
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled() || !isCraftingContextValid(client)) {
            resetAll("功能关闭或离开切石机");
            return;
        }
        StonecutterScreenHandler handler = (StonecutterScreenHandler) client.player.currentScreenHandler;
        if (sessionHandler != null && sessionHandler != handler) {
            resetAll("切换切石机");
        }
        handleHotkeys(client, handler);
        pump(client);
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled() || !isCraftingContextValid(client)) {
            return false;
        }
        StonecutterScreenHandler handler = (StonecutterScreenHandler) client.player.currentScreenHandler;
        return startCraft(client, handler, rapidCraft, true);
    }

    private void handleHotkeys(MinecraftClient client, StonecutterScreenHandler handler) {
        boolean singleDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
        if (singleDown && !lastVDown) {
            startCraft(client, handler, false, false);
        }
        if (rapidDown && !lastAltCDown) {
            startCraft(client, handler, true, false);
        }
        lastVDown = singleDown;
        lastAltCDown = rapidDown;
    }

    private boolean startCraft(MinecraftClient client, StonecutterScreenHandler handler,
                               boolean rapid, boolean fromButton) {
        if (rapidCraftingActive || singleCraftPending || client.interactionManager == null
                || client.getNetworkHandler() == null || !handler.getCursorStack().isEmpty()) {
            if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
                LOGGER.info("切石机拒绝启动：syncId={}，持续={}，单次={}，光标={}",
                        handler.syncId, rapidCraftingActive, singleCraftPending, handler.getCursorStack());
            }
            return false;
        }
        if (sessionHandler != handler || sessionNetwork != client.getNetworkHandler()) {
            clearLockedSelection();
        }
        if (!lockCurrentSelection(client, handler)) {
            if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
                LOGGER.info("切石机拒绝启动：未选配方，syncId={}", handler.syncId);
            }
            sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.no_selection"));
            return false;
        }
        sessionHandler = handler;
        sessionNetwork = client.getNetworkHandler();
        rapidCraftingActive = rapid;
        rapidCraftStartedByButton = fromButton;
        singleCraftPending = !rapid;
        outputSprayMode = unlockedEmptySlots(handler) == 0;
        sprayInventoryCleared = false;
        noIngredientRescueAttempted = false;
        pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        sessionId = ++nextSessionId;
        batchId = 0L;
        inputRecoveryAttempted = false;
        batchSourceSlot = -1;
        batchSourceCount = 0;
        batchInput = ItemStack.EMPTY;
        predictionRecipeIndex = handler.getSelectedRecipe();
        batchSentAtNanos = 0L;
        batchStartRevision = handler.getRevision();
        logCraft("会话开始：" + (rapid ? "持续合成" : "单次合成") + (fromButton ? "，按钮触发" : "，热键触发"));
        if (rapid) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.started"));
        }
        pump(client);
        return true;
    }

    private boolean isRapidInputHeld(MinecraftClient client) {
        return rapidCraftStartedByButton ? isCraftButtonRapidModeHeld(client)
                : QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    private void pump(MinecraftClient client) {
        if ((!rapidCraftingActive && !singleCraftPending) || !isSessionValid(client)) {
            return;
        }
        predicting = true;
        try {
            while (rapidCraftingActive || singleCraftPending) {
                if (rapidCraftingActive && !isRapidInputHeld(client)) {
                    finish(client, Text.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                if (!singleCraftPending
                        && !rateLimiter.isReady(QuickCraftConfigs.getCraftsPerSecond(), System.nanoTime())) {
                    long waitingSessionId = sessionId;
                    rateLimiter.resumeWhenReady(QuickCraftConfigs.getCraftsPerSecond(), client, () -> {
                        if (sessionId == waitingSessionId && rapidCraftingActive
                                && QuickCraftConfigs.isStonecutterQuickCraftEnabled() && isSessionValid(client)) {
                            pump(client);
                        }
                    });
                    return;
                }
                StonecutterScreenHandler handler = sessionHandler;
                if (!handler.getCursorStack().isEmpty()) {
                    finish(client, Text.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                if (matchesLockedInput(handler.getSlot(INPUT_SLOT).getStack())
                        && ItemStack.areItemsAndComponentsEqual(handler.getSlot(OUTPUT_SLOT).getStack(), lockedResultTemplate)
                        && handler.getSelectedRecipe() != predictionRecipeIndex) {
                    handler.onButtonClick(client.player, predictionRecipeIndex);
                    refreshClientPrediction(handler);
                }
                if (!recoverInput(client)) return;
                if (!handler.getSlot(INPUT_SLOT).hasStack()) {
                    int source = findBestSupplyIngredientSlot(client.player.getInventory(), handler, lockedRecipe);
                    if (source < 0) {
                        if (rapidCraftingActive && !noIngredientRescueAttempted && unlockedEmptySlots(handler) == 0) {
                            noIngredientRescueAttempted = true;
                            outputSprayMode = true;
                            sprayInventoryCleared = true;
                            logCraft("首次缺料时释放满包产物");
                            if (dropInventoryCraftResults(client, handler) > 0) pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
                        }
                        if (pickupWaitTicks == MAX_PICKUP_WAIT_TICKS) logCraft("无可用原料，等待拾取补料");
                        if (rapidCraftingActive && pickupWaitTicks-- > 0) return;
                        finish(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
                        return;
                    }
                    beginBatch(Phase.REFILL);
                    batchSourceSlot = playerInventoryIndexToHandlerSlot(source);
                    batchSourceCount = handler.getSlot(batchSourceSlot).getStack().getCount();
                    click(client, batchSourceSlot, 0, SlotActionType.QUICK_MOVE);
                } else {
                    beginBatch(Phase.OUTPUT);
                }
                if (!matchesLockedInput(handler.getSlot(INPUT_SLOT).getStack())) {
                    logCraft("补料后输入为空或品种不符，停止合成");
                    finish(client, Text.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
                if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                    int index = findAvailableRecipeIndex(handler, lockedRecipe);
                    if (index < 0) index = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
                    if (!isRecipeIndexAvailable(handler, index)) {
                        finish(client, Text.translatable("quickcraft.message.stonecutter.no_selection"));
                        return;
                    }
                    batchPhase = Phase.SELECT;
                    predictionRecipeIndex = index;
                    if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) logCraft("选择配方：index=" + index);
                    if (handler.getSelectedRecipe() != index) {
                        access.selectRecipeForCraft(client, handler, index);
                    }
                    refreshClientPrediction(handler);
                }
                ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
                if (output.isEmpty() || !ItemStack.areItemsAndComponentsEqual(output, lockedResultTemplate)) {
                    finish(client, Text.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                batchPhase = Phase.OUTPUT;
                int inputBefore = handler.getSlot(INPUT_SLOT).getStack().getCount();
                moveOrThrowOutput(client, handler, output, inputBefore);
                if (!handler.getCursorStack().isEmpty()
                        || handler.getSlot(INPUT_SLOT).getStack().getCount() >= inputBefore) {
                    logCraft("本地操作未消耗原料或光标未清空，停止合成");
                    finish(client, Text.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                logCraft("批次本地预测完成");
                if (singleCraftPending) {
                    singleCraftPending = false;
                    logCraft("单次合成结束");
                    return;
                }
            }
        } finally {
            predicting = false;
        }
    }

    private void moveOrThrowOutput(MinecraftClient client, StonecutterScreenHandler handler,
                                   ItemStack output, int inputBefore) {
        if (!outputSprayMode && shouldEnterOutputSprayMode(false,
                unlockedEmptySlots(handler), findAcceptingSlot(handler, output) >= 0)) {
            activateOutputSprayMode(client, handler, "背包首次占满");
        }
        if (!outputSprayMode) {
            if (!hasLockedPlayerSlots(handler)) {
                click(client, OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE);
            } else {
                // 显式放入可用格，避免原版连续合成跨越锁槽保护。
                int maximum = Math.min(64, inputBefore);
                for (int i = 0; i < maximum; i++) {
                    ItemStack next = handler.getSlot(OUTPUT_SLOT).getStack();
                    int target = findAcceptingSlot(handler, next);
                    if (next.isEmpty() || !ItemStack.areItemsAndComponentsEqual(next, lockedResultTemplate)
                            || target < 0) break;
                    click(client, OUTPUT_SLOT, 0, SlotActionType.PICKUP);
                    ItemStack cursor = handler.getCursorStack();
                    if (cursor.isEmpty() || !ItemStack.areItemsAndComponentsEqual(cursor, lockedResultTemplate)) break;
                    click(client, target, 0, SlotActionType.PICKUP);
                    if (!handler.getCursorStack().isEmpty()) break;
                }
            }
            // 和普通 ACK 合成一致：当前背包实际占满时才释放产物。
            if (shouldEnterOutputSprayMode(false, unlockedEmptySlots(handler), true)) {
                activateOutputSprayMode(client, handler, "背包实际占满");
            }
            return;
        }
        activateOutputSprayMode(client, handler, "产物无法放入背包");
        logCraft("从结果槽 Ctrl+Q 喷射：操作前");
        access.throwOutput(client, handler, lockedResultTemplate);
        logCraft("从结果槽 Ctrl+Q 喷射：操作后");
    }

    private void activateOutputSprayMode(MinecraftClient client, StonecutterScreenHandler handler, String reason) {
        if (!outputSprayMode) {
            outputSprayMode = true;
            logCraft("切换永久喷射模式：" + reason);
        }
        if (!sprayInventoryCleared) {
            sprayInventoryCleared = true;
            logCraft("进入喷射模式丢出背包产物");
            dropInventoryCraftResults(client, handler);
        }
    }

    private boolean recoverInput(MinecraftClient client) {
        ItemStack input = sessionHandler.getSlot(INPUT_SLOT).getStack().copy();
        if (input.isEmpty() || matchesLockedInput(input)) return true;
        if (inputRecoveryAttempted || !ItemStack.areItemsAndComponentsEqual(input, lockedResultTemplate)) {
            logCraft("输入类型与锁定原料不符，停止合成");
            finish(client, Text.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        int target = findAcceptingSlot(sessionHandler, input);
        if (target < 0 || QuickContainerLock.isLockedSlot(sessionHandler, INPUT_SLOT)) {
            logCraft("输入槽误入产物，但没有可安全返还的未锁定槽位");
            finish(client, Text.translatable("quickcraft.message.stonecutter.blocked_input"));
            return false;
        }
        inputRecoveryAttempted = true;
        logCraft("返还误入输入槽的产物");
        click(client, INPUT_SLOT, 0, SlotActionType.PICKUP);
        ItemStack cursor = sessionHandler.getCursorStack();
        if (sessionHandler.getSlot(INPUT_SLOT).hasStack()
                || !ItemStack.areItemsAndComponentsEqual(cursor, input) || cursor.getCount() != input.getCount()) {
            logCraft("无法完整取出误入输入槽的产物，停止返还");
            finish(client, Text.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        click(client, target, 0, SlotActionType.PICKUP);
        if (sessionHandler.getSlot(INPUT_SLOT).hasStack() || !sessionHandler.getCursorStack().isEmpty()) {
            logCraft("输入返还未完成，保留槽位和光标现状并停止");
            finish(client, Text.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        return true;
    }

    private boolean matchesLockedInput(ItemStack input) {
        return !input.isEmpty() && (lockedRecipe != null
                ? matchesAnyIngredient(input, recipeIngredients(lockedRecipe))
                : ItemStack.areItemsAndComponentsEqual(input, lockedInputTemplate));
    }

    public static void refreshClientPrediction(StonecutterScreenHandler handler) {
        QuickCraftStonecutter active = INSTANCE;
        if (active == null || !active.predicting || active.sessionHandler != handler
                || !active.matchesLockedInput(handler.getSlot(INPUT_SLOT).getStack())
                || active.predictionRecipeIndex < 0 || handler.getSelectedRecipe() != active.predictionRecipeIndex
                || handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return;
        }
        // 仅在本次点击内部补齐客户端缺少的结果，让原版连续取出在发包前完成预测。
        handler.getSlot(OUTPUT_SLOT).setStackNoCallbacks(active.lockedResultTemplate.copy());
    }

    private void beginBatch(Phase phase) {
        batchPhase = phase;
        batchId++;
        batchStartRevision = sessionHandler.getRevision();
        batchInput = sessionHandler.getSlot(INPUT_SLOT).getStack().copy();
        batchSourceSlot = -1;
        batchSourceCount = 0;
        batchSentAtNanos = System.nanoTime();
        if (rapidCraftingActive) {
            rateLimiter.recordDispatch(QuickCraftConfigs.getCraftsPerSecond(), batchSentAtNanos);
        }
        logCraft("批次开始");
    }

    private void logCraft(String event) {
        if (!QuickCraftConfigs.isMouseCraftAckDebugEnabled() || !LOGGER.isInfoEnabled()) return;
        StringBuilder slots = new StringBuilder();
        if (sessionHandler != null) {
            for (int i = 0; i < sessionHandler.slots.size(); i++) {
                if (i > 0) slots.append(", ");
                slots.append(i);
                if (QuickContainerLock.isLockedSlot(sessionHandler, i)) slots.append("(锁)");
                slots.append('=').append(sessionHandler.getSlot(i).getStack());
            }
        }
        LOGGER.info("切石机合成：会话=#{}，批次=#{}，事件={}，阶段={}，syncId={}，revision={}->{}，"
                        + "目标={}，批前原料={}，补料来源={}x{}，光标={}，喷射={}，当前空格={}，"
                        + "耗时={}ms，槽位=[{}]",
                sessionId, batchId, event, batchId == 0 ? "未发批次" : batchPhase,
                sessionHandler == null ? -1 : sessionHandler.syncId, batchStartRevision,
                sessionHandler == null ? -1 : sessionHandler.getRevision(), lockedResultTemplate,
                batchId == 0 ? ItemStack.EMPTY : batchInput, batchSourceSlot, batchSourceCount,
                sessionHandler == null ? ItemStack.EMPTY : sessionHandler.getCursorStack(),
                outputSprayMode, sessionHandler == null ? -1 : unlockedEmptySlots(sessionHandler),
                batchSentAtNanos == 0L ? 0L : (System.nanoTime() - batchSentAtNanos) / 1_000_000L, slots);
    }

    private void click(MinecraftClient client, int slot, int button, SlotActionType action) {
        boolean debug = QuickCraftConfigs.isMouseCraftAckDebugEnabled();
        if (debug) logCraft("点击前：slot=" + slot + "，button=" + button + "，action=" + action);
        client.interactionManager.clickSlot(sessionHandler.syncId, slot, button, action, client.player);
        if (debug) logCraft("点击后：slot=" + slot + "，button=" + button + "，action=" + action);
    }

    private int unlockedEmptySlots(StonecutterScreenHandler handler) {
        int count = 0;
        for (int i = 2; i < handler.slots.size(); i++) {
            if (!QuickContainerLock.isLockedSlot(handler, i) && !handler.getSlot(i).hasStack()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasLockedPlayerSlots(StonecutterScreenHandler handler) {
        for (int i = 2; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i)) {
                return true;
            }
        }
        return false;
    }

    private int findAcceptingSlot(StonecutterScreenHandler handler, ItemStack output) {
        if (output.isEmpty()) {
            return -1;
        }
        int empty = -1;
        for (int i = 2; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i) || !handler.getSlot(i).canInsert(output)) {
                continue;
            }
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                if (empty < 0 && output.getCount() <= handler.getSlot(i).getMaxItemCount(output)) {
                    empty = i;
                }
            } else if (ItemStack.areItemsAndComponentsEqual(stack, output)
                    && stack.getCount() + output.getCount() <= handler.getSlot(i).getMaxItemCount(output)) {
                return i;
            }
        }
        return empty;
    }

    private boolean isSessionValid(MinecraftClient client) {
        return QuickCraftConfigs.isStonecutterQuickCraftEnabled()
                && isCraftingContextValid(client) && client.interactionManager != null
                && client.player.currentScreenHandler == sessionHandler && client.getNetworkHandler() == sessionNetwork;
    }

    private void finish(MinecraftClient client, Text message) {
        if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
            logCraft("结束会话：" + message.getString());
        }
        if (rapidCraftingActive && isSessionValid(client) && sessionHandler.getCursorStack().isEmpty()
                && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropInventoryCraftResults(client, sessionHandler);
        }
        rapidCraftingActive = false;
        singleCraftPending = false;
        sendStatusMessage(client, message);
    }

    private void resetAll(String reason) {
        if (rapidCraftingActive || singleCraftPending) logCraft("取消会话：" + reason);
        rapidCraftingActive = false;
        singleCraftPending = false;
        lastVDown = false;
        lastAltCDown = false;
        sessionHandler = null;
        sessionNetwork = null;
        predictionRecipeIndex = -1;
        clearLockedSelection();
    }

    private int dropInventoryCraftResults(MinecraftClient client, StonecutterScreenHandler handler) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) {
            return 0;
        }
        int droppedSlots = 0;
        for (int i = 2; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && !QuickContainerLock.isLockedSlot(handler, i)
                    && ItemStack.areItemsAndComponentsEqual(stack, lockedResultTemplate)) {
                logCraft("丢出背包已有产物：slot=" + i);
                client.interactionManager.clickSlot(handler.syncId, i, 1, SlotActionType.THROW, client.player);
                droppedSlots++;
            }
        }
        return droppedSlots;
    }

    private int findBestSupplyIngredientSlot(PlayerInventory inventory,
                                             StonecutterScreenHandler handler,
                                             RecipeEntry<StonecuttingRecipe> recipe) {
        List<Ingredient> ingredients = recipe == null ? List.of() : recipeIngredients(recipe);
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (recipe == null ? !ItemStack.areItemsAndComponentsEqual(stack, lockedInputTemplate)
                    : !matchesAnyIngredient(stack, ingredients)) continue;

            int totalCount = countMatchingUnlockedItems(inventory, handler, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int countMatchingUnlockedItems(PlayerInventory inventory,
                                           StonecutterScreenHandler handler,
                                           ItemStack template) {
        int total = 0;
        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
            if (!stack.isEmpty()
                    && !isLockedPlayerInventorySlot(handler, invIndex)
                    && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isLockedPlayerInventorySlot(StonecutterScreenHandler handler, int invIndex) {
        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        return handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot);
    }

    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || access.isIngredientEmpty(ingredient)) continue;
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private List<Ingredient> recipeIngredients(RecipeEntry<StonecuttingRecipe> recipe) {
        return access.getIngredients(recipe);
    }

    private boolean lockCurrentSelection(MinecraftClient client, StonecutterScreenHandler handler) {
        int selectedIndex = handler.getSelectedRecipe();
        if (!isRecipeIndexAvailable(handler, selectedIndex) && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            selectedIndex = findAvailableRecipeIndexByResult(client, handler, handler.getSlot(OUTPUT_SLOT).getStack());
        }

        RecipeEntry<StonecuttingRecipe> recipe = getRecipeAt(handler, selectedIndex);
        ItemStack resultTemplate = ItemStack.EMPTY;
        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        }
        if (resultTemplate.isEmpty() && isRecipeIndexAvailable(handler, selectedIndex)) {
            resultTemplate = getDisplayResultStack(client, handler, selectedIndex);
        }

        if (resultTemplate.isEmpty()) {
            return hasLockedSelection();
        }

        lockedRecipe = recipe;
        lockedInputTemplate = copyTemplate(handler.getSlot(INPUT_SLOT).getStack());
        lockedResultTemplate = resultTemplate.copy();
        return true;
    }

    private RecipeEntry<StonecuttingRecipe> getRecipeAt(StonecutterScreenHandler handler, int recipeIndex) {
        return access.getRecipeAt(handler, recipeIndex);
    }

    private int findAvailableRecipeIndex(StonecutterScreenHandler handler,
                                         RecipeEntry<StonecuttingRecipe> recipe) {
        return access.findAvailableRecipeIndex(handler, recipe);
    }

    private boolean isRecipeIndexAvailable(StonecutterScreenHandler handler, int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < availableRecipeCount(handler);
    }

    private int availableRecipeCount(StonecutterScreenHandler handler) {
        return access.availableRecipeCount(handler);
    }

    int findAvailableRecipeIndexByResult(MinecraftClient client,
                                         StonecutterScreenHandler handler,
                                         ItemStack resultTemplate) {
        if (resultTemplate.isEmpty()) {
            return -1;
        }

        int matchedIndex = -1;
        for (int i = 0; i < availableRecipeCount(handler); i++) {
            ItemStack displayedResult = getDisplayResultStack(client, handler, i);
            if (!displayedResult.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(displayedResult, resultTemplate)) {
                if (matchedIndex >= 0) {
                    return -1;
                }
                matchedIndex = i;
            }
        }
        return matchedIndex;
    }

    private ItemStack getDisplayResultStack(MinecraftClient client,
                                            StonecutterScreenHandler handler,
                                            int recipeIndex) {
        return access.getDisplayResultStack(client, handler, recipeIndex);
    }

    private boolean hasLockedSelection() {
        return !lockedResultTemplate.isEmpty();
    }

    private ItemStack copyTemplate(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private void clearLockedSelection() {
        lockedRecipe = null;
        lockedInputTemplate = ItemStack.EMPTY;
        lockedResultTemplate = ItemStack.EMPTY;
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 29 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 2 + (invIndex - 9);
        }
        return -1;
    }

    private boolean isCraftingContextValid(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return false;
        }

        if (!(client.currentScreen instanceof StonecutterScreen)) {
            return false;
        }

        return client.player.currentScreenHandler instanceof StonecutterScreenHandler;
    }

    private List<ItemStack> mainStacks(PlayerInventory inventory) {
        return access.getMainStacks(inventory);
    }

    void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private boolean isCraftButtonRapidModeHeld(MinecraftClient client) {
        return QuickCraftKeyBindings.isAltDown()
                && QuickCraftKeyBindings.isLeftMouseButtonDown(client);
    }
}
