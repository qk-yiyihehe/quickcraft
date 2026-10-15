package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckRules.*;

/**
 * 切石机按本地预测连续补料和取产物，回包不参与合成调度。
 */
public class QuickCraftStonecutter implements ClientModInitializer {
    private static QuickCraftStonecutter INSTANCE;
    private static long nextSessionId;
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/StonecutterCraft");
    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    private static final int MAX_PICKUP_WAIT_TICKS = 10;

    private boolean lastVDown;
    private boolean lastAltCDown;
    private boolean rapidCraftingActive;
    private boolean rapidCraftStartedByButton;
    private boolean singleCraftPending;
    private ItemStack lockedInputTemplate = ItemStack.EMPTY;
    private ItemStack lockedResultTemplate = ItemStack.EMPTY;
    private StonecutterMenu sessionHandler;
    private ClientPacketListener sessionNetwork;
    private boolean outputSprayMode;
    private boolean sprayInventoryCleared;
    private boolean noIngredientRescueAttempted;
    private int outputFillSlotsRemaining;
    private int dispatchedThisTick;
    private int pickupWaitTicks;

    enum Phase { REFILL, SELECT, OUTPUT }

    private Phase batchPhase;
    private boolean predicting;
    private boolean inputRecoveryAttempted;
    private record PredictedSelection(int revision, ItemStack output) { }
    private final Deque<PredictedSelection> predictedSelections = new ArrayDeque<>();
    private int predictedRevision;
    private ItemStack predictedOutput = ItemStack.EMPTY;
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
        return INSTANCE != null && INSTANCE.handleCraftButton(Minecraft.getInstance(), rapidCraft);
    }

    private void onClientTick(Minecraft client) {
        dispatchedThisTick = 0;
        if (sessionNetwork != null && sessionNetwork != client.getConnection()) {
            resetAll("连接切换");
        }
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled() || !isCraftingContextValid(client)) {
            resetAll("功能关闭或离开切石机");
            return;
        }
        StonecutterMenu handler = (StonecutterMenu) client.player.containerMenu;
        if (sessionHandler != null && sessionHandler != handler) {
            resetAll("切换切石机");
        }
        handleHotkeys(client, handler);
        pump(client);
    }

    private boolean handleCraftButton(Minecraft client, boolean rapidCraft) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled() || !isCraftingContextValid(client)) {
            return false;
        }
        StonecutterMenu handler = (StonecutterMenu) client.player.containerMenu;
        return startCraft(client, handler, rapidCraft, true);
    }

    private void handleHotkeys(Minecraft client, StonecutterMenu handler) {
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

    private boolean startCraft(Minecraft client, StonecutterMenu handler,
                               boolean rapid, boolean fromButton) {
        if (rapidCraftingActive || singleCraftPending || client.gameMode == null
                || client.getConnection() == null || !handler.getCarried().isEmpty()) {
            if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
                LOGGER.info("切石机拒绝启动：syncId={}，持续={}，单次={}，光标={}",
                        handler.containerId, rapidCraftingActive, singleCraftPending, handler.getCarried());
            }
            return false;
        }
        if (sessionHandler != handler || sessionNetwork != client.getConnection()) {
            clearLockedSelection();
            predictedSelections.clear();
        }
        if (!lockCurrentSelection(client, handler)) {
            if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
                LOGGER.info("切石机拒绝启动：未选配方，syncId={}", handler.containerId);
            }
            sendStatusMessage(client, Component.translatable("quickcraft.message.stonecutter.no_selection"));
            return false;
        }
        sessionHandler = handler;
        sessionNetwork = client.getConnection();
        rapidCraftingActive = rapid;
        rapidCraftStartedByButton = fromButton;
        singleCraftPending = !rapid;
        outputFillSlotsRemaining = unlockedEmptySlots(handler);
        outputSprayMode = outputFillSlotsRemaining == 0;
        sprayInventoryCleared = false;
        noIngredientRescueAttempted = false;
        pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        sessionId = ++nextSessionId;
        batchId = 0L;
        inputRecoveryAttempted = false;
        batchSourceSlot = -1;
        batchSourceCount = 0;
        batchInput = ItemStack.EMPTY;
        predictionRecipeIndex = handler.getSelectedRecipeIndex();
        batchSentAtNanos = 0L;
        batchStartRevision = handler.getStateId();
        predictedRevision = handler.getStateId();
        predictedOutput = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        logCraft("会话开始：" + (rapid ? "持续合成" : "单次合成") + (fromButton ? "，按钮触发" : "，热键触发"));
        if (rapid) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.started"));
        }
        pump(client);
        return true;
    }

    private boolean isRapidInputHeld(Minecraft client) {
        return rapidCraftStartedByButton ? isCraftButtonRapidModeHeld(client)
                : QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    private void pump(Minecraft client) {
        if ((!rapidCraftingActive && !singleCraftPending) || !isSessionValid(client)) {
            return;
        }
        predicting = true;
        try {
            while ((rapidCraftingActive || singleCraftPending)
                    && canDispatchWithinTick(dispatchedThisTick, QuickCraftConfigs.getCraftLoopsPerTick())) {
                if (rapidCraftingActive && !isRapidInputHeld(client)) {
                    finish(client, Component.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                StonecutterMenu handler = sessionHandler;
                if (!handler.getCarried().isEmpty()) {
                    finish(client, Component.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                if (matchesLockedInput(handler.getSlot(INPUT_SLOT).getItem())
                        && ItemStack.isSameItemSameComponents(handler.getSlot(OUTPUT_SLOT).getItem(), lockedResultTemplate)
                        && handler.getSelectedRecipeIndex() != predictionRecipeIndex) {
                    handler.clickMenuButton(client.player, predictionRecipeIndex);
                    refreshClientPrediction(handler);
                }
                if (!recoverInput(client)) return;
                if (!handler.getSlot(INPUT_SLOT).hasItem()) {
                    int source = findBestSupplyIngredientSlot(client.player.getInventory(), handler);
                    if (source < 0) {
                        if (rapidCraftingActive && !noIngredientRescueAttempted && unlockedEmptySlots(handler) == 0) {
                            noIngredientRescueAttempted = true;
                            outputSprayMode = true;
                            outputFillSlotsRemaining = 0;
                            sprayInventoryCleared = true;
                            logCraft("首次缺料时释放满包产物");
                            if (dropInventoryCraftResults(client, handler) > 0) pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
                        }
                        if (pickupWaitTicks == MAX_PICKUP_WAIT_TICKS) logCraft("无可用原料，等待拾取补料");
                        if (rapidCraftingActive && pickupWaitTicks-- > 0) return;
                        finish(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
                        return;
                    }
                    beginBatch(Phase.REFILL);
                    batchSourceSlot = playerInventoryIndexToHandlerSlot(source);
                    batchSourceCount = handler.getSlot(batchSourceSlot).getItem().getCount();
                    click(client, batchSourceSlot, 0, ContainerInput.QUICK_MOVE);
                } else {
                    beginBatch(Phase.OUTPUT);
                }
                if (!matchesLockedInput(handler.getSlot(INPUT_SLOT).getItem())) {
                    logCraft("补料后输入为空或品种不符，停止合成");
                    finish(client, Component.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
                if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
                    int index = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
                    if (!isRecipeIndexAvailable(handler, index)) {
                        finish(client, Component.translatable("quickcraft.message.stonecutter.no_selection"));
                        return;
                    }
                    batchPhase = Phase.SELECT;
                    predictionRecipeIndex = index;
                    if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) logCraft("选择配方：index=" + index);
                    if (handler.getSelectedRecipeIndex() != index) {
                        handler.clickMenuButton(client.player, index);
                        client.gameMode.handleInventoryButtonClick(handler.containerId, index);
                        // 原版选配方会发送一个结果槽包并递增序号；后续点击直接使用该序号。
                        predictedRevision = (handler.getStateId() + 1) & 32767;
                        predictedSelections.addLast(new PredictedSelection(predictedRevision, lockedResultTemplate.copy()));
                        sessionHandler.setItem(OUTPUT_SLOT, predictedRevision, lockedResultTemplate.copy());
                    }
                    refreshClientPrediction(handler);
                }
                ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
                if (output.isEmpty() || !ItemStack.isSameItemSameComponents(output, lockedResultTemplate)) {
                    finish(client, Component.translatable("quickcraft.message.crafting.stopped"));
                    return;
                }
                batchPhase = Phase.OUTPUT;
                int inputBefore = handler.getSlot(INPUT_SLOT).getItem().getCount();
                moveOrThrowOutput(client, handler, output, inputBefore);
                if (!handler.getCarried().isEmpty()
                        || handler.getSlot(INPUT_SLOT).getItem().getCount() >= inputBefore) {
                    logCraft("本地操作未消耗原料或光标未清空，停止合成");
                    finish(client, Component.translatable("quickcraft.message.crafting.stopped"));
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
            predictedOutput = sessionHandler.getSlot(OUTPUT_SLOT).getItem().copy();
            predicting = false;
        }
    }

    private void moveOrThrowOutput(Minecraft client, StonecutterMenu handler,
                                   ItemStack output, int inputBefore) {
        if (!outputSprayMode && shouldEnterOutputSprayMode(false, outputFillSlotsRemaining,
                unlockedEmptySlots(handler), findAcceptingSlot(handler, output) >= 0)) {
            activateOutputSprayMode(client, handler, "背包首次占满");
        }
        if (!outputSprayMode) {
            int resultSlotsBefore = matchingResultSlots(handler);
            if (!hasLockedPlayerSlots(handler)) {
                click(client, OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE);
            } else {
                // 显式放入可用格，避免原版连续合成跨越锁槽保护。
                int maximum = Math.min(64, inputBefore);
                for (int i = 0; i < maximum; i++) {
                    ItemStack next = handler.getSlot(OUTPUT_SLOT).getItem();
                    int target = findAcceptingSlot(handler, next);
                    if (next.isEmpty() || !ItemStack.isSameItemSameComponents(next, lockedResultTemplate)
                            || target < 0) break;
                    click(client, OUTPUT_SLOT, 0, ContainerInput.PICKUP);
                    ItemStack cursor = handler.getCarried();
                    if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, lockedResultTemplate)) break;
                    click(client, target, 0, ContainerInput.PICKUP);
                    if (!handler.getCarried().isEmpty()) break;
                }
            }
            outputFillSlotsRemaining = remainingOutputFillSlots(outputFillSlotsRemaining,
                    matchingResultSlots(handler) - resultSlotsBefore);
            // 和普通 ACK 合成一致：填包完成当批就释放产物，不等下一次补料。
            if (shouldEnterOutputSprayMode(false, outputFillSlotsRemaining, unlockedEmptySlots(handler), true)) {
                activateOutputSprayMode(client, handler, "填包阶段完成");
            }
            return;
        }
        activateOutputSprayMode(client, handler, "产物无法放入背包");
        logCraft("从结果槽 Ctrl+Q 喷射：操作前");
        click(client, OUTPUT_SLOT, 1, ContainerInput.THROW);
        logCraft("从结果槽 Ctrl+Q 喷射：操作后");
    }

    private void activateOutputSprayMode(Minecraft client, StonecutterMenu handler, String reason) {
        if (!outputSprayMode) {
            outputSprayMode = true;
            outputFillSlotsRemaining = 0;
            logCraft("切换永久喷射模式：" + reason);
        }
        if (!sprayInventoryCleared) {
            sprayInventoryCleared = true;
            logCraft("进入喷射模式丢出背包产物");
            dropInventoryCraftResults(client, handler);
        }
    }

    private boolean recoverInput(Minecraft client) {
        ItemStack input = sessionHandler.getSlot(INPUT_SLOT).getItem().copy();
        if (input.isEmpty() || matchesLockedInput(input)) return true;
        if (inputRecoveryAttempted || !ItemStack.isSameItemSameComponents(input, lockedResultTemplate)) {
            logCraft("输入类型与锁定原料不符，停止合成");
            finish(client, Component.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        int target = findAcceptingSlot(sessionHandler, input);
        if (target < 0 || QuickContainerLock.isLockedSlot(sessionHandler, INPUT_SLOT)) {
            logCraft("输入槽误入产物，但没有可安全返还的未锁定槽位");
            finish(client, Component.translatable("quickcraft.message.stonecutter.blocked_input"));
            return false;
        }
        inputRecoveryAttempted = true;
        logCraft("返还误入输入槽的产物");
        click(client, INPUT_SLOT, 0, ContainerInput.PICKUP);
        ItemStack cursor = sessionHandler.getCarried();
        if (sessionHandler.getSlot(INPUT_SLOT).hasItem()
                || !ItemStack.isSameItemSameComponents(cursor, input) || cursor.getCount() != input.getCount()) {
            logCraft("无法完整取出误入输入槽的产物，停止返还");
            finish(client, Component.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        click(client, target, 0, ContainerInput.PICKUP);
        if (sessionHandler.getSlot(INPUT_SLOT).hasItem() || !sessionHandler.getCarried().isEmpty()) {
            logCraft("输入返还未完成，保留槽位和光标现状并停止");
            finish(client, Component.translatable("quickcraft.message.stonecutter.input_changed"));
            return false;
        }
        return true;
    }

    static boolean isExpectedRecipeResult(int expectedRevision, int revision, boolean sameOutput,
                                          int expectedCount, int count) {
        return expectedRevision == revision && sameOutput && expectedCount == count;
    }

    private void applyServerSlotUpdate(Minecraft client, int revision, int slotId, ItemStack stack) {
        if (!isSessionValid(client)) return;
        PredictedSelection expected = predictedSelections.peekFirst();
        if (slotId == OUTPUT_SLOT && expected != null
                && isExpectedRecipeResult(expected.revision(), revision,
                ItemStack.isSameItemSameComponents(expected.output(), stack), expected.output().getCount(), stack.getCount())) {
            predictedSelections.removeFirst();
            // 迟到的选配方结果属于之前的点击，不能把已取空的结果槽重新填满。
            sessionHandler.setItem(OUTPUT_SLOT, predictedRevision, predictedOutput.copy());
            logCraft("收到预期配方结果包，连续发送不等待");
        } else if (slotId == INPUT_SLOT || slotId == OUTPUT_SLOT) {
            stopOnServerCorrection(client);
        } else if (predictedSelections.isEmpty()) {
            predictedRevision = revision;
        } else {
            // 外部拾取等库存变化保留原版结果；后续序号包含仍在途的选配方操作。
            predictedRevision = (revision + predictedSelections.size()) & 32767;
            sessionHandler.setItem(OUTPUT_SLOT, predictedRevision, predictedOutput.copy());
        }
    }

    private void stopOnServerCorrection(Minecraft client) {
        predictedSelections.clear();
        predictedRevision = sessionHandler.getStateId();
        predictedOutput = sessionHandler.getSlot(OUTPUT_SLOT).getItem().copy();
        if (rapidCraftingActive || singleCraftPending) {
            logCraft("服务端纠正预测，保留实际库存并停止发送");
            // 纠正后不按旧库存继续执行结束丢弃。
            rapidCraftingActive = false;
            finish(client, Component.translatable("quickcraft.message.stonecutter.sync_changed"));
        }
    }

    private boolean matchesLockedInput(ItemStack input) {
        return !input.isEmpty() && ItemStack.isSameItemSameComponents(input, lockedInputTemplate);
    }

    public static void refreshClientPrediction(StonecutterMenu handler) {
        QuickCraftStonecutter active = INSTANCE;
        if (active == null || !active.predicting || active.sessionHandler != handler
                || !active.matchesLockedInput(handler.getSlot(INPUT_SLOT).getItem())
                || active.predictionRecipeIndex < 0 || handler.getSelectedRecipeIndex() != active.predictionRecipeIndex
                || handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return;
        }
        // 仅在本次点击内部补齐客户端缺少的结果，让原版连续取出在发包前完成预测。
        handler.getSlot(OUTPUT_SLOT).set(active.lockedResultTemplate.copy());
    }

    private void beginBatch(Phase phase) {
        batchPhase = phase;
        batchId++;
        batchStartRevision = sessionHandler.getStateId();
        batchInput = sessionHandler.getSlot(INPUT_SLOT).getItem().copy();
        batchSourceSlot = -1;
        batchSourceCount = 0;
        batchSentAtNanos = System.nanoTime();
        dispatchedThisTick++;
        logCraft("批次开始");
    }

    public static void onServerSlotUpdate(int syncId, int revision, int slotId, ItemStack stack) {
        QuickCraftStonecutter active = INSTANCE;
        if (active != null && active.sessionHandler != null && active.sessionHandler.containerId == syncId
                && QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
            LOGGER.info("切石机服务端槽位包：会话=#{}，批次=#{}，syncId={}，revision={}，slot={}，物品={}",
                    active.sessionId, active.batchId, syncId, revision, slotId, stack);
        }
        if (active != null && active.sessionHandler != null && active.sessionHandler.containerId == syncId) {
            active.applyServerSlotUpdate(Minecraft.getInstance(), revision, slotId, stack);
        }
    }

    public static void onServerInventoryUpdate(int syncId, int revision,
                                               List<ItemStack> contents, ItemStack cursor) {
        QuickCraftStonecutter active = INSTANCE;
        if (active != null && active.sessionHandler != null && active.sessionHandler.containerId == syncId
                && QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
            LOGGER.info("切石机服务端全量包：会话=#{}，批次=#{}，syncId={}，revision={}，光标={}，内容={}",
                    active.sessionId, active.batchId, syncId, revision, cursor, contents);
        }
        if (active != null && active.sessionHandler != null && active.sessionHandler.containerId == syncId) {
            Minecraft client = Minecraft.getInstance();
            if (active.isSessionValid(client)) active.stopOnServerCorrection(client);
        }
    }

    private void logCraft(String event) {
        if (!QuickCraftConfigs.isMouseCraftAckDebugEnabled() || !LOGGER.isInfoEnabled()) return;
        StringBuilder slots = new StringBuilder();
        if (sessionHandler != null) {
            for (int i = 0; i < sessionHandler.slots.size(); i++) {
                if (i > 0) slots.append(", ");
                slots.append(i);
                if (QuickContainerLock.isLockedSlot(sessionHandler, i)) slots.append("(锁)");
                slots.append('=').append(sessionHandler.getSlot(i).getItem());
            }
        }
        LOGGER.info("切石机合成：会话=#{}，批次=#{}，事件={}，阶段={}，syncId={}，revision={}->{}，"
                        + "目标={}，批前原料={}，补料来源={}x{}，光标={}，喷射={}，填包剩余={}，"
                        + "在途配方包={}，耗时={}ms，槽位=[{}]",
                sessionId, batchId, event, batchId == 0 ? "未发批次" : batchPhase,
                sessionHandler == null ? -1 : sessionHandler.containerId, batchStartRevision,
                sessionHandler == null ? -1 : sessionHandler.getStateId(), lockedResultTemplate,
                batchId == 0 ? ItemStack.EMPTY : batchInput, batchSourceSlot, batchSourceCount,
                sessionHandler == null ? ItemStack.EMPTY : sessionHandler.getCarried(),
                outputSprayMode, outputFillSlotsRemaining, predictedSelections.size(),
                batchSentAtNanos == 0L ? 0L : (System.nanoTime() - batchSentAtNanos) / 1_000_000L, slots);
    }

    private void click(Minecraft client, int slot, int button, ContainerInput action) {
        boolean debug = QuickCraftConfigs.isMouseCraftAckDebugEnabled();
        if (debug) logCraft("点击前：slot=" + slot + "，button=" + button + "，action=" + action);
        client.gameMode.handleContainerInput(sessionHandler.containerId, slot, button, action, client.player);
        if (debug) logCraft("点击后：slot=" + slot + "，button=" + button + "，action=" + action);
    }

    private int unlockedEmptySlots(StonecutterMenu handler) {
        int count = 0;
        for (int i = 2; i < handler.slots.size(); i++) {
            if (!QuickContainerLock.isLockedSlot(handler, i) && !handler.getSlot(i).hasItem()) {
                count++;
            }
        }
        return count;
    }

    private int matchingResultSlots(StonecutterMenu handler) {
        int count = 0;
        for (int i = 2; i < handler.slots.size(); i++) {
            if (!QuickContainerLock.isLockedSlot(handler, i)
                    && ItemStack.isSameItemSameComponents(handler.getSlot(i).getItem(), lockedResultTemplate)) {
                count++;
            }
        }
        return count;
    }

    private boolean hasLockedPlayerSlots(StonecutterMenu handler) {
        for (int i = 2; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i)) {
                return true;
            }
        }
        return false;
    }

    private int findAcceptingSlot(StonecutterMenu handler, ItemStack output) {
        if (output.isEmpty()) {
            return -1;
        }
        int empty = -1;
        for (int i = 2; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i) || !handler.getSlot(i).mayPlace(output)) {
                continue;
            }
            ItemStack stack = handler.getSlot(i).getItem();
            if (stack.isEmpty()) {
                if (empty < 0 && output.getCount() <= handler.getSlot(i).getMaxStackSize(output)) {
                    empty = i;
                }
            } else if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() + output.getCount() <= handler.getSlot(i).getMaxStackSize(output)) {
                return i;
            }
        }
        return empty;
    }

    private boolean isSessionValid(Minecraft client) {
        return QuickCraftConfigs.isStonecutterQuickCraftEnabled()
                && isCraftingContextValid(client) && client.gameMode != null
                && client.player.containerMenu == sessionHandler && client.getConnection() == sessionNetwork;
    }

    private void finish(Minecraft client, Component message) {
        if (QuickCraftConfigs.isMouseCraftAckDebugEnabled()) {
            logCraft("结束会话：" + message.getString());
        }
        if (rapidCraftingActive && isSessionValid(client) && sessionHandler.getCarried().isEmpty()
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
        predictedSelections.clear();
        predictedOutput = ItemStack.EMPTY;
        clearLockedSelection();
    }

    private int dropInventoryCraftResults(Minecraft client, StonecutterMenu handler) {
        if (client.player == null || client.gameMode == null || !handler.getCarried().isEmpty()) {
            return 0;
        }
        int droppedSlots = 0;
        for (int i = 2; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && !QuickContainerLock.isLockedSlot(handler, i)
                    && ItemStack.isSameItemSameComponents(stack, lockedResultTemplate)) {
                logCraft("丢出背包已有产物：slot=" + i);
                client.gameMode.handleContainerInput(handler.containerId, i, 1, ContainerInput.THROW, client.player);
                droppedSlots++;
            }
        }
        return droppedSlots;
    }

    private int findBestSupplyIngredientSlot(Inventory inventory, StonecutterMenu handler) {
        int bestIndex = -1;
        int bestStackCount = -1;
        List<ItemStack> stacks = mainStacks(inventory);
        for (int invIndex = 0; invIndex < stacks.size(); invIndex++) {
            ItemStack stack = stacks.get(invIndex);
            if (isLockedPlayerInventorySlot(handler, invIndex) || !matchesLockedInput(stack)) continue;
            if (stack.getCount() > bestStackCount) {
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }
        return bestIndex;
    }

    private boolean isLockedPlayerInventorySlot(StonecutterMenu handler, int invIndex) {
        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        return handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot);
    }

    private boolean lockCurrentSelection(Minecraft client, StonecutterMenu handler) {
        int selectedIndex = handler.getSelectedRecipeIndex();
        if (!isRecipeIndexAvailable(handler, selectedIndex) && handler.getSlot(OUTPUT_SLOT).hasItem()) {
            selectedIndex = findAvailableRecipeIndexByResult(client, handler, handler.getSlot(OUTPUT_SLOT).getItem());
        }

        ItemStack resultTemplate = ItemStack.EMPTY;
        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        }
        if (resultTemplate.isEmpty() && isRecipeIndexAvailable(handler, selectedIndex)) {
            resultTemplate = getDisplayResultStack(client, handler, selectedIndex);
        }

        if (resultTemplate.isEmpty()) {
            return hasLockedSelection();
        }

        lockedInputTemplate = copyTemplate(handler.getSlot(INPUT_SLOT).getItem());
        lockedResultTemplate = resultTemplate.copy();
        return true;
    }

    private boolean isRecipeIndexAvailable(StonecutterMenu handler, int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < handler.getNumberOfVisibleRecipes();
    }

    private int availableRecipeCount(StonecutterMenu handler) {
        return handler.getNumberOfVisibleRecipes();
    }

    int findAvailableRecipeIndexByResult(Minecraft client,
                                         StonecutterMenu handler,
                                         ItemStack resultTemplate) {
        if (resultTemplate.isEmpty()) {
            return -1;
        }

        int matchedIndex = -1;
        for (int i = 0; i < availableRecipeCount(handler); i++) {
            ItemStack displayedResult = getDisplayResultStack(client, handler, i);
            if (!displayedResult.isEmpty()
                    && ItemStack.isSameItemSameComponents(displayedResult, resultTemplate)) {
                if (matchedIndex >= 0) {
                    return -1;
                }
                matchedIndex = i;
            }
        }
        return matchedIndex;
    }

    private ItemStack getDisplayResultStack(Minecraft client,
                                            StonecutterMenu handler,
                                            int recipeIndex) {
        if (client.level == null || !isRecipeIndexAvailable(handler, recipeIndex)) {
            return ItemStack.EMPTY;
        }

        try {
            return handler.getVisibleRecipes()
                    .entries()
                    .get(recipeIndex)
                    .recipe()
                    .optionDisplay()
                    .resolveForFirstStack(SlotDisplayContext.fromLevel(client.level))
                    .copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
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

    private boolean isCraftingContextValid(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }

        if (!(QuickClientScreenAccess.currentScreen(client) instanceof StonecutterScreen)) {
            return false;
        }

        return client.player.containerMenu instanceof StonecutterMenu;
    }

    private List<ItemStack> mainStacks(Inventory inventory) {
        return inventory.getNonEquipmentItems();
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private boolean isCraftButtonRapidModeHeld(Minecraft client) {
        return QuickCraftKeyBindings.isAltDown()
                && QuickCraftKeyBindings.isLeftMouseButtonDown(client);
    }
}
