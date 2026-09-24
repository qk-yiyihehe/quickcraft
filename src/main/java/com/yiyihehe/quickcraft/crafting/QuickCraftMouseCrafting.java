package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 工作台与背包 2x2 共用的鼠标点击合成核心。
 * 原版物理槽号只由 layout 转换，快照、特殊配方补料、锁格和停止收尾均保持同一实现。
 */
final class QuickCraftMouseCrafting {

    private final QuickCraftMouseCraftLayout.Layout layout;
    private final BooleanSupplier enabled;
    private final Runnable clearRecipeGhosts;
    private final QuickCraftMouseCraftAckExecutor mouseCraftAckExecutor;

    private static final int RAPID_INTERVAL = 1;

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // 服务端结果包最多等 10 Tick，但产物一到就立即继续。
    private static final int CRAFTING_RESULT_WAIT_TICKS = 10;

    private static final int OUTPUT_SLOT = 0;

    // 单轮最多处理一组原料，避免异常合成格导致无界发送产物槽点击。
    private static final int MAX_OUTPUT_THROW_BURST = 64;

    private boolean lastVDown = false;

    private boolean lastAltCDown = false;

    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    private boolean mouseCraftAckLegacyFallback = false;
    private boolean mouseCraftAckManualRestockFallback = false;

    private int rapidCooldown = 0;

    private int consecutiveFailures = 0;

    private int craftingResultWaitTicks = 0;

    // 阻止特殊配方在同一 Tick 内重复读取 burst 后的预测状态；下一 Tick 立即继续。
    private int manualGridSyncWaitTicks = 0;

    private RecipeEntry<CraftingRecipe> lockedRecipe = null;

    private NetworkRecipeId lockedRecipeId = null;

    private List<ItemStack> lockedCraftingPattern = new ArrayList<>();
    private final List<ItemStack> knownRecipeRemainders = new ArrayList<>();

    private ItemStack lockedResultTemplate = ItemStack.EMPTY;

    private enum ManualPatternState {
        COMPLETE,
        MISSING,
        INVALID
    }

    QuickCraftMouseCrafting(QuickCraftMouseCraftLayout.Layout layout,
                                  BooleanSupplier enabled,
                                  Runnable clearRecipeGhosts) {
        this.layout = layout;
        this.enabled = enabled;
        this.clearRecipeGhosts = clearRecipeGhosts != null ? clearRecipeGhosts : () -> { };
        this.mouseCraftAckExecutor = new QuickCraftMouseCraftAckExecutor(
                layout,
                this::fallbackMouseCraftAck,
                this::refillAckSnapshot,
                this::hasItemsForMissingPatternSlots
        );
    }

    boolean handleCraftButton(boolean rapidCraft) {
        return handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    boolean isRapidCraftingActive() {
        return rapidCraftingActive;
    }

    void tick(MinecraftClient client) {
        if (!enabled.getAsBoolean()) {
            resetAll();
            trackHotkeyState();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;

        if (mouseCraftAckExecutor.isActive()) {
            trackHotkeyStateWhileBlocked();
            mouseCraftAckExecutor.tick(client);
            return;
        }

        if (QuickCraftMouseCraftAckExecutor.isCanceledBatchDraining(handler)) {
            trackHotkeyStateWhileBlocked();
            return;
        }

        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }

        if (tryStartMouseCraftAck(client, handler)) {
            mouseCraftAckExecutor.tick(client);
            return;
        }

        if (rapidCraftingActive && hasLockedCraftingPlan()
                && !mouseCraftAckExecutor.isActive()) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }
    }

