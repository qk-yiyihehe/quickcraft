package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台潜影盒喷射执行器。只负责潜影盒直填和对应输出策略，不处理普通配方书合成。
 */
public final class QuickCraftWorkbenchShulkerCraft implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/WorkbenchShulkerCraft");
    private static final int OUTPUT_SLOT = 0;
    private static final int GRID_START = 1;
    private static final int GRID_END = 9;
    private static final int MAX_OUTPUT_BURST = 64;
    private static final int MAX_FAILURES = 3;
    private static QuickCraftWorkbenchShulkerCraft instance;

    private boolean active;
    private boolean startedByButton;
    private boolean lastSingleKeyDown;
    private boolean lastRapidKeyDown;
    private int consecutiveFailures;
    private RecipeEntry<CraftingRecipe> recipe;
    private List<ItemStack> pattern = List.of();
    private ItemStack resultTemplate = ItemStack.EMPTY;
    private int snapshotSyncId = -1;

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LOGGER.info("潜影盒工作台喷射执行器已注册");
    }

    public static boolean handleWorkbenchCraftButton(boolean rapidCraft) {
        if (instance == null || !rapidCraft) {
            return false;
        }
        return instance.start(MinecraftClient.getInstance(), true);
    }

    public static boolean shouldSuppressRecipeGhostSlots() {
        return instance != null && (instance.active || QuickCraftWorkbenchShulker.isShulkerCraftBusy());
    }

    private void onClientTick(MinecraftClient client) {
        QuickCraftWorkbenchShulker.advanceShulkerCraftActionCooldown();
        if (!QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()) {
            stopHelperSafely(client);
            reset();
            return;
        }
        if (!isWorkbenchOpen(client)) {
            stopHelperSafely(client);
            reset();
            return;
        }

        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;
        handleHotkey(client);
        if (active && startedByButton && !isButtonRapidModeHeld(client)) {
            stop(client, Text.translatable("quickcraft.message.crafting.stopped"));
            return;
        }
        if (!active) {
            return;
        }

        if (QuickCraftWorkbenchShulker.isShulkerCraftBusy()) {
            if (!isRapidInputHeld(client)) {
                QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
            }
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> QuickCraftWorkbenchShulker.tickShulkerCraft(client));
            processRefillResult(client, handler);
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> processCraftTick(client, handler));
    }

    private void processCraftTick(MinecraftClient client, CraftingScreenHandler handler) {
        if (hasMissingPerCraftMaterial(handler)) {
            rebalanceIncompleteRepeatedMaterials(client, handler);
            int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
            if (movedLooseItems > 0) {
                primeOutputLocally(client, handler);
                consecutiveFailures = 0;
                return;
            }
            QuickCraftWorkbenchShulker.RefillStart refill =
                    QuickCraftWorkbenchShulker.beginShulkerCraftRefill(handler, pattern);
            LOGGER.debug("不可堆叠材料已消耗，优先请求补料：结果={}，配方={}，合成格={}",
                    refill, recipe == null ? "NONE" : recipe.id(), describePattern(pattern));
            if (refill == QuickCraftWorkbenchShulker.RefillStart.STARTED) {
                runFirstRefillActionImmediately(client, handler);
            } else {
                recordProgressOrStop(client, false);
            }
            return;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            boolean progressed = QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
                    ? storeOutputBurst(client, handler)
                    : throwOutputBurst(client, handler);
            recordProgressOrStop(client, progressed);
            return;
        }

        if (primeOutputLocally(client, handler)) {
            consecutiveFailures = 0;
            return;
        }

        rebalanceIncompleteRepeatedMaterials(client, handler);
        int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
        if (movedLooseItems > 0) {
            primeOutputLocally(client, handler);
            consecutiveFailures = 0;
            return;
        }

        QuickCraftWorkbenchShulker.RefillStart refill =
                QuickCraftWorkbenchShulker.beginShulkerCraftRefill(handler, pattern);
        LOGGER.debug("潜影盒工作台喷射请求补料：结果={}，配方={}，合成格={}",
                refill, recipe == null ? "NONE" : recipe.id(), describePattern(pattern));
        if (refill == QuickCraftWorkbenchShulker.RefillStart.STARTED) {
            runFirstRefillActionImmediately(client, handler);
            return;
        }
        recordProgressOrStop(client, false);
    }

    private void runFirstRefillActionImmediately(MinecraftClient client,
                                                 CraftingScreenHandler handler) {
        consecutiveFailures = 0;
        if (QuickCraftWorkbenchShulker.isShulkerCraftActionCoolingDown()) {
            return;
        }
        QuickCraftWorkbenchShulker.tickShulkerCraft(client);
        processRefillResult(client, handler);
    }

    private boolean throwOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
        long startedAtNanos = System.nanoTime();
        int attempts = getAvailableCraftCount(handler);
        int completed = 0;
        while (completed < attempts && active) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack() && !primeOutputLocally(client, handler)) {
                break;
            }
            if (!isExpectedOutput(handler.getSlot(OUTPUT_SLOT).getStack())) {
                break;
            }
            client.interactionManager.clickSlot(handler.syncId, OUTPUT_SLOT, 1,
                    SlotActionType.THROW, client.player);
            completed++;
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接丢弃 burst：合成={} 次，耗时={} us，配方={}",
                    completed, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return completed > 0;
    }

    private boolean storeOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
        long startedAtNanos = System.nanoTime();
        if (QuickCraftWorkbenchShulkerOutput.isShulkerBox(resultTemplate)) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_output_unsupported"));
            return false;
        }

        int attempts = getAvailableCraftCount(handler);
        int sourceSlot = -1;
        int completed = 0;
        while (completed < attempts && active) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack() && !primeOutputLocally(client, handler)) {
                break;
            }
            ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack().copy();
            if (!isExpectedOutput(output)) {
                break;
            }
            if (sourceSlot == -1
                    || QuickCraftWorkbenchShulkerOutput.getCapacity(handler.getCursorStack(), output) < output.getCount()) {
                if (!QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot)) {
                    stop(client, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                    return completed > 0;
                }
                sourceSlot = QuickCraftWorkbenchShulkerOutput.takeBox(client, handler, output);
                if (sourceSlot == -1) {
                    stop(client, Text.translatable("quickcraft.message.crafting.no_output_shulker"));
                    return completed > 0;
                }
            }
            if (!QuickCraftWorkbenchShulkerOutput.storeOnce(client, handler, output)) {
                QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot);
                stop(client, Text.translatable("quickcraft.message.crafting.shulker_output_failed"));
                return completed > 0;
            }
            completed++;
        }
        if (!QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot)) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接装盒 burst：合成={} 次，耗时={} us，不受补货间隔限制，配方={}",
                    completed, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return completed > 0;
    }

    private void processRefillResult(MinecraftClient client, CraftingScreenHandler handler) {
        QuickCraftWorkbenchShulker.TaskResult result = QuickCraftWorkbenchShulker.consumeShulkerCraftResult();
        if (result == QuickCraftWorkbenchShulker.TaskResult.NONE) {
            return;
        }
        Text message = QuickCraftWorkbenchShulker.consumeShulkerCraftMessage();
        if (result == QuickCraftWorkbenchShulker.TaskResult.STOPPED || !isRapidInputHeld(client)) {
            stop(client, message != null ? message : Text.translatable("quickcraft.message.crafting.stopped"));
            return;
        }
        if (!primeOutputLocally(client, handler)) {
            recordProgressOrStop(client, false);
        }
    }

    private void recordProgressOrStop(MinecraftClient client, boolean progressed) {
        if (progressed) {
            consecutiveFailures = 0;
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_FAILURES) {
            stop(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean start(MinecraftClient client, boolean fromButton) {
        boolean workbenchOpen = isWorkbenchOpen(client);
        boolean available = QuickCraftWorkbenchShulker.isAvailable();
        LOGGER.debug("请求启动潜影盒工作台喷射：fromButton={}，workbenchOpen={}，featureEnabled={}，"
                        + "quickShulkerConfigured={}，available={}",
                fromButton, workbenchOpen, QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled(),
                QuickCraftWorkbenchShulker.isConfigured(), available);
        if (!workbenchOpen || !available) {
            sendMessage(client, Text.translatable("quickcraft.message.crafting.shulker_unavailable"));
            return false;
        }
        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;
        RecipeEntry<CraftingRecipe> currentRecipe = findCurrentRecipe(client, handler);
        boolean capturedCurrentRecipe = currentRecipe != null && handler.getSlot(OUTPUT_SLOT).hasStack();
        boolean canReuseSnapshot = canReuseSnapshot(handler.syncId, snapshotSyncId,
                recipe != null && !pattern.isEmpty() && !resultTemplate.isEmpty());
        if (!capturedCurrentRecipe && !canReuseSnapshot) {
            LOGGER.debug("潜影盒工作台喷射启动失败：recipe={}，output={}，grid={}",
                    currentRecipe == null ? "NONE" : currentRecipe.id(),
                    handler.getSlot(OUTPUT_SLOT).getStack(), describePattern(snapshotPattern(handler)));
            sendMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }
        if (capturedCurrentRecipe) {
            if (hasRemainder(currentRecipe, handler)) {
                sendMessage(client, Text.translatable("quickcraft.message.crafting.shulker_recipe_remainder"));
                return false;
            }
            recipe = currentRecipe;
            pattern = snapshotPattern(handler);
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
            snapshotSyncId = handler.syncId;
            LOGGER.debug("锁定潜影盒工作台配方快照：syncId={}，配方={}，合成格={}",
                    snapshotSyncId, recipe.id(), describePattern(pattern));
        } else {
            LOGGER.debug("复用潜影盒工作台配方快照：syncId={}，配方={}，合成格={}",
                    snapshotSyncId, recipe.id(), describePattern(pattern));
        }
        active = true;
        startedByButton = fromButton;
        consecutiveFailures = 0;
        sendMessage(client, Text.translatable("quickcraft.message.crafting.started"));
        LOGGER.info("开始潜影盒工作台喷射：配方={}，输出装盒={}",
                recipe.id(), QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled());
        return true;
    }

    private boolean primeOutputLocally(MinecraftClient client, CraftingScreenHandler handler) {
        if (client.world == null || recipe == null || resultTemplate.isEmpty()) {
            return false;
        }
        try {
            CraftingRecipeInput input = createInput(handler);
            if (!recipe.value().matches(input, client.world)) {
                return false;
            }
            ItemStack result = recipe.value().craft(input, client.world.getRegistryManager());
            if (!isExpectedOutput(result)) {
                return false;
            }
            Slot outputSlot = handler.getSlot(OUTPUT_SLOT);
            if (!(outputSlot.inventory instanceof CraftingResultInventory resultInventory)) {
                return false;
            }
            resultInventory.setLastRecipe(recipe);
            resultInventory.setStack(outputSlot.getIndex(), result.copy());
            return isExpectedOutput(outputSlot.getStack());
        } catch (Throwable throwable) {
            LOGGER.debug("本地计算潜影盒合成产物失败：配方={}", recipe.id(), throwable);
            return false;
        }
    }

    private int getAvailableCraftCount(CraftingScreenHandler handler) {
        int attempts = MAX_OUTPUT_BURST;
        boolean hasIngredient = false;
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty()) {
                hasIngredient = true;
                attempts = Math.min(attempts, stack.getCount());
            }
        }
        return hasIngredient ? attempts : 0;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getCount() == resultTemplate.getCount()
                && ItemStack.areItemsAndComponentsEqual(stack, resultTemplate);
    }

    private RecipeEntry<CraftingRecipe> findCurrentRecipe(MinecraftClient client,
                                                           CraftingScreenHandler handler) {
        try {
            Optional<RecipeEntry<CraftingRecipe>> match = client.world.getRecipeManager().getFirstMatch(
                    RecipeType.CRAFTING, createInput(handler), client.world);
            return match.orElse(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean hasRemainder(RecipeEntry<CraftingRecipe> currentRecipe,
                                 CraftingScreenHandler handler) {
        try {
            for (ItemStack remainder : currentRecipe.value().getRemainder(createInput(handler))) {
                if (!remainder.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            return true;
        }
        return false;
    }

    static boolean canDirectFillRecipe(boolean recipeKnown, boolean hasRemainder) {
        return recipeKnown && !hasRemainder;
    }

    static boolean isPerCraftMaterial(int maxCount) {
        return maxCount == 1;
    }

    static boolean canReuseSnapshot(int currentSyncId, int capturedSyncId, boolean snapshotComplete) {
        return currentSyncId == capturedSyncId && snapshotComplete;
    }

    private boolean hasMissingPerCraftMaterial(CraftingScreenHandler handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            if (!expected.isEmpty()
                    && isPerCraftMaterial(expected.getMaxCount())
                    && handler.getSlot(GRID_START + patternIndex).getStack().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int fillMissingSlotsFromPlayerInventory(MinecraftClient client,
                                                    CraftingScreenHandler handler) {
        if (client.player == null || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()) {
            return 0;
        }

        long startedAtNanos = System.nanoTime();
        int movedItems = 0;
        int operations = 0;
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack template = pattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }

            for (int attempt = 0; attempt < PlayerInventory.MAIN_SIZE; attempt++) {
                List<Integer> targetSlots = getFillablePatternSlots(handler, template);
                if (targetSlots.isEmpty()) {
                    break;
                }
                int emptySlots = countEmptySlots(handler, targetSlots);
                int availableItems = countMatchingPlayerItems(handler, template);
                if (!canLooseItemsCompleteEmptyPatternSlots(availableItems, emptySlots)) {
                    break;
                }
                int sourceSlot = findSmallestMatchingPlayerStack(client.player.getInventory(), handler, template);
                if (sourceSlot == -1) {
                    break;
                }

                int before = countMatchingItems(handler, targetSlots, template);
                if (!distributePlayerStack(client, handler, sourceSlot, targetSlots, template)) {
                    break;
                }
                int moved = countMatchingItems(handler, targetSlots, template) - before;
                if (moved <= 0) {
                    break;
                }
                movedItems += moved;
                operations++;
            }
        }

        if (movedItems > 0) {
            LOGGER.debug("背包散装材料补格：移动={} 个，来源堆={}，耗时={} us，配方={}",
                    movedItems, operations, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return movedItems;
    }

    private boolean hasEarlierMatchingPatternStack(int patternIndex, ItemStack template) {
        for (int i = 0; i < patternIndex; i++) {
            ItemStack earlier = pattern.get(i);
            if (!earlier.isEmpty() && ItemStack.areItemsAndComponentsEqual(earlier, template)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getFillablePatternSlots(CraftingScreenHandler handler, ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            ItemStack expected = pattern.get(i);
            if (expected.isEmpty() || !ItemStack.areItemsAndComponentsEqual(expected, template)) {
                continue;
            }
            int slotId = GRID_START + i;
            Slot slot = handler.getSlot(slotId);
            ItemStack current = slot.getStack();
            if ((current.isEmpty() || ItemStack.areItemsAndComponentsEqual(current, template))
                    && current.getCount() < slot.getMaxItemCount(template)
                    && slot.canInsert(template)) {
                slots.add(slotId);
            }
        }
        slots.sort((left, right) -> Integer.compare(
                handler.getSlot(left).getStack().getCount(),
                handler.getSlot(right).getStack().getCount()));
        return slots;
    }

    private int rebalanceIncompleteRepeatedMaterials(MinecraftClient client,
                                                     CraftingScreenHandler handler) {
        if (client.player == null || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()) {
            return 0;
        }

        long startedAtNanos = System.nanoTime();
        int movedItems = 0;
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack template = pattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            List<Integer> groupSlots = getMatchingPatternSlots(template);
            int emptySlots = countEmptySlots(handler, groupSlots);
            if (!shouldRebalancePatternGroup(groupSlots.size(), emptySlots)) {
                continue;
            }

            movedItems += rebalancePatternGroupInGrid(client, handler, groupSlots, template);
        }
        if (movedItems > 0) {
            LOGGER.debug("复杂配方尾数工作台内重新均分：移动={} 个，耗时={} us，配方={}",
                    movedItems, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return movedItems;
    }

    private int rebalancePatternGroupInGrid(MinecraftClient client,
                                             CraftingScreenHandler handler,
                                             List<Integer> groupSlots,
                                             ItemStack template) {
        int total = 0;
        for (int slotId : groupSlots) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        if (total <= 0) {
            return 0;
        }

        int base = total / groupSlots.size();
        int remainder = total % groupSlots.size();
        int movedItems = 0;
        for (int targetIndex = 0; targetIndex < groupSlots.size(); targetIndex++) {
            int targetSlotId = groupSlots.get(targetIndex);
            int targetGoal = base + (targetIndex < remainder ? 1 : 0);
            int targetCount = matchingGridCount(handler.getSlot(targetSlotId).getStack(), template);
            if (targetCount >= targetGoal) {
                continue;
            }

            while (targetCount < targetGoal) {
                boolean movedFromSource = false;
                for (int sourceIndex = 0; sourceIndex < groupSlots.size(); sourceIndex++) {
                    int sourceSlotId = groupSlots.get(sourceIndex);
                    if (sourceSlotId == targetSlotId) {
                        continue;
                    }
                    int sourceCount = matchingGridCount(handler.getSlot(sourceSlotId).getStack(), template);
                    int sourceGoal = base + (sourceIndex < remainder ? 1 : 0);
                    if (sourceCount <= sourceGoal) {
                        continue;
                    }
                    int amount = Math.min(sourceCount - sourceGoal, targetGoal - targetCount);
                    int moved = moveGridItems(client, handler, sourceSlotId, targetSlotId, amount, template);
                    if (moved <= 0) {
                        continue;
                    }
                    movedItems += moved;
                    targetCount += moved;
                    movedFromSource = true;
                    break;
                }
                if (!movedFromSource) {
                    break;
                }
            }
        }
        return movedItems;
    }

    private int moveGridItems(MinecraftClient client,
                              CraftingScreenHandler handler,
                              int sourceSlotId,
                              int targetSlotId,
                              int amount,
                              ItemStack template) {
        if (amount <= 0 || !handler.getCursorStack().isEmpty()) {
            return 0;
        }
        Slot source = handler.getSlot(sourceSlotId);
        Slot target = handler.getSlot(targetSlotId);
        ItemStack sourceStack = source.getStack();
        ItemStack targetStack = target.getStack();
        if (sourceStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(sourceStack, template)
                || (!targetStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(targetStack, template))) {
            return 0;
        }

        int sourceBefore = sourceStack.getCount();
        int targetBefore = matchingGridCount(targetStack, template);
        int capacity = target.getMaxItemCount(template) - targetBefore;
        int requested = Math.min(amount, Math.min(sourceBefore, capacity));
        if (requested <= 0) {
            return 0;
        }

        // 能整堆搬运时只发两个槽位操作；尾数才退化为右键逐个补齐，避免把材料放进背包。
        if (requested == sourceBefore && targetBefore == 0) {
            client.interactionManager.clickSlot(handler.syncId, sourceSlotId, 0,
                    SlotActionType.PICKUP, client.player);
            if (!sameCursorMaterial(handler.getCursorStack(), template)) {
                returnCursorToGridSlot(client, handler, sourceSlotId);
                return 0;
            }
            client.interactionManager.clickSlot(handler.syncId, targetSlotId, 0,
                    SlotActionType.PICKUP, client.player);
        } else {
            client.interactionManager.clickSlot(handler.syncId, sourceSlotId, 0,
                    SlotActionType.PICKUP, client.player);
            if (!sameCursorMaterial(handler.getCursorStack(), template)) {
                returnCursorToGridSlot(client, handler, sourceSlotId);
                return 0;
            }
            int moved = 0;
            while (moved < requested && !handler.getCursorStack().isEmpty()) {
                int before = matchingGridCount(handler.getSlot(targetSlotId).getStack(), template);
                client.interactionManager.clickSlot(handler.syncId, targetSlotId, 1,
                        SlotActionType.PICKUP, client.player);
                int after = matchingGridCount(handler.getSlot(targetSlotId).getStack(), template);
                if (after <= before) {
                    break;
                }
                moved += after - before;
            }
            returnCursorToGridSlot(client, handler, sourceSlotId);
        }

        int sourceAfter = matchingGridCount(handler.getSlot(sourceSlotId).getStack(), template);
        int targetAfter = matchingGridCount(handler.getSlot(targetSlotId).getStack(), template);
        return Math.max(0, Math.min(requested, targetAfter - targetBefore))
                + Math.max(0, sourceBefore - sourceAfter - requested);
    }

    private boolean returnCursorToGridSlot(MinecraftClient client,
                                           CraftingScreenHandler handler,
                                           int slotId) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        client.interactionManager.clickSlot(handler.syncId, slotId, 0,
                SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
    }

    private int matchingGridCount(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)
                ? stack.getCount() : 0;
    }

    private boolean sameCursorMaterial(ItemStack cursor, ItemStack template) {
        return !cursor.isEmpty() && ItemStack.areItemsAndComponentsEqual(cursor, template);
    }

    private List<Integer> getMatchingPatternSlots(ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            ItemStack expected = pattern.get(i);
            if (!expected.isEmpty() && ItemStack.areItemsAndComponentsEqual(expected, template)) {
                slots.add(GRID_START + i);
            }
        }
        return slots;
    }

    private int countEmptySlots(CraftingScreenHandler handler, List<Integer> slotIds) {
        int count = 0;
        for (int slotId : slotIds) {
            if (!handler.getSlot(slotId).hasStack()) {
                count++;
            }
        }
        return count;
    }

    private int countMatchingPlayerItems(CraftingScreenHandler handler, ItemStack template) {
        int count = 0;
        for (int inventoryIndex = 0; inventoryIndex < PlayerInventory.MAIN_SIZE; inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static boolean canLooseItemsCompleteEmptyPatternSlots(int availableItems, int emptySlots) {
        return emptySlots == 0 || availableItems >= emptySlots;
    }

    static boolean shouldRebalancePatternGroup(int patternSlots, int emptySlots) {
        return patternSlots > 1 && emptySlots > 0 && emptySlots < patternSlots;
    }

    private int findSmallestMatchingPlayerStack(PlayerInventory inventory,
                                                CraftingScreenHandler handler,
                                                ItemStack template) {
        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int inventoryIndex = 0; inventoryIndex < inventory.main.size(); inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty()
                    && stack.getCount() < bestCount
                    && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                bestSlot = handlerSlot;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
    }

    private boolean distributePlayerStack(MinecraftClient client,
                                          CraftingScreenHandler handler,
                                          int sourceSlot,
                                          List<Integer> targetSlots,
                                          ItemStack template) {
        int sourceCount = handler.getSlot(sourceSlot).getStack().getCount();
        int targetCount = Math.min(sourceCount, targetSlots.size());
        if (targetCount <= 0) {
            return false;
        }

        List<Integer> selectedTargets = new ArrayList<>(targetSlots.subList(0, targetCount));
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0,
                SlotActionType.PICKUP, client.player);
        if (handler.getCursorStack().isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) {
            return false;
        }

        if (selectedTargets.size() == 1) {
            client.interactionManager.clickSlot(handler.syncId, selectedTargets.getFirst(), 0,
                    SlotActionType.PICKUP, client.player);
        } else {
            client.interactionManager.clickSlot(handler.syncId, -999,
                    ScreenHandler.packQuickCraftData(0, 0), SlotActionType.QUICK_CRAFT, client.player);
            for (int targetSlot : selectedTargets) {
                client.interactionManager.clickSlot(handler.syncId, targetSlot,
                        ScreenHandler.packQuickCraftData(1, 0), SlotActionType.QUICK_CRAFT, client.player);
            }
            client.interactionManager.clickSlot(handler.syncId, -999,
                    ScreenHandler.packQuickCraftData(2, 0), SlotActionType.QUICK_CRAFT, client.player);
        }
        return returnCursorStack(client, handler, sourceSlot);
    }

    private boolean returnCursorStack(MinecraftClient client,
                                      CraftingScreenHandler handler,
                                      int preferredSlot) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        if (canAcceptStack(handler.getSlot(preferredSlot).getStack(), handler.getCursorStack())) {
            client.interactionManager.clickSlot(handler.syncId, preferredSlot, 0,
                    SlotActionType.PICKUP, client.player);
        }
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        for (int inventoryIndex = 0; inventoryIndex < PlayerInventory.MAIN_SIZE; inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot != -1
                    && canAcceptStack(handler.getSlot(handlerSlot).getStack(), handler.getCursorStack())) {
                client.interactionManager.clickSlot(handler.syncId, handlerSlot, 0,
                        SlotActionType.PICKUP, client.player);
                if (handler.getCursorStack().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countMatchingItems(CraftingScreenHandler handler,
                                   List<Integer> slotIds,
                                   ItemStack template) {
        int count = 0;
        for (int slotId : slotIds) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean canAcceptStack(ItemStack target, ItemStack cursor) {
        return target.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(target, cursor)
                && target.getCount() + cursor.getCount() <= target.getMaxCount());
    }

    private int playerInventoryIndexToHandlerSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 8) {
            return 37 + inventoryIndex;
        }
        if (inventoryIndex >= 9 && inventoryIndex < PlayerInventory.MAIN_SIZE) {
            return 10 + inventoryIndex - 9;
        }
        return -1;
    }

    private CraftingRecipeInput createInput(CraftingScreenHandler handler) {
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            stacks.add(handler.getSlot(slotId).getStack().copy());
        }
        return CraftingRecipeInput.create(3, 3, stacks);
    }

    private List<ItemStack> snapshotPattern(CraftingScreenHandler handler) {
        List<ItemStack> snapshot = new ArrayList<>(9);
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            snapshot.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        return snapshot;
    }

    private void handleHotkey(MinecraftClient client) {
        boolean singleDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();
        if (singleDown && !lastSingleKeyDown && !active) {
            QuickCraftWorkbench.handleWorkbenchCraftButton(false);
        }
        if (rapidDown && !lastRapidKeyDown) {
            start(client, false);
        }
        if (!rapidDown && active && !startedByButton) {
            stop(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }
        lastSingleKeyDown = singleDown;
        lastRapidKeyDown = rapidDown;
    }

    private boolean isRapidInputHeld(MinecraftClient client) {
        return startedByButton
                ? isButtonRapidModeHeld(client)
                : QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();
    }

    private boolean isButtonRapidModeHeld(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean altDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        return altDown && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private boolean isWorkbenchOpen(MinecraftClient client) {
        return client != null && client.player != null && client.world != null
                && client.currentScreen instanceof CraftingScreen
                && client.player.currentScreenHandler instanceof CraftingScreenHandler;
    }

    private void stopHelperSafely(MinecraftClient client) {
        if (QuickCraftWorkbenchShulker.isShulkerCraftBusy()) {
            QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> QuickCraftWorkbenchShulker.tickShulkerCraft(client));
        }
    }

    private void stop(MinecraftClient client, Text message) {
        LOGGER.debug("停止潜影盒工作台喷射：配方={}，连续失败={}，补料中={}，原因={}",
                recipe == null ? "NONE" : recipe.id(), consecutiveFailures,
                QuickCraftWorkbenchShulker.isShulkerCraftBusy(),
                message == null ? "无" : message.getString());
        if (QuickCraftWorkbenchShulker.isShulkerCraftBusy()) {
            QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> QuickCraftWorkbenchShulker.tickShulkerCraft(client));
            QuickCraftWorkbenchShulker.consumeShulkerCraftResult();
            QuickCraftWorkbenchShulker.consumeShulkerCraftMessage();
        }
        active = false;
        startedByButton = false;
        consecutiveFailures = 0;
        sendMessage(client, message);
    }

    private void reset() {
        if (recipe != null) {
            LOGGER.debug("关闭工作台，释放潜影盒配方快照：syncId={}，配方={}", snapshotSyncId, recipe.id());
        }
        active = false;
        startedByButton = false;
        consecutiveFailures = 0;
        recipe = null;
        pattern = List.of();
        resultTemplate = ItemStack.EMPTY;
        snapshotSyncId = -1;
        lastSingleKeyDown = false;
        lastRapidKeyDown = false;
        QuickCraftWorkbenchShulker.resetShulkerCraft();
    }

    private static String describePattern(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                parts.add((i + 1) + "=" + stack.getName().getString());
            }
        }
        return parts.toString();
    }

    private void sendMessage(MinecraftClient client, Text message) {
        if (message != null && client != null && client.player != null) {
            client.player.sendMessage(message, true);
        }
    }
}