    private void processRapidCraftTick(MinecraftClient client,
                                       ScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        if (waitForManualGridSync()) {
            return;
        }
        if (waitForCraftingResult(client, handler)) {
            return;
        }

        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            }
            if (!rapidCraftingActive || craftingResultWaitTicks > 0 || manualGridSyncWaitTicks > 0) {
                break;
            }
            if (!progressed) {
                break;
            }
        }

        if (anyProgress) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
            }
        }
    }

    private boolean runOneCraftSubLoop(MinecraftClient client,
                                       ScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {

        if (client.player == null || client.interactionManager == null || client.world == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        }

        boolean manualRecipe = shouldManualRestock(recipe);
        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            if (manualRecipe) {
                ManualPatternState patternState = getManualPatternState(handler);
                if (patternState == ManualPatternState.INVALID) {
                    return false;
                }
                if (!rapidCraftingActive) {
                    return tryTakeOutputForRecipe(client, handler, recipe);
                }

                boolean filled = fillManualPatternStacks(client, handler);
                patternState = getManualPatternState(handler);
                if (patternState != ManualPatternState.COMPLETE
                        || !isLockedResult(handler.getSlot(OUTPUT_SLOT).getStack())) {
                    return filled;
                }

                boolean thrown = throwCraftingOutput(client, handler);
                if (thrown) {
                    beginManualGridSync();
                }
                return thrown;
            }

            return rapidCraftingActive
                    ? throwCraftingOutput(client, handler)
                    : tryTakeOutputForRecipe(client, handler, recipe);
        }

        if (restockCraftingGrid(client, handler, recipe)) {
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            if (!manualRecipe || !rapidCraftingActive) {
                tryTakeOutputForRecipe(client, handler, recipe);
            }
            return true;
        }

        return false;
    }

    private boolean waitForCraftingResult(MinecraftClient client, ScreenHandler handler) {
        if (craftingResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            craftingResultWaitTicks = 0;
            return false;
        }

        craftingResultWaitTicks--;
        if (craftingResultWaitTicks <= 0) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }

    private boolean waitForManualGridSync() {
        if (manualGridSyncWaitTicks <= 0) {
            return false;
        }

        manualGridSyncWaitTicks--;
        return manualGridSyncWaitTicks > 0;
    }

    private void beginManualGridSync() {
        manualGridSyncWaitTicks = 1;
    }

    private void handleSingleCraft(MinecraftClient client, ScreenHandler handler) {

        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasStack()) {
            lockCurrentRecipe(client, currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (success && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                    client, handler, layout, lockedResultTemplate, "单次合成结束丢出背包产物");
        }
        if (!success) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }
        if (mouseCraftAckExecutor.isActive()) {
            return true;
        }

        ScreenHandler handler = (ScreenHandler) client.player.currentScreenHandler;
        if (QuickCraftMouseCraftAckExecutor.isCanceledBatchDraining(handler)) {
            return true;
        }
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean restockCraftingGrid(MinecraftClient client,
                                        ScreenHandler handler,
                                        RecipeEntry<CraftingRecipe> recipe) {
        dropKnownRecipeRemainders(client, handler);
        relocateMismatchedGridItems(client, handler);
        if (rapidCraftingActive) {
            return fillManualPatternStacks(client, handler);
        }
        return restockCraftingGridFromPattern(client, handler);
    }

    private boolean fillManualPatternStacks(MinecraftClient client,
                                            ScreenHandler handler) {
        return fillFullStacksThenTail(client, handler, MAX_OUTPUT_THROW_BURST);
    }

    private boolean refillAckSnapshot(MinecraftClient client,
                                      ScreenHandler handler,
                                      int maxSourceStacksPerIngredient) {
        if (!rapidCraftingActive || !hasLockedCraftingPlan()
                || client.player == null
                || client.player.currentScreenHandler != handler
                || QuickCraftMouseCraftLayout.fromHandler(handler) != layout) {
            return false;
        }

        dropKnownRecipeRemainders(client, handler);
        int relocated = relocateMismatchedGridItems(client, handler);
        if (getManualPatternState(handler) == ManualPatternState.INVALID) {
            relocated += relocateMismatchedGridItems(client, handler);
        }
        ManualPatternState beforeFill = getManualPatternState(handler);
        if (beforeFill == ManualPatternState.MISSING
                && !hasTotalItemsForMissingPatternSlots(handler)
                && rebalanceGridTail(client, handler)) {
            return true;
        }
        if (beforeFill == ManualPatternState.MISSING
                && !hasTotalItemsForMissingPatternSlots(handler)) {
            return relocated > 0;
        }

        // A duplicate ingredient only uses the 64-per-slot path when every
        // fillable occurrence has its own full source stack. The final 1..N
        // source stacks are distributed together, so 128 planks for three slab
        // slots becomes 42/42/42 instead of the invalid 64/64/empty pattern.
        boolean filled = fillFullStacksThenTail(
                client, handler, maxSourceStacksPerIngredient);
        ManualPatternState state = getManualPatternState(handler);
        boolean outputPresent = handler.getSlot(OUTPUT_SLOT).hasStack();
        return relocated > 0 || filled || (state == ManualPatternState.COMPLETE && outputPresent);
    }

    private boolean fillFullStacksThenTail(MinecraftClient client,
                                           ScreenHandler handler,
                                           int maxSourceStacksPerIngredient) {
        if (retainIngredientSamples()) {
            // 留样模式的安全上限按整个 ACK 补货批次计算；不能先走“整栈”再走“尾料”，
            // 否则两次 refillWithSamples() 会分别认为同一配方格尚未在本批操作过。
            return refillWithSamples(client, handler);
        }
        boolean filled = fillManualPatternStacks(
                client, handler, maxSourceStacksPerIngredient, true, true);
        ManualPatternState stateAfterFullStacks = getManualPatternState(handler);
        if (stateAfterFullStacks == ManualPatternState.COMPLETE) {
            filled |= topUpCompletePattern(client, handler, maxSourceStacksPerIngredient);
            filled |= fillManualPatternStacks(
                    client, handler, maxSourceStacksPerIngredient, false, false);
        } else if (stateAfterFullStacks == ManualPatternState.MISSING
                && hasTotalItemsForMissingPatternSlots(handler)) {
            filled |= fillManualPatternStacks(
                    client, handler, maxSourceStacksPerIngredient, false, false);
        }
        return filled;
    }

    private boolean topUpCompletePattern(MinecraftClient client,
                                         ScreenHandler handler,
                                         int maxSourceStacksPerIngredient) {
        var player = client.player;
        var interactionManager = client.interactionManager;
        if (player == null || interactionManager == null
                || retainIngredientSamples()
                || !handler.getCursorStack().isEmpty()
                || getManualPatternState(handler) != ManualPatternState.COMPLETE) {
            return false;
        }

        int sourceBudget = Math.max(1, maxSourceStacksPerIngredient);
        boolean movedAny = false;
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            List<Integer> fillableSlots = getFillablePatternSlots(handler, template);
            if (fillableSlots.isEmpty()) {
                continue;
            }
            int occurrences = patternIngredientOccurrences(template);
            int remainingCapacity = fillableSlots.stream()
                    .mapToInt(slotId -> handler.getSlot(slotId).getMaxItemCount(template)
                            - handler.getSlot(slotId).getStack().getCount())
                    .sum();
            if (!QuickCraftMouseCraftInventory.canQuickTopUpCompleteGroup(
                    occurrences,
                    countMatchingUnlockedItems(handler, template),
                    remainingCapacity)) {
                continue;
            }

            int attempts = 0;
            while (attempts < sourceBudget && hasFillablePatternSlot(handler, template)) {
                int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                        player.getInventory(), handler, template, 1);
                if (sourceSlot == -1) {
                    break;
                }
                List<Integer> targets = getFillablePatternSlots(handler, template);
                int beforeCount = countMatchingItemsInSlots(handler, targets, template);
                boolean moved = distributeIngredientStackAcrossPatternSlots(
                        client,
                        handler,
                        sourceSlot,
                        targets,
                        template,
                        true
                );
                int afterCount = countMatchingItemsInSlots(handler, targets, template);
                if (!moved || afterCount <= beforeCount || !handler.getCursorStack().isEmpty()) {
                    break;
                }
                attempts++;
                movedAny = true;
            }
        }
        return movedAny;
    }

    private int patternIngredientOccurrences(ItemStack template) {
        int occurrences = 0;
        for (ItemStack patternStack : lockedCraftingPattern) {
            if (!patternStack.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                occurrences++;
            }
        }
        return occurrences;
    }

    private List<QuickCraftMouseCraftInventory.GridBalanceMove> planGridTailBalance(ScreenHandler handler) {
        List<QuickCraftMouseCraftInventory.GridBalanceMove> moves = new ArrayList<>();
        if (getManualPatternState(handler) != ManualPatternState.MISSING) {
            return moves;
        }
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            for (int index = patternIndex; index < lockedCraftingPattern.size(); index++) {
                if (ItemStack.areItemsAndComponentsEqual(lockedCraftingPattern.get(index), template)) {
                    slots.add(layout.gridSlotId(index));
                }
            }
            int[] counts = slots.stream().mapToInt(slot -> handler.getSlot(slot).getStack().getCount()).toArray();
            for (var move : QuickCraftMouseCraftInventory.planGridTailBalance(counts)) {
                moves.add(new QuickCraftMouseCraftInventory.GridBalanceMove(
                        slots.get(move.source()), slots.get(move.target()), move.count()));
            }
        }
        return moves;
    }

    private boolean rebalanceGridTail(MinecraftClient client, ScreenHandler handler) {
        var moves = planGridTailBalance(handler);
        if (moves.isEmpty() || !handler.getCursorStack().isEmpty()) {
            return false;
        }
        for (var move : moves) {
            int sourceCount = handler.getSlot(move.source()).getStack().getCount();
            int targetCount = handler.getSlot(move.target()).getStack().getCount();
            client.interactionManager.clickSlot(handler.syncId, move.source(), 0, SlotActionType.PICKUP, client.player);
            if (handler.getCursorStack().getCount() != sourceCount
                    || handler.getSlot(move.source()).hasStack()) {
                return true;
            }
            for (int placed = 0; placed < move.count(); placed++) {
                client.interactionManager.clickSlot(handler.syncId, move.target(), 1, SlotActionType.PICKUP, client.player);
            }
            if (!handler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, move.source(), 0, SlotActionType.PICKUP, client.player);
            }
            if (!handler.getCursorStack().isEmpty()
                    || handler.getSlot(move.source()).getStack().getCount() != sourceCount - move.count()
                    || handler.getSlot(move.target()).getStack().getCount() != targetCount + move.count()) {
                return true;
            }
        }
        return true;
    }

    private boolean hasTotalItemsForMissingPatternSlots(ScreenHandler handler) {
        if (retainIngredientSamples()) {
            return hasSampleRefillCapacity(handler);
        }
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            int missing = 0;
            for (int i = patternIndex; i < lockedCraftingPattern.size(); i++) {
                ItemStack sameTemplate = lockedCraftingPattern.get(i);
                if (sameTemplate.isEmpty()
                        || !ItemStack.areItemsAndComponentsEqual(sameTemplate, template)) {
                    continue;
                }
                if (!handler.getSlot(layout.gridSlotId(i)).hasStack()) {
                    missing++;
                }
            }
            if (missing == 0) {
                continue;
            }

            int available = 0;
            for (int inventoryIndex = 0;
                 inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
                 inventoryIndex++) {
                int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
                if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                    continue;
                }
                ItemStack source = handler.getSlot(handlerSlot).getStack();
                if (!source.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(source, template)) {
                    available += source.getCount();
                }
            }
            if (available < missing) {
                return false;
            }
        }
        return true;
    }

    private boolean fillManualPatternStacks(MinecraftClient client,
                                            ScreenHandler handler,
                                            int maxSourceStacksPerIngredient,
                                            boolean moveWholeStackToSingleSlot) {
        return fillManualPatternStacks(
                client, handler, maxSourceStacksPerIngredient,
                moveWholeStackToSingleSlot, false);
    }

    private boolean fillManualPatternStacks(MinecraftClient client,
                                            ScreenHandler handler,
                                            int maxSourceStacksPerIngredient,
                                            boolean moveWholeStackToSingleSlot,
                                            boolean fullStacksOnly) {
        if (client.player == null || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()) {
            return false;
        }
        if (getManualPatternState(handler) == ManualPatternState.INVALID) {
            return false;
        }

        if (retainIngredientSamples()) {
            return refillWithSamples(client, handler);
        }
        int sourceStackBudget = Math.max(1, maxSourceStacksPerIngredient);
        boolean movedAny = false;
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            if (fullStacksOnly && !hasEnoughFullStacksForMissingPatternSlots(handler, template)) {
                continue;
            }

            int attempts = 0;
            // 普通阶段只把完整来源栈放进空配方格，不反复给半栈补到 64；
            // 尾料阶段才把所有剩余来源汇总到当前数量最低的同类格。
            while (attempts < sourceStackBudget
                    && (moveWholeStackToSingleSlot
                    ? hasMissingPatternSlot(handler, template)
                    : hasFillablePatternSlot(handler, template))) {
                List<Integer> targetSlots = moveWholeStackToSingleSlot
                        ? getMissingPatternSlots(handler, 0, template)
                        : getLowestFillablePatternSlots(handler, template);
                if (targetSlots.isEmpty()) {
                    break;
                }

                int minimumSourceCount = fullStacksOnly
                        ? usableIngredientCount(template.getMaxCount())
                        : targetSlots.size();
                int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                        client.player.getInventory(), handler, template, minimumSourceCount);
                int beforeCount = countMatchingItemsInSlots(handler, targetSlots, template);
                boolean movedStack;
                if (sourceSlot != -1) {
                    movedStack = distributeIngredientStackAcrossPatternSlots(
                            client,
                            handler,
                            sourceSlot,
                            targetSlots,
                            template,
                            moveWholeStackToSingleSlot
                    );
                } else if (!fullStacksOnly) {
                    movedStack = distributeOneRoundFromMultipleSources(
                            client, handler, targetSlots, template);
                } else {
                    movedStack = false;
                }
                if (!movedStack) {
                    break;
                }
                attempts++;

                int afterCount = countMatchingItemsInSlots(handler, targetSlots, template);
                if (afterCount <= beforeCount) {
                    break;
                }
                movedAny = true;
            }
        }
        return movedAny;
    }

    private boolean distributeOneRoundFromMultipleSources(MinecraftClient client,
                                                           ScreenHandler handler,
                                                           List<Integer> targetSlots,
                                                           ItemStack template) {
        if (targetSlots.isEmpty()) {
            return false;
        }

        int minimumTargetCount = Integer.MAX_VALUE;
        for (int targetSlot : targetSlots) {
            ItemStack target = handler.getSlot(targetSlot).getStack();
            minimumTargetCount = Math.min(minimumTargetCount, target.isEmpty() ? 0 : target.getCount());
        }

        List<Integer> lowestTargets = new ArrayList<>();
        for (int targetSlot : targetSlots) {
            ItemStack target = handler.getSlot(targetSlot).getStack();
            int count = target.isEmpty() ? 0 : target.getCount();
            if (count == minimumTargetCount) {
                lowestTargets.add(targetSlot);
            }
        }
        if (countMatchingUnlockedItems(handler, template) < lowestTargets.size()) {
            return false;
        }

        for (int targetSlot : lowestTargets) {
            int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                    client.player.getInventory(), handler, template, 1);
            if (sourceSlot == -1
                    || !moveOneItemToGridSlot(client, handler, sourceSlot, targetSlot, template)) {
                return false;
            }
        }

        for (int targetSlot : lowestTargets) {
            ItemStack target = handler.getSlot(targetSlot).getStack();
            if (target.isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(target, template)
                    || target.getCount() != minimumTargetCount + 1) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughFullStacksForMissingPatternSlots(ScreenHandler handler,
                                                               ItemStack template) {
        int missingSlots = getMissingPatternSlots(handler, 0, template).size();
        if (missingSlots == 0) {
            return true;
        }

        int fullSourceStacks = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack source = handler.getSlot(handlerSlot).getStack();
            if (!source.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(source, template)
                    && source.getCount() >= template.getMaxCount()) {
                fullSourceStacks++;
            }
        }
        return fullSourceStacks >= missingSlots;
    }

    private boolean hasFillablePatternSlot(ScreenHandler handler, ItemStack template) {
        return !getFillablePatternSlots(handler, template).isEmpty();
    }

    private boolean hasMissingPatternSlot(ScreenHandler handler, ItemStack template) {
        return !getMissingPatternSlots(handler, 0, template).isEmpty();
    }

    private boolean hasEarlierMatchingPatternStack(int patternIndex, ItemStack template) {
        for (int i = 0; i < patternIndex; i++) {
            ItemStack earlier = lockedCraftingPattern.get(i);
            if (!earlier.isEmpty() && ItemStack.areItemsAndComponentsEqual(earlier, template)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getFillablePatternSlots(ScreenHandler handler,
                                                  ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            ItemStack existing = slot.getStack();
            if ((existing.isEmpty() || ItemStack.areItemsAndComponentsEqual(existing, template))
                    && existing.getCount() < slot.getMaxItemCount(template)
                    && slot.canInsert(template)) {
                slots.add(slotId);
            }
        }
        slots.sort(Comparator.comparingInt(slotId -> handler.getSlot(slotId).getStack().getCount()));
        return slots;
    }

    private List<Integer> getLowestFillablePatternSlots(ScreenHandler handler,
                                                         ItemStack template) {
        List<Integer> fillable = getFillablePatternSlots(handler, template);
        if (fillable.size() < 2) {
            return fillable;
        }
        int lowestCount = handler.getSlot(fillable.get(0)).getStack().getCount();
        fillable.removeIf(slotId -> handler.getSlot(slotId).getStack().getCount() != lowestCount);
        return fillable;
    }

    private boolean distributeIngredientStackAcrossPatternSlots(MinecraftClient client,
                                                                ScreenHandler handler,
                                                                int sourceSlot,
                                                                List<Integer> targetSlots,
                                                                ItemStack template,
                                                                boolean moveWholeStackToSingleSlot) {
        int sourceCount = usableIngredientCount(handler.getSlot(sourceSlot).getStack().getCount());
        if (sourceCount <= 0) {
            return false;
        }

        // Full-stack ACK refills use one source stack per recipe slot. Only the
        // final partial refill is allowed to distribute across duplicate slots;
        // that path verifies the remainder before sending QUICK_CRAFT.
        if (moveWholeStackToSingleSlot) {
            return moveIngredientStackToSinglePatternSlot(
                    client, handler, sourceSlot, targetSlots.get(0), template);
        }

        if (targetSlots.size() == 1) {
            return moveIngredientStackToSinglePatternSlot(
                    client, handler, sourceSlot, targetSlots.get(0), template);
        }

        int remainingCapacity = targetSlots.stream()
                .mapToInt(slotId -> handler.getSlot(slotId).getMaxItemCount(template)
                        - handler.getSlot(slotId).getStack().getCount())
                .min()
                .orElse(0);
        int itemsPerSlot = QuickCraftMouseCraftInventory.tailItemsPerSlot(
                sourceCount, targetSlots.size(), remainingCapacity);
        if (itemsPerSlot <= 0) {
            return false;
        }
        if (!pickupIngredientStack(client, handler, sourceSlot)) {
            return false;
        }
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        return distributeCursorStackToPatternSlots(
                client,
                handler,
                sourceSlot,
                targetSlots,
                template,
                itemsPerSlot
        );
    }

    private boolean moveIngredientStackToSinglePatternSlot(MinecraftClient client,
                                                            ScreenHandler handler,
                                                            int sourceSlot,
                                                            int targetSlot,
                                                            ItemStack template) {
        ItemStack before = handler.getSlot(targetSlot).getStack();
        int beforeCount = before.isEmpty() ? 0 : before.getCount();
        int sourceCount = usableIngredientCount(handler.getSlot(sourceSlot).getStack().getCount());
        int maxCount = handler.getSlot(targetSlot).getMaxItemCount(template);
        int firstAccepting = QuickCraftMouseCraftInventory.firstAcceptingGridSlot(handler, layout, template);
        if (!retainIngredientSamples() && QuickCraftMouseCraftInventory.canQuickMoveWholeStackToGridSlot(
                sourceCount, beforeCount, maxCount, firstAccepting, targetSlot)) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    sourceSlot,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            ItemStack afterQuickMove = handler.getSlot(targetSlot).getStack();
            boolean movedQuick = !afterQuickMove.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(afterQuickMove, template)
                    && afterQuickMove.getCount() > beforeCount;
            boolean cursorClear = returnCursorStack(client, handler, sourceSlot);
            if (movedQuick && cursorClear) {
                return true;
            }
            if (!cursorClear) {
                return false;
            }
        }

        if (!handler.getCursorStack().isEmpty()
                && !returnCursorStack(client, handler, sourceSlot)) {
            return false;
        }
        if (!pickupIngredientStack(client, handler, sourceSlot)) {
            return false;
        }
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                targetSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        ItemStack after = handler.getSlot(targetSlot).getStack();
        boolean moved = !after.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(after, template)
                && after.getCount() > beforeCount;
        return closeIngredientTransfer(client, handler, sourceSlot) && moved;
    }

    private int countMatchingItemsInSlots(ScreenHandler handler,
                                          List<Integer> slots,
                                          ItemStack template) {
        int total = 0;
        for (int slotId : slots) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean restockCraftingGridFromPattern(MinecraftClient client,
                                                   ScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getCursorStack().isEmpty() || lockedCraftingPattern.isEmpty()) {
            return false;
        }
        relocateMismatchedGridItems(client, handler);
        if (getManualPatternState(handler) == ManualPatternState.INVALID) {
            return false;
        }
        if (getManualPatternState(handler) == ManualPatternState.MISSING
                && !hasTotalItemsForMissingPatternSlots(handler)) {
            return rebalanceGridTail(client, handler);
        }
        boolean filled = fillFullStacksThenTail(client, handler, MAX_OUTPUT_THROW_BURST);
        return filled || (getManualPatternState(handler) == ManualPatternState.COMPLETE
                && handler.getSlot(OUTPUT_SLOT).hasStack());
    }

    private boolean refillWithSamples(MinecraftClient client, ScreenHandler handler) {
        int movedItems = 0;
        List<Integer> touchedTargets = new ArrayList<>();
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            while (hasFillablePatternSlot(handler, template)) {
                List<Integer> targets = getLowestFillablePatternSlots(handler, template);
                if (targets.isEmpty()) {
                    break;
                }

                int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                        client.player.getInventory(), handler, template, 1);
                if (sourceSlot == -1) {
                    break;
                }

                int maximumServerPickup = QuickCraftMouseCraftInventory
                        .maximumRetainedHalfPickup(template.getMaxCount());
                int targetSlot = -1;
                for (int candidate : targets) {
                    if (touchedTargets.contains(candidate)) {
                        continue;
                    }
                    Slot target = handler.getSlot(candidate);
                    int targetCapacity = target.getMaxItemCount(template)
                            - target.getStack().getCount();
                    if (targetCapacity >= maximumServerPickup) {
                        targetSlot = candidate;
                        break;
                    }
                }
                if (targetSlot == -1) {
                    break;
                }

                int targetCountBefore = handler.getSlot(targetSlot).getStack().getCount();
                if (!pickupIngredientStack(client, handler, sourceSlot)) {
                    return movedItems > 0;
                }

                int cursorCount = handler.getCursorStack().getCount();
                client.interactionManager.clickSlot(
                        handler.syncId, targetSlot, 0, SlotActionType.PICKUP, client.player);
                ItemStack targetAfter = handler.getSlot(targetSlot).getStack();
                boolean moved = handler.getCursorStack().isEmpty()
                        && !targetAfter.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(targetAfter, template)
                        && targetAfter.getCount() == targetCountBefore + cursorCount;
                if (!moved) {
                    return movedItems > 0;
                }
                touchedTargets.add(targetSlot);
                movedItems += cursorCount;
            }
        }
        return movedItems > 0;
    }

    private boolean hasSampleRefillCapacity(ScreenHandler handler) {
        for (int patternIndex = 0; patternIndex < lockedCraftingPattern.size(); patternIndex++) {
            ItemStack template = lockedCraftingPattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }
            int missingSlots = getMissingPatternSlots(handler, 0, template).size();
            if (missingSlots == 0) {
                continue;
            }

            int usableItems = 0;
            for (int inventoryIndex = 0;
                 inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
                 inventoryIndex++) {
                int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
                if (slotId < 0 || QuickContainerLock.isLockedSlot(handler, slotId)) {
                    continue;
                }
                ItemStack source = handler.getSlot(slotId).getStack();
                if (!source.isEmpty() && ItemStack.areItemsAndComponentsEqual(source, template)) {
                    usableItems += Math.max(0, source.getCount() - 1);
                }
            }
            if (usableItems < missingSlots) {
                return false;
            }
        }
        return true;
    }

    private boolean retainIngredientSamples() {
        return QuickCraftConfigs.isRetainOneCraftIngredientEnabled();
    }

    private int usableIngredientCount(int count) {
        return QuickCraftMouseCraftInventory.usableIngredientCount(count, retainIngredientSamples());
    }

    private boolean pickupIngredientStack(MinecraftClient client, ScreenHandler handler, int sourceSlot) {
        ItemStack source = handler.getSlot(sourceSlot).getStack();
        if (!handler.getCursorStack().isEmpty() || usableIngredientCount(source.getCount()) == 0) {
            return false;
        }
        boolean retain = retainIngredientSamples();
        int beforeCount = source.getCount();
        client.interactionManager.clickSlot(handler.syncId, sourceSlot,
                QuickCraftMouseCraftInventory.ingredientPickupButton(retain), SlotActionType.PICKUP, client.player);
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }
        if (retain) {
            ItemStack sample = handler.getSlot(sourceSlot).getStack();
            if (sample.isEmpty() || !ItemStack.areItemsAndComponentsEqual(sample, handler.getCursorStack())) {
                returnCursorStack(client, handler, sourceSlot);
                return false;
            }
        }
        return !handler.getCursorStack().isEmpty();
    }

    private boolean moveOneItemToGridSlot(MinecraftClient client,
                                          ScreenHandler handler,
                                          int sourceSlot,
                                          int gridSlot,
                                          ItemStack template) {
        if (!pickupIngredientStack(client, handler, sourceSlot)) {
            return false;
        }
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                1,
                SlotActionType.PICKUP,
                client.player
        );

        boolean placed = handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
        boolean cursorReturned = retainIngredientSamples()
                ? closeRetainedIngredientTransfer(client, handler, sourceSlot)
                : closeIngredientTransfer(client, handler, sourceSlot);
        return placed && cursorReturned;
    }

    private boolean distributeCursorStackToPatternSlots(MinecraftClient client,
                                                         ScreenHandler handler,
                                                         int sourceSlot,
                                                         List<Integer> targetSlots,
                                                         ItemStack template,
                                                         int expectedIncreasePerSlot) {
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        int[] targetCountsBefore = new int[targetSlots.size()];
        for (int i = 0; i < targetSlots.size(); i++) {
            ItemStack target = handler.getSlot(targetSlots.get(i)).getStack();
            targetCountsBefore[i] = target.isEmpty() ? 0 : target.getCount();
        }

        int cursorCountBeforeDrag = handler.getCursorStack().getCount();

        // 1.21.3 ScreenHandler QUICK_CRAFT(mode=0) 会按目标数向下均分、逐槽按容量截断，
        // 未放下的余料保留在光标，因此一次拖拽即可替代逐物品右键补料。
        client.interactionManager.clickSlot(
                handler.syncId,
                -999,
                ScreenHandler.packQuickCraftData(0, 0),
                SlotActionType.QUICK_CRAFT,
                client.player
        );
        for (int targetSlot : targetSlots) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    targetSlot,
                    ScreenHandler.packQuickCraftData(1, 0),
                    SlotActionType.QUICK_CRAFT,
                    client.player
            );
        }
        client.interactionManager.clickSlot(
                handler.syncId,
                -999,
                ScreenHandler.packQuickCraftData(2, 0),
                SlotActionType.QUICK_CRAFT,
                client.player
        );

        boolean cursorReturned = retainIngredientSamples()
                ? closeRetainedIngredientTransfer(client, handler, sourceSlot)
                : closeIngredientTransfer(client, handler, sourceSlot);
        for (int i = 0; i < targetSlots.size(); i++) {
            int targetSlot = targetSlots.get(i);
            ItemStack placed = handler.getSlot(targetSlot).getStack();
            if (placed.isEmpty() || !ItemStack.areItemsAndComponentsEqual(placed, template)) {
                return false;
            }
            int expectedCount = targetCountsBefore[i] + expectedIncreasePerSlot;
            if (placed.getCount() != expectedCount) {
                return false;
            }
        }
        int returned = cursorCountBeforeDrag - expectedIncreasePerSlot * targetSlots.size();
        return cursorReturned;
    }

    private boolean closeIngredientTransfer(MinecraftClient client,
                                            ScreenHandler handler,
                                            int sourceSlot) {
        // 来源槽可能在客户端快照后捡入地面物品。即使本地预测光标已空，也必须固定点回
        // 原来源槽闭合序列，避免服务端按新数量取料后把差额留在权威光标上。
        client.interactionManager.clickSlot(
                handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
    }

    private boolean closeRetainedIngredientTransfer(MinecraftClient client,
                                                    ScreenHandler handler,
                                                    int sourceSlot) {
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }
        return true;
    }

    private List<Integer> getMissingPatternSlots(ScreenHandler handler,
                                                 int startPatternIndex,
                                                 ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (existing.isEmpty()) {
                slots.add(layout.gridSlotId(i));
            }
        }
        return slots;
    }

    private boolean returnCursorStack(MinecraftClient client,
                                      ScreenHandler handler,
                                      int preferredSlot) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        if (canAcceptStack(handler.getSlot(preferredSlot).getStack(), handler.getCursorStack())) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    preferredSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(handler, handler.getCursorStack());
        if (returnSlot == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                returnSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        return QuickCraftMouseCraftInventory.returnCursorToUnlockedInventory(client, handler, layout);
    }

    private boolean canAcceptStack(ItemStack targetStack, ItemStack cursorStack) {
        return targetStack.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(targetStack, cursorStack)
                && targetStack.getCount() + cursorStack.getCount() <= targetStack.getMaxCount());
    }

    private boolean hasItemsForMissingPatternSlots(ScreenHandler handler) {
        if (!planGridTailBalance(handler).isEmpty()) {
            return true;
        }
        if (retainIngredientSamples()) {
            return hasSampleRefillCapacity(handler);
        }
        List<ItemStack> availableStacks = new ArrayList<>();
        for (int invIndex = 0; invIndex < 36; invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack handlerStack = handler.getSlot(handlerSlot).getStack();
            boolean locked = QuickContainerLock.isLockedSlot(handler, handlerSlot);
            if (locked) {
                continue;
            }
            if (!handlerStack.isEmpty()) {
                ItemStack available = handlerStack.copy();
                available.setCount(usableIngredientCount(handlerStack.getCount()));
                availableStacks.add(available);
            }
        }

        int missingSlots = 0;
        int matchedMissingSlots = 0;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (!existing.isEmpty()) {
                if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                    return false;
                }
                continue;
            }

            missingSlots++;

            int availableIndex = findMatchingStackIndex(availableStacks, template);
            if (availableIndex == -1) {
                continue;
            }
            matchedMissingSlots++;
            // This availability check also permits the final tail to be split
            // across duplicate slots; the refill method selects the safe
            // quick-craft distribution path when whole source stacks are fewer.
            availableStacks.get(availableIndex).decrement(1);
        }

        boolean complete = missingSlots == 0;
        // A partial match is still unsafe to start; the caller only reaches the
        // refill path when every missing slot has at least one available item.
        return complete || matchedMissingSlots == missingSlots;
    }

    private int relocateMismatchedGridItems(MinecraftClient client, ScreenHandler handler) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || !handler.getCursorStack().isEmpty()
                || lockedCraftingPattern.isEmpty()) {
            return 0;
        }

        int relocated = 0;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            int gridSlot = layout.gridSlotId(i);
            ItemStack existing = handler.getSlot(gridSlot).getStack();
            if (existing.isEmpty()) {
                continue;
            }
            boolean matchesPattern = !template.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(existing, template);
            if (matchesPattern) {
                continue;
            }

            ItemStack moving = existing.copy();
            boolean isIngredient = matchesLockedPatternIngredient(moving)
                    || matchesCurrentRecipeIngredient(moving);
            if (!isIngredient) {
                rememberRecipeRemainder(moving);
                client.interactionManager.clickSlot(
                        handler.syncId,
                        gridSlot,
                        1,
                        SlotActionType.THROW,
                        client.player
                );
                if (handler.getSlot(gridSlot).getStack().isEmpty()) {
                    relocated++;
                }
                continue;
            }
            int patternDest = QuickCraftMouseCraftInventory.firstPatternSlotWithRoom(
                    handler, layout, lockedCraftingPattern, moving);
            if (patternDest >= 0 && patternDest != gridSlot) {
                client.interactionManager.clickSlot(
                        handler.syncId, gridSlot, 0, SlotActionType.PICKUP, client.player);
                client.interactionManager.clickSlot(
                        handler.syncId, patternDest, 0, SlotActionType.PICKUP, client.player);
                if (handler.getSlot(gridSlot).getStack().isEmpty()
                        && handler.getCursorStack().isEmpty()) {
                    relocated++;
                    continue;
                }
                if (!handler.getCursorStack().isEmpty()) {
                    returnCursorStack(client, handler, gridSlot);
                    if (!handler.getCursorStack().isEmpty()) {
                        break;
                    }
                }
                existing = handler.getSlot(gridSlot).getStack();
                if (existing.isEmpty()) {
                    relocated++;
                    continue;
                }
                moving = existing.copy();
            }
            if (QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(handler, layout, moving) < 0) {
                continue;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    gridSlot,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            existing = handler.getSlot(gridSlot).getStack();
            if (!existing.isEmpty()
                    && (template.isEmpty() || !ItemStack.areItemsAndComponentsEqual(existing, template))) {
                client.interactionManager.clickSlot(
                        handler.syncId,
                        gridSlot,
                        0,
                        SlotActionType.PICKUP,
                        client.player
                );
                int dest = QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                        handler, layout, handler.getCursorStack());
                if (dest >= 0 && !handler.getCursorStack().isEmpty()) {
                    client.interactionManager.clickSlot(
                            handler.syncId,
                            dest,
                            0,
                            SlotActionType.PICKUP,
                            client.player
                    );
                }
            }
            if (!handler.getCursorStack().isEmpty()) {
                int dest = QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                        handler, layout, handler.getCursorStack());
                if (dest >= 0) {
                    returnCursorStack(client, handler, dest);
                }
                break;
            }

            existing = handler.getSlot(gridSlot).getStack();
            boolean cleared = existing.isEmpty()
                    || (!template.isEmpty() && ItemStack.areItemsAndComponentsEqual(existing, template));
            if (cleared) {
                relocated++;
            }
        }
        return relocated;
    }

    private void rememberRecipeRemainder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (ItemStack known : knownRecipeRemainders) {
            if (ItemStack.areItemsAndComponentsEqual(known, stack)) {
                return;
            }
        }
        ItemStack template = stack.copy();
        template.setCount(1);
        knownRecipeRemainders.add(template);
    }

    private int dropKnownRecipeRemainders(MinecraftClient client, ScreenHandler handler) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || !handler.getCursorStack().isEmpty()
                || knownRecipeRemainders.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (int invIndex = 0; invIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE; invIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(invIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (stack.isEmpty() || !knownRecipeRemainders.stream()
                    .anyMatch(known -> ItemStack.areItemsAndComponentsEqual(known, stack))) {
                continue;
            }
            client.interactionManager.clickSlot(
                    handler.syncId, handlerSlot, 1, SlotActionType.THROW, client.player);
            dropped++;
        }
        return dropped;
    }

    private ManualPatternState getManualPatternState(ScreenHandler handler) {
        if (lockedCraftingPattern.isEmpty()) {
            return ManualPatternState.INVALID;
        }

        boolean missing = false;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (template.isEmpty() && existing.isEmpty()) {
                continue;
            }
            if (template.isEmpty()) {
                return ManualPatternState.INVALID;
            }
            if (existing.isEmpty()) {
                missing = true;
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                return ManualPatternState.INVALID;
            }
        }

        return missing ? ManualPatternState.MISSING : ManualPatternState.COMPLETE;
    }

    private int findMatchingStackIndex(List<ItemStack> stacks, ItemStack template) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPlayerInventoryHandlerSlot(PlayerInventory inventory,
                                                       ScreenHandler handler,
                                                       ItemStack template,
                                                       int minimumCount) {
        if (!retainIngredientSamples()) {
            int bestSlot = -1;
            int bestCount = -1;
            for (int inventoryIndex = 0; inventoryIndex < inventory.main.size(); inventoryIndex++) {
                ItemStack stack = inventory.main.get(inventoryIndex);
                if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
                    continue;
                }
                int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
                if (handlerSlot != -1
                        && !QuickContainerLock.isLockedSlot(handler, handlerSlot)
                        && handler.getSlot(handlerSlot).hasStack()
                        && handler.getSlot(handlerSlot).getStack().getCount() >= minimumCount) {
                    int stackCount = handler.getSlot(handlerSlot).getStack().getCount();
                    if (stackCount > bestCount) {
                        bestCount = stackCount;
                        bestSlot = handlerSlot;
                    }
                }
            }
            return bestSlot;
        }

        int bestSlot = -1;
        int bestCount = -1;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (stack.isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(stack, template)
                    || usableIngredientCount(stack.getCount()) < minimumCount) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlot = handlerSlot;
            }
        }
        return bestSlot;
    }

    private int findAcceptingPlayerInventoryHandlerSlot(ScreenHandler handler,
                                                        ItemStack cursorStack) {
        return QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                handler,
                layout,
                cursorStack
        );
    }

    private boolean shouldManualRestock(RecipeEntry<CraftingRecipe> recipe) {
        if (recipe == null) {
            return false;
        }
        try {
            return recipe.value().isIgnoredInRecipeBook();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasRecipeRemainder(ScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        if (handler == null || recipe == null) {
            return false;
        }
        try {
            for (ItemStack remainder : recipe.value().getRecipeRemainders(getCraftingRecipeInput(handler))) {
                if (!remainder.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private boolean isLockedResult(ItemStack stack) {
        return !stack.isEmpty()
                && !lockedResultTemplate.isEmpty()
                && stack.getCount() == lockedResultTemplate.getCount()
                && ItemStack.areItemsAndComponentsEqual(stack, lockedResultTemplate);
    }

    private void lockCurrentRecipe(MinecraftClient client,
                                   RecipeEntry<CraftingRecipe> recipe,
                                   ScreenHandler handler) {
        knownRecipeRemainders.clear();
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
        lockedResultTemplate = handler.getSlot(OUTPUT_SLOT).hasStack()
                ? handler.getSlot(OUTPUT_SLOT).getStack().copy()
                : ItemStack.EMPTY;
        lockedRecipeId = QuickCraftClientRecipeMatcher.findUniqueRecipeId(
                client,
                handler,
                lockedResultTemplate,
                layout.gridWidth(),
                layout.gridHeight()
        );
    }

    private boolean hasLockedCraftingPlan() {
        return !lockedCraftingPattern.isEmpty() && !lockedResultTemplate.isEmpty();
    }

    private List<ItemStack> snapshotCraftingGrid(ScreenHandler handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getStack().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    private boolean tryQuickMoveOutput(MinecraftClient client, ScreenHandler handler) {
        if (QuickCraftMouseCraftInventory.canAcceptUnlocked(
                handler, layout, handler.getSlot(OUTPUT_SLOT).getStack())
                && QuickCraftMouseCraftInventory.moveOutputToUnlockedInventory(
                client, handler, layout)) {
            return true;
        }
        return throwCraftingOutput(client, handler);
    }

    private boolean tryTakeOutputForRecipe(MinecraftClient client,
                                           ScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {

        boolean moved;
        if (shouldManualRestock(recipe)) {
            if (getManualPatternState(handler) != ManualPatternState.COMPLETE
                    || !isLockedResult(handler.getSlot(OUTPUT_SLOT).getStack())) {
                return false;
            }
            if (hasUnevenManualPatternStacks(handler)) {
                moved = tryTakeOneOutput(client, handler);
            } else {
                moved = tryQuickMoveOutput(client, handler);
            }
        } else {
            moved = tryQuickMoveOutput(client, handler);
        }

        return moved;
    }

    private boolean tryTakeOneOutput(MinecraftClient client, ScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(handler, before);
        if (returnSlot == -1) {
            return throwCraftingOutput(client, handler);
        }

        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        boolean pickedOutput = !handler.getCursorStack().isEmpty()
                && ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), before);
        if (pickedOutput) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    returnSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();
        return handler.getCursorStack().isEmpty()
                && (after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount);
    }

    private boolean hasUnevenManualPatternStacks(ScreenHandler handler) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            int count = getPatternSlotCount(handler, i, template);
            if (count <= 0) {
                continue;
            }

            for (int j = i + 1; j < lockedCraftingPattern.size(); j++) {
                ItemStack otherTemplate = lockedCraftingPattern.get(j);
                if (otherTemplate.isEmpty() || !ItemStack.areItemsAndComponentsEqual(template, otherTemplate)) {
                    continue;
                }
                int otherCount = getPatternSlotCount(handler, j, otherTemplate);
                if (otherCount > 0 && otherCount != count) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getPatternSlotCount(ScreenHandler handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(layout.gridSlotId(patternIndex)).getStack();
        if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }

    private boolean matchesLockedPatternIngredient(ItemStack stack) {
        for (ItemStack template : lockedCraftingPattern) {
            if (!template.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCurrentRecipeIngredient(ItemStack stack) {
        if (stack == null || stack.isEmpty() || lockedCraftingPattern.isEmpty()) {
            return false;
        }
        // 1.21.2+ 展示配方取代 Ingredients，客户端无法反查原料集；
        // 用户点选配方后锁定格即原料的唯一来源，因此按已锁配方格判定原料归属。
        for (ItemStack template : lockedCraftingPattern) {
            if (!template.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private int countMatchingUnlockedItems(ScreenHandler handler, ItemStack template) {
        int total = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += usableIngredientCount(stack.getCount());
            }
        }
        return total;
    }

    private boolean throwCraftingOutput(MinecraftClient client, ScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                1,
                SlotActionType.THROW,
                client.player
        );
        return true;
    }

    private ItemStack getRecipeResultStack(MinecraftClient client, RecipeEntry<CraftingRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (client.world == null || client.player == null
                    || QuickCraftMouseCraftLayout.fromHandler(
                    client.player.currentScreenHandler) != layout) {
                return ItemStack.EMPTY;
            }
            DynamicRegistryManager registryManager = client.world.getRegistryManager();
            return recipe.value().craft(
                    getCraftingRecipeInput(client.player.currentScreenHandler),
                    registryManager
            ).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeEntry<CraftingRecipe> getCurrentCraftingRecipe(MinecraftClient client, ScreenHandler handler) {
        if (client.world == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return null;
        }

        return tryFindCurrentRecipe(client.world, handler);
    }

    private RecipeEntry<CraftingRecipe> tryFindCurrentRecipe(World world, ScreenHandler handler) {
        // 1.21.2+ display 时代客户端不再含原始 RecipeManager.getFirstMatch；
        // 从本机合成格无法反查 RecipeEntry<CraftingRecipe>。锁定以输出槽命中为准（display 身份在 carrier 阶段承接，
        // 此处返回空作为让锁定走 pattern+output 后备），以免误把猜测产物当成权威结果。
        return null;
    }

    private CraftingRecipeInput getCraftingRecipeInput(ScreenHandler handler) {
        List<ItemStack> inputStacks = new ArrayList<>();
        for (int i = 0; i < layout.gridSize(); i++) {
            inputStacks.add(handler.getSlot(layout.gridSlotId(i)).getStack().copy());
        }
        return CraftingRecipeInput.create(layout.gridWidth(), layout.gridHeight(), inputStacks);
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        return layout.handlerSlotForInventoryIndex(invIndex);
    }

    private void handleHotkeys(MinecraftClient client, ScreenHandler handler) {

        boolean vDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }
        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private void trackHotkeyState() {
        lastVDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        lastAltCDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    private void trackHotkeyStateWhileBlocked() {
        lastVDown = keepHotkeyLatchedWhileBlocked(
                lastVDown, QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey()));
        lastAltCDown = keepHotkeyLatchedWhileBlocked(
                lastAltCDown, QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey()));
    }

    static boolean keepHotkeyLatchedWhileBlocked(boolean previouslyDown,
                                                  boolean currentlyDown) {
        return previouslyDown && currentlyDown;
    }

    private boolean startRapidCraft(MinecraftClient client,
                                    ScreenHandler handler,
                                    boolean fromButton) {
        mouseCraftAckLegacyFallback = false;
        mouseCraftAckManualRestockFallback = false;
        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasStack()) {
            lockCurrentRecipe(client, currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }

        rapidCraftingActive = true;
        rapidCraftStartedByButton = fromButton;
        clearRecipeGhosts.run();
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        manualGridSyncWaitTicks = 0;
        tryStartMouseCraftAck(client, handler);

        sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.started"));
        return true;
    }

    private boolean isCraftingContextValid(MinecraftClient client) {
        return client != null && client.player != null && client.world != null
                && QuickCraftMouseCraftLayout.fromScreen(client.currentScreen) == layout
                && QuickCraftMouseCraftLayout.fromHandler(client.player.currentScreenHandler) == layout;
    }

    private int countMatchingItems(PlayerInventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client != null && client.player != null && message != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void stopRapidCraft(MinecraftClient client, Text message) {
        if (mouseCraftAckExecutor.isActive()) {
            mouseCraftAckExecutor.requestStop(client, message);
            return;
        }
        finishRapidCraft(client, message);
    }

    private void finishRapidCraft(MinecraftClient client, Text message) {
        if (QuickCraftConfigs.isDropCraftResultsOnStopEnabled()
                && client != null && client.player != null
                && client.player.currentScreenHandler != null) {
            QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                    client, client.player.currentScreenHandler, layout,
                    lockedResultTemplate, "连续合成结束丢出背包产物");
        }
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        manualGridSyncWaitTicks = 0;
        mouseCraftAckLegacyFallback = false;
        mouseCraftAckManualRestockFallback = false;
        clearRecipeGhosts.run();
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        boolean hadCraftState = mouseCraftAckExecutor.isActive()
                || rapidCraftingActive || lockedRecipe != null || lockedRecipeId != null
                || !lockedCraftingPattern.isEmpty() || !lockedResultTemplate.isEmpty();
        mouseCraftAckExecutor.cancel("配方书合成状态重置：" + layout.name());
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        manualGridSyncWaitTicks = 0;
        mouseCraftAckLegacyFallback = false;
        mouseCraftAckManualRestockFallback = false;
        lockedRecipe = null;
        lockedRecipeId = null;
        lockedCraftingPattern.clear();
        lockedResultTemplate = ItemStack.EMPTY;
        knownRecipeRemainders.clear();
        if (hadCraftState) {
            clearRecipeGhosts.run();
        }
        lastVDown = false;
        lastAltCDown = false;
    }

    private boolean isCraftButtonRapidModeHeld(MinecraftClient client) {
        return QuickCraftKeyBindings.isAltDown()
                && QuickCraftKeyBindings.isLeftMouseButtonDown(client);
    }

    private boolean tryStartMouseCraftAck(MinecraftClient client, ScreenHandler handler) {
        if (!rapidCraftingActive || !hasLockedCraftingPlan()) {
            return false;
        }
        if (mouseCraftAckExecutor.isActive()) {
            return mouseCraftAckExecutor.owns(handler);
        }
        if (lockedRecipeId == null) {
            return false;
        }
        boolean started = mouseCraftAckExecutor.start(
                client,
                handler,
                lockedRecipeId,
                lockedResultTemplate,
                lockedCraftingPattern,
                () -> rapidCraftingActive && isRapidCraftInputHeld(client),
                (message, allowTailDrop) -> finishMouseCraftAck(
                        client, message, allowTailDrop)
        );
        return started;
    }

    private boolean isRapidCraftInputHeld(MinecraftClient client) {
        return rapidCraftStartedByButton
                ? isCraftButtonRapidModeHeld(client)
                : QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    private int finishMouseCraftAck(MinecraftClient client,
                                    Text message,
                                    boolean allowTailDrop) {
        finishRapidCraft(client, message);
        return 0;
    }

    private void fallbackMouseCraftAck(MinecraftClient client,
                                       ScreenHandler screenHandler,
                                       NetworkRecipeId recipeId) {
        mouseCraftAckLegacyFallback = true;
        mouseCraftAckManualRestockFallback = true;
        if (screenHandler == null
                || QuickCraftMouseCraftLayout.fromHandler(screenHandler) != layout
                || !isCraftingContextValid(client)) {
            rapidCraftingActive = false;
            return;
        }
        craftingResultWaitTicks = 0;
        manualGridSyncWaitTicks = 0;
        rapidCooldown = 0;
    }

}
