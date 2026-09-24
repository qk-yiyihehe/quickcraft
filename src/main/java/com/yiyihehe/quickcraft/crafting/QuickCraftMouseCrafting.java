package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/RecipeBookCraft");

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

    private RecipeHolder<CraftingRecipe> lockedRecipe = null;

    private RecipeDisplayId lockedRecipeId = null;

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
        return handleCraftButton(Minecraft.getInstance(), rapidCraft);
    }

    boolean isRapidCraftingActive() {
        return rapidCraftingActive;
    }

    void tick(Minecraft client) {
        if (!enabled.getAsBoolean()) {
            resetAll();
            trackHotkeyState();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        AbstractContainerMenu handler = client.player.containerMenu;

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
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }

        if (tryStartMouseCraftAck(client, handler)) {
            mouseCraftAckExecutor.tick(client);
            return;
        }

        if (rapidCraftingActive && hasLockedCraftingPlan()
                && !mouseCraftAckExecutor.isActive()) {
            LOGGER.warn("手动补货ACK未能接管持续喷射，停止旧Tick回退：界面={}，配方={}，光标={}，输出={}",
                    layout.name(),
                    lockedRecipeId == null ? "none" : lockedRecipeId,
                    handler.getCarried().isEmpty() ? "空" : handler.getCarried(),
                    handler.getSlot(OUTPUT_SLOT).hasItem()
                            ? handler.getSlot(OUTPUT_SLOT).getItem()
                            : "空");
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }
    }

    private void processRapidCraftTick(Minecraft client,
                                       AbstractContainerMenu handler,
                                       RecipeHolder<CraftingRecipe> recipe) {
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
                stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
            }
        }
    }

    private boolean runOneCraftSubLoop(Minecraft client,
                                       AbstractContainerMenu handler,
                                       RecipeHolder<CraftingRecipe> recipe) {

        if (client.player == null || client.gameMode == null || client.level == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasItem()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        }

        boolean manualRecipe = shouldManualRestock(recipe);
        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
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
                        || !isLockedResult(handler.getSlot(OUTPUT_SLOT).getItem())) {
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
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasItem()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            if (!manualRecipe || !rapidCraftingActive) {
                tryTakeOutputForRecipe(client, handler, recipe);
            }
            return true;
        }

        return false;
    }

    private boolean waitForCraftingResult(Minecraft client, AbstractContainerMenu handler) {
        if (craftingResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            craftingResultWaitTicks = 0;
            return false;
        }

        craftingResultWaitTicks--;
        if (craftingResultWaitTicks <= 0) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
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

    private void handleSingleCraft(Minecraft client, AbstractContainerMenu handler) {

        RecipeHolder<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasItem()) {
            lockCurrentRecipe(client, currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_recipe"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (success && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                    client, handler, layout, lockedResultTemplate, "单次合成结束丢出背包产物");
        }
        if (!success) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean handleCraftButton(Minecraft client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }
        if (mouseCraftAckExecutor.isActive()) {
            return true;
        }

        AbstractContainerMenu handler = (AbstractContainerMenu) client.player.containerMenu;
        if (QuickCraftMouseCraftAckExecutor.isCanceledBatchDraining(handler)) {
            return true;
        }
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean restockCraftingGrid(Minecraft client,
                                        AbstractContainerMenu handler,
                                        RecipeHolder<CraftingRecipe> recipe) {
        dropKnownRecipeRemainders(client, handler);
        relocateMismatchedGridItems(client, handler);
        if (rapidCraftingActive) {
            return fillManualPatternStacks(client, handler);
        }
        return restockCraftingGridFromPattern(client, handler);
    }

    private boolean fillManualPatternStacks(Minecraft client,
                                            AbstractContainerMenu handler) {
        return fillFullStacksThenTail(client, handler, MAX_OUTPUT_THROW_BURST);
    }

    private boolean refillAckSnapshot(Minecraft client,
                                      AbstractContainerMenu handler,
                                      int maxSourceStacksPerIngredient) {
        if (!rapidCraftingActive || !hasLockedCraftingPlan()
                || client.player == null
                || client.player.containerMenu != handler
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
            LOGGER.info("手动补货ACK没有完整一轮可用原料：界面={}，配方={}，格子={}，每格原料留样={}；不消耗样品，不发送半成品补料",
                    layout.name(), lockedRecipeId == null ? "none" : lockedRecipeId,
                    describeLiveGrid(handler), retainIngredientSamples());
            return relocated > 0;
        }

        // A duplicate ingredient only uses the 64-per-slot path when every
        // fillable occurrence has its own full source stack. The final 1..N
        // source stacks are distributed together, so 128 planks for three slab
        // slots becomes 42/42/42 instead of the invalid 64/64/empty pattern.
        boolean filled = fillFullStacksThenTail(
                client, handler, maxSourceStacksPerIngredient);
        ManualPatternState state = getManualPatternState(handler);
        boolean outputPresent = handler.getSlot(OUTPUT_SLOT).hasItem();
        LOGGER.info("手动补货ACK补料：界面={}，配方={}，挪走错位={}，补料={}，格状态={}，"
                        + "背包可补={}，空格={}，格子={}，光标={}，输出={}",
                layout.name(),
                lockedRecipeId == null ? "none" : lockedRecipeId,
                relocated,
                filled,
                state,
                hasItemsForMissingPatternSlots(handler),
                QuickCraftMouseCraftInventory.unlockedEmptySlots(handler, layout),
                describeLiveGrid(handler),
                handler.getCarried().isEmpty() ? "空" : handler.getCarried(),
                outputPresent ? handler.getSlot(OUTPUT_SLOT).getItem() : "空");
        return relocated > 0 || filled || (state == ManualPatternState.COMPLETE && outputPresent);
    }

    private boolean fillFullStacksThenTail(Minecraft client,
                                           AbstractContainerMenu handler,
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

    private boolean topUpCompletePattern(Minecraft client,
                                         AbstractContainerMenu handler,
                                         int maxSourceStacksPerIngredient) {
        var player = client.player;
        var gameMode = client.gameMode;
        if (player == null || gameMode == null
                || retainIngredientSamples()
                || !handler.getCarried().isEmpty()
                || getManualPatternState(handler) != ManualPatternState.COMPLETE) {
            return false;
        }

        int sourceBudget = Math.max(1, maxSourceStacksPerIngredient);
        int movedSources = 0;
        int movedIngredientTypes = 0;
        boolean movedAny = false;
        String before = describeLiveGrid(handler);
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
                    .mapToInt(slotId -> handler.getSlot(slotId).getMaxStackSize(template)
                            - handler.getSlot(slotId).getItem().getCount())
                    .sum();
            if (!QuickCraftMouseCraftInventory.canQuickTopUpCompleteGroup(
                    occurrences,
                    countMatchingUnlockedItems(handler, template),
                    remainingCapacity)) {
                continue;
            }

            int attempts = 0;
            boolean movedIngredient = false;
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
                if (!moved || afterCount <= beforeCount || !handler.getCarried().isEmpty()) {
                    break;
                }
                attempts++;
                movedSources++;
                movedAny = true;
                if (!movedIngredient) {
                    movedIngredient = true;
                    movedIngredientTypes++;
                }
            }
        }
        if (movedAny) {
            LOGGER.info("普通配方书完整图案补至最大：界面={}，原料种类={}，来源栈={}，补前={}，补后={}",
                    layout.name(), movedIngredientTypes, movedSources, before, describeLiveGrid(handler));
        }
        return movedAny;
    }

    private int patternIngredientOccurrences(ItemStack template) {
        int occurrences = 0;
        for (ItemStack patternStack : lockedCraftingPattern) {
            if (!patternStack.isEmpty()
                    && ItemStack.isSameItemSameComponents(patternStack, template)) {
                occurrences++;
            }
        }
        return occurrences;
    }

    private List<QuickCraftMouseCraftInventory.GridBalanceMove> planGridTailBalance(AbstractContainerMenu handler) {
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
                if (ItemStack.isSameItemSameComponents(lockedCraftingPattern.get(index), template)) {
                    slots.add(layout.gridSlotId(index));
                }
            }
            int[] counts = slots.stream().mapToInt(slot -> handler.getSlot(slot).getItem().getCount()).toArray();
            for (var move : QuickCraftMouseCraftInventory.planGridTailBalance(counts)) {
                moves.add(new QuickCraftMouseCraftInventory.GridBalanceMove(
                        slots.get(move.source()), slots.get(move.target()), move.count()));
            }
        }
        return moves;
    }

    private boolean rebalanceGridTail(Minecraft client, AbstractContainerMenu handler) {
        var moves = planGridTailBalance(handler);
        if (moves.isEmpty() || !handler.getCarried().isEmpty()) {
            return false;
        }
        String before = describeLiveGrid(handler);
        for (var move : moves) {
            int sourceCount = handler.getSlot(move.source()).getItem().getCount();
            int targetCount = handler.getSlot(move.target()).getItem().getCount();
            client.gameMode.handleContainerInput(handler.containerId, move.source(), 0, ContainerInput.PICKUP, client.player);
            if (handler.getCarried().getCount() != sourceCount
                    || handler.getSlot(move.source()).hasItem()) {
                LOGGER.warn("工作台尾料取料未确认：界面={}，来源={}，光标={}；停止本批后续点击",
                        layout.name(), move.source(), handler.getCarried());
                return true;
            }
            for (int placed = 0; placed < move.count(); placed++) {
                client.gameMode.handleContainerInput(handler.containerId, move.target(), 1, ContainerInput.PICKUP, client.player);
            }
            if (!handler.getCarried().isEmpty()) {
                client.gameMode.handleContainerInput(handler.containerId, move.source(), 0, ContainerInput.PICKUP, client.player);
            }
            if (!handler.getCarried().isEmpty()
                    || handler.getSlot(move.source()).getItem().getCount() != sourceCount - move.count()
                    || handler.getSlot(move.target()).getItem().getCount() != targetCount + move.count()) {
                LOGGER.warn("工作台尾料均分未确认：界面={}，来源={}，目标={}，移动={}，光标={}；交给ACK确认",
                        layout.name(), move.source(), move.target(), move.count(), handler.getCarried());
                return true;
            }
        }
        LOGGER.info("工作台尾料均分：界面={}，移动计划={}，均分前={}，均分后={}，背包原料留样不动=true；等待ACK后继续合成",
                layout.name(), moves, before, describeLiveGrid(handler));
        return true;
    }

    private boolean hasTotalItemsForMissingPatternSlots(AbstractContainerMenu handler) {
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
                        || !ItemStack.isSameItemSameComponents(sameTemplate, template)) {
                    continue;
                }
                if (!handler.getSlot(layout.gridSlotId(i)).hasItem()) {
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
                ItemStack source = handler.getSlot(handlerSlot).getItem();
                if (!source.isEmpty()
                        && ItemStack.isSameItemSameComponents(source, template)) {
                    available += source.getCount();
                }
            }
            if (available < missing) {
                return false;
            }
        }
        return true;
    }

    private boolean fillManualPatternStacks(Minecraft client,
                                            AbstractContainerMenu handler,
                                            int maxSourceStacksPerIngredient,
                                            boolean moveWholeStackToSingleSlot) {
        return fillManualPatternStacks(
                client, handler, maxSourceStacksPerIngredient,
                moveWholeStackToSingleSlot, false);
    }

    private boolean fillManualPatternStacks(Minecraft client,
                                            AbstractContainerMenu handler,
                                            int maxSourceStacksPerIngredient,
                                            boolean moveWholeStackToSingleSlot,
                                            boolean fullStacksOnly) {
        if (client.player == null || client.gameMode == null
                || !handler.getCarried().isEmpty()) {
            return false;
        }
        if (getManualPatternState(handler) == ManualPatternState.INVALID) {
            LOGGER.debug("手动补货跳过：合成格仍有无法匹配快照的物品，界面={}", layout.name());
            return false;
        }

        if (retainIngredientSamples()) {
            return refillWithSamples(client, handler);
        }
        int sourceStackBudget = Math.max(1, maxSourceStacksPerIngredient);
        long refillStartedAtNanos = moveWholeStackToSingleSlot ? System.nanoTime() : 0L;
        int movedSourceStacks = 0;
        int movedIngredientTypes = 0;
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
            boolean movedIngredient = false;
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
                        ? usableIngredientCount(template.getMaxStackSize())
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
                movedSourceStacks++;

                int afterCount = countMatchingItemsInSlots(handler, targetSlots, template);
                if (afterCount <= beforeCount) {
                    break;
                }
                if (!movedIngredient) {
                    movedIngredient = true;
                    movedIngredientTypes++;
                }
                movedAny = true;
            }
        }

        if (moveWholeStackToSingleSlot && LOGGER.isDebugEnabled()) {
            LOGGER.debug("普通配方书快照整栈补料：界面={}，预算={}，原料种类={}，来源栈={}，"
                            + "已移动={}，耗时={} us",
                    layout.name(), sourceStackBudget, movedIngredientTypes, movedSourceStacks,
                    movedAny, (System.nanoTime() - refillStartedAtNanos) / 1_000L);
        }

        return movedAny;
    }

    private boolean distributeOneRoundFromMultipleSources(Minecraft client,
                                                           AbstractContainerMenu handler,
                                                           List<Integer> targetSlots,
                                                           ItemStack template) {
        if (targetSlots.isEmpty()) {
            return false;
        }

        int minimumTargetCount = Integer.MAX_VALUE;
        for (int targetSlot : targetSlots) {
            ItemStack target = handler.getSlot(targetSlot).getItem();
            minimumTargetCount = Math.min(minimumTargetCount, target.isEmpty() ? 0 : target.getCount());
        }

        List<Integer> lowestTargets = new ArrayList<>();
        for (int targetSlot : targetSlots) {
            ItemStack target = handler.getSlot(targetSlot).getItem();
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
            ItemStack target = handler.getSlot(targetSlot).getItem();
            if (target.isEmpty()
                    || !ItemStack.isSameItemSameComponents(target, template)
                    || target.getCount() != minimumTargetCount + 1) {
                return false;
            }
        }
        LOGGER.info("普通配方书分散尾料补齐一轮：界面={}，目标槽={}，每槽={}，配方={}",
                layout.name(), lowestTargets, minimumTargetCount + 1,
                template.getHoverName().getString());
        return true;
    }

    private boolean hasEnoughFullStacksForMissingPatternSlots(AbstractContainerMenu handler,
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
            ItemStack source = handler.getSlot(handlerSlot).getItem();
            if (!source.isEmpty()
                    && ItemStack.isSameItemSameComponents(source, template)
                    && source.getCount() >= template.getMaxStackSize()) {
                fullSourceStacks++;
            }
        }
        return fullSourceStacks >= missingSlots;
    }

    private boolean hasFillablePatternSlot(AbstractContainerMenu handler, ItemStack template) {
        return !getFillablePatternSlots(handler, template).isEmpty();
    }

    private boolean hasMissingPatternSlot(AbstractContainerMenu handler, ItemStack template) {
        return !getMissingPatternSlots(handler, 0, template).isEmpty();
    }

    private boolean hasEarlierMatchingPatternStack(int patternIndex, ItemStack template) {
        for (int i = 0; i < patternIndex; i++) {
            ItemStack earlier = lockedCraftingPattern.get(i);
            if (!earlier.isEmpty() && ItemStack.isSameItemSameComponents(earlier, template)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getFillablePatternSlots(AbstractContainerMenu handler,
                                                  ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.isSameItemSameComponents(patternStack, template)) {
                continue;
            }

            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            ItemStack existing = slot.getItem();
            if ((existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, template))
                    && existing.getCount() < slot.getMaxStackSize(template)
                    && slot.mayPlace(template)) {
                slots.add(slotId);
            }
        }
        slots.sort(Comparator.comparingInt(slotId -> handler.getSlot(slotId).getItem().getCount()));
        return slots;
    }

    private List<Integer> getLowestFillablePatternSlots(AbstractContainerMenu handler,
                                                         ItemStack template) {
        List<Integer> fillable = getFillablePatternSlots(handler, template);
        if (fillable.size() < 2) {
            return fillable;
        }
        int lowestCount = handler.getSlot(fillable.get(0)).getItem().getCount();
        fillable.removeIf(slotId -> handler.getSlot(slotId).getItem().getCount() != lowestCount);
        return fillable;
    }

    private boolean distributeIngredientStackAcrossPatternSlots(Minecraft client,
                                                                AbstractContainerMenu handler,
                                                                int sourceSlot,
                                                                List<Integer> targetSlots,
                                                                ItemStack template,
                                                                boolean moveWholeStackToSingleSlot) {
        int sourceCount = usableIngredientCount(handler.getSlot(sourceSlot).getItem().getCount());
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
                .mapToInt(slotId -> handler.getSlot(slotId).getMaxStackSize(template)
                        - handler.getSlot(slotId).getItem().getCount())
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
        if (handler.getCarried().isEmpty()) {
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

    private boolean moveIngredientStackToSinglePatternSlot(Minecraft client,
                                                            AbstractContainerMenu handler,
                                                            int sourceSlot,
                                                            int targetSlot,
                                                            ItemStack template) {
        ItemStack before = handler.getSlot(targetSlot).getItem();
        int beforeCount = before.isEmpty() ? 0 : before.getCount();
        int sourceCount = usableIngredientCount(handler.getSlot(sourceSlot).getItem().getCount());
        int maxCount = handler.getSlot(targetSlot).getMaxStackSize(template);
        int firstAccepting = QuickCraftMouseCraftInventory.firstAcceptingGridSlot(handler, layout, template);
        boolean remainingFits = QuickCraftMouseCraftInventory.wholeStackFitsInSlot(
                sourceCount, beforeCount, maxCount);
        if (!retainIngredientSamples() && QuickCraftMouseCraftInventory.canQuickMoveWholeStackToGridSlot(
                sourceCount, beforeCount, maxCount, firstAccepting, targetSlot)) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    sourceSlot,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
            ItemStack afterQuickMove = handler.getSlot(targetSlot).getItem();
            boolean movedQuick = !afterQuickMove.isEmpty()
                    && ItemStack.isSameItemSameComponents(afterQuickMove, template)
                    && afterQuickMove.getCount() > beforeCount;
            boolean cursorClear = returnCursorStack(client, handler, sourceSlot);
            if (movedQuick && cursorClear) {
                return true;
            }
            if (!cursorClear) {
                return false;
            }
        } else if (!remainingFits) {
            LOGGER.info("整栈补货不用QUICK_MOVE：目标格装不下整组，避免摊到其他合成格。"
                            + "界面={}，格={}，已有={}，来源={}，上限={}",
                    layout.name(), targetSlot, beforeCount, sourceCount, maxCount);
        }

        if (!handler.getCarried().isEmpty()
                && !returnCursorStack(client, handler, sourceSlot)) {
            return false;
        }
        if (!pickupIngredientStack(client, handler, sourceSlot)) {
            return false;
        }
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                targetSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        ItemStack after = handler.getSlot(targetSlot).getItem();
        boolean moved = !after.isEmpty()
                && ItemStack.isSameItemSameComponents(after, template)
                && after.getCount() > beforeCount;
        return closeIngredientTransfer(client, handler, sourceSlot) && moved;
    }

    private int countMatchingItemsInSlots(AbstractContainerMenu handler,
                                          List<Integer> slots,
                                          ItemStack template) {
        int total = 0;
        for (int slotId : slots) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean restockCraftingGridFromPattern(Minecraft client,
                                                   AbstractContainerMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getCarried().isEmpty() || lockedCraftingPattern.isEmpty()) {
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
                && handler.getSlot(OUTPUT_SLOT).hasItem());
    }

    private boolean refillWithSamples(Minecraft client, AbstractContainerMenu handler) {
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
                    LOGGER.info("留样补料当前材料没有可用来源槽：界面={}，原料={}，权威可用={}，"
                                    + "目标槽={}；每个来源槽均保留一个，继续检查后续材料",
                            layout.name(), template.getHoverName().getString(),
                            countMatchingUnlockedItems(handler, template), targets);
                    break;
                }

                int maximumServerPickup = QuickCraftMouseCraftInventory
                        .maximumRetainedHalfPickup(template.getMaxStackSize());
                int targetSlot = -1;
                for (int candidate : targets) {
                    if (touchedTargets.contains(candidate)) {
                        continue;
                    }
                    Slot target = handler.getSlot(candidate);
                    int targetCapacity = target.getMaxStackSize(template)
                            - target.getItem().getCount();
                    if (targetCapacity >= maximumServerPickup) {
                        targetSlot = candidate;
                        break;
                    }
                }
                if (targetSlot == -1) {
                    LOGGER.info("留样单槽补料结束：界面={}，原料={}，最低目标槽={}，"
                                    + "本批已补目标={}，服务端最大半组={}；"
                                    + "同一目标格每批只补一次，尾量留到下一批",
                            layout.name(), template.getHoverName().getString(), targets,
                            touchedTargets, maximumServerPickup);
                    break;
                }

                int targetCountBefore = handler.getSlot(targetSlot).getItem().getCount();
                if (!pickupIngredientStack(client, handler, sourceSlot)) {
                    return movedItems > 0;
                }

                int cursorCount = handler.getCarried().getCount();
                client.gameMode.handleContainerInput(
                        handler.containerId, targetSlot, 0, ContainerInput.PICKUP, client.player);
                ItemStack targetAfter = handler.getSlot(targetSlot).getItem();
                boolean moved = handler.getCarried().isEmpty()
                        && !targetAfter.isEmpty()
                        && ItemStack.isSameItemSameComponents(targetAfter, template)
                        && targetAfter.getCount() == targetCountBefore + cursorCount;
                if (!moved) {
                    LOGGER.warn("留样单槽补料未完成：界面={}，来源={}，目标={}，补前={}，"
                                    + "拿取={}，补后={}，光标={}；停止本批并交给ACK确认",
                            layout.name(), sourceSlot, targetSlot, targetCountBefore,
                            cursorCount, targetAfter, handler.getCarried());
                    return movedItems > 0;
                }
                touchedTargets.add(targetSlot);
                movedItems += cursorCount;
                LOGGER.info("留样单槽安全补料：界面={}，来源槽={}，目标槽={}，原料={}，"
                                + "补前={}，增加={}，补后={}，权威最大半组={}，"
                                + "不拖拽=true，不点回来源=true",
                        layout.name(), sourceSlot, targetSlot,
                        template.getHoverName().getString(), targetCountBefore,
                        cursorCount, targetAfter.getCount(), maximumServerPickup);
            }
        }
        LOGGER.debug("留样单槽安全补料：界面={}，搬运原料={}，同类格尽量补满=true，"
                        + "来源槽按数量降序选择=true，每个来源槽至少保留一个=true，"
                        + "权威光标无需点回来源=true，每目标每批最多一次=true，目标槽={}",
                layout.name(), movedItems, touchedTargets);
        return movedItems > 0;
    }

    private boolean hasSampleRefillCapacity(AbstractContainerMenu handler) {
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
                ItemStack source = handler.getSlot(slotId).getItem();
                if (!source.isEmpty() && ItemStack.isSameItemSameComponents(source, template)) {
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

    private boolean pickupIngredientStack(Minecraft client, AbstractContainerMenu handler, int sourceSlot) {
        ItemStack source = handler.getSlot(sourceSlot).getItem();
        if (!handler.getCarried().isEmpty() || usableIngredientCount(source.getCount()) == 0) {
            return false;
        }
        boolean retain = retainIngredientSamples();
        int beforeCount = source.getCount();
        client.gameMode.handleContainerInput(handler.containerId, sourceSlot,
                QuickCraftMouseCraftInventory.ingredientPickupButton(retain), ContainerInput.PICKUP, client.player);
        if (handler.getCarried().isEmpty()) {
            return false;
        }
        if (retain) {
            ItemStack sample = handler.getSlot(sourceSlot).getItem();
            if (sample.isEmpty() || !ItemStack.isSameItemSameComponents(sample, handler.getCarried())) {
                LOGGER.warn("原料留样失败：界面={}，槽={}，原数量={}，槽内={}，光标={}；停止本次补料",
                        layout.name(), sourceSlot, beforeCount, sample, handler.getCarried());
                returnCursorStack(client, handler, sourceSlot);
                return false;
            }
            mouseCraftAckExecutor.recordRetainedSourcePrediction(
                    handler, sourceSlot, sample, beforeCount,
                    sample.getCount(), handler.getCarried().getCount());
            LOGGER.info("原料逐组留样：界面={}，槽={}，原数量={}，保留={}，批量搬运={}",
                    layout.name(), sourceSlot, beforeCount, sample.getCount(), handler.getCarried().getCount());
        }
        return !handler.getCarried().isEmpty();
    }

    private boolean moveOneItemToGridSlot(Minecraft client,
                                          AbstractContainerMenu handler,
                                          int sourceSlot,
                                          int gridSlot,
                                          ItemStack template) {
        if (!pickupIngredientStack(client, handler, sourceSlot)) {
            return false;
        }
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                gridSlot,
                1,
                ContainerInput.PICKUP,
                client.player
        );

        boolean placed = handler.getSlot(gridSlot).hasItem()
                && ItemStack.isSameItemSameComponents(handler.getSlot(gridSlot).getItem(), template);
        boolean cursorReturned = retainIngredientSamples()
                ? closeRetainedIngredientTransfer(client, handler, sourceSlot)
                : closeIngredientTransfer(client, handler, sourceSlot);
        return placed && cursorReturned;
    }

    private boolean distributeCursorStackToPatternSlots(Minecraft client,
                                                         AbstractContainerMenu handler,
                                                         int sourceSlot,
                                                         List<Integer> targetSlots,
                                                         ItemStack template,
                                                         int expectedIncreasePerSlot) {
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        int[] targetCountsBefore = new int[targetSlots.size()];
        for (int i = 0; i < targetSlots.size(); i++) {
            ItemStack target = handler.getSlot(targetSlots.get(i)).getItem();
            targetCountsBefore[i] = target.isEmpty() ? 0 : target.getCount();
        }

        int cursorCountBeforeDrag = handler.getCarried().getCount();

        // 1.21.3 AbstractContainerMenu QUICK_CRAFT(mode=0) 会按目标数向下均分、逐槽按容量截断，
        // 未放下的余料保留在光标，因此一次拖拽即可替代逐物品右键补料。
        client.gameMode.handleContainerInput(
                handler.containerId,
                -999,
                AbstractContainerMenu.getQuickcraftMask(0, 0),
                ContainerInput.QUICK_CRAFT,
                client.player
        );
        for (int targetSlot : targetSlots) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    targetSlot,
                    AbstractContainerMenu.getQuickcraftMask(1, 0),
                    ContainerInput.QUICK_CRAFT,
                    client.player
            );
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                -999,
                AbstractContainerMenu.getQuickcraftMask(2, 0),
                ContainerInput.QUICK_CRAFT,
                client.player
        );

        boolean cursorReturned = retainIngredientSamples()
                ? closeRetainedIngredientTransfer(client, handler, sourceSlot)
                : closeIngredientTransfer(client, handler, sourceSlot);
        for (int i = 0; i < targetSlots.size(); i++) {
            int targetSlot = targetSlots.get(i);
            ItemStack placed = handler.getSlot(targetSlot).getItem();
            if (placed.isEmpty() || !ItemStack.isSameItemSameComponents(placed, template)) {
                return false;
            }
            int expectedCount = targetCountsBefore[i] + expectedIncreasePerSlot;
            if (placed.getCount() != expectedCount) {
                LOGGER.warn("普通配方书尾料均分结果不一致，禁止继续发包：界面={}，槽={}，实际={}，期望={}，"
                                + "目标槽={}，来源余数={}，配方={}",
                        layout.name(), targetSlot, placed.getCount(), expectedCount,
                        targetSlots,
                        cursorCountBeforeDrag - expectedIncreasePerSlot * targetSlots.size(),
                        template.getHoverName().getString());
                return false;
            }
        }
        int returned = cursorCountBeforeDrag - expectedIncreasePerSlot * targetSlots.size();
        LOGGER.info("普通配方书尾料快速拖拽：界面={}，来源={}，目标槽={}，每槽增加={}，"
                        + "余数={}，来源闭合={}，基础槽位操作={}，配方={}",
                layout.name(), cursorCountBeforeDrag, targetSlots, expectedIncreasePerSlot,
                returned, cursorReturned, targetSlots.size() + 4,
                template.getHoverName().getString());
        return cursorReturned;
    }

    private boolean closeIngredientTransfer(Minecraft client,
                                            AbstractContainerMenu handler,
                                            int sourceSlot) {
        // 来源槽可能在客户端快照后捡入地面物品。即使本地预测光标已空，也必须固定点回
        // 原来源槽闭合序列，避免服务端按新数量取料后把差额留在权威光标上。
        client.gameMode.handleContainerInput(
                handler.containerId, sourceSlot, 0, ContainerInput.PICKUP, client.player);
        return handler.getCarried().isEmpty();
    }

    private boolean closeRetainedIngredientTransfer(Minecraft client,
                                                    AbstractContainerMenu handler,
                                                    int sourceSlot) {
        if (!handler.getCarried().isEmpty()) {
            LOGGER.warn("留样补料禁止点回来源槽：界面={}，来源槽={}，光标={}；"
                            + "客户端与服务端可能不同步，交给ACK处理",
                    layout.name(), sourceSlot, handler.getCarried());
            return false;
        }
        return true;
    }

    private List<Integer> getMissingPatternSlots(AbstractContainerMenu handler,
                                                 int startPatternIndex,
                                                 ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.isSameItemSameComponents(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (existing.isEmpty()) {
                slots.add(layout.gridSlotId(i));
            }
        }
        return slots;
    }

    private boolean returnCursorStack(Minecraft client,
                                      AbstractContainerMenu handler,
                                      int preferredSlot) {
        if (handler.getCarried().isEmpty()) {
            return true;
        }

        if (canAcceptStack(handler.getSlot(preferredSlot).getItem(), handler.getCarried())) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    preferredSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        }
        if (handler.getCarried().isEmpty()) {
            return true;
        }

        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(handler, handler.getCarried());
        if (returnSlot == -1) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                returnSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        return QuickCraftMouseCraftInventory.returnCursorToUnlockedInventory(client, handler, layout);
    }

    private boolean canAcceptStack(ItemStack targetStack, ItemStack cursorStack) {
        return targetStack.isEmpty()
                || (ItemStack.isSameItemSameComponents(targetStack, cursorStack)
                && targetStack.getCount() + cursorStack.getCount() <= targetStack.getMaxStackSize());
    }

    private boolean hasItemsForMissingPatternSlots(AbstractContainerMenu handler) {
        if (!planGridTailBalance(handler).isEmpty()) {
            return true;
        }
        if (retainIngredientSamples()) {
            return hasSampleRefillCapacity(handler);
        }
        List<ItemStack> availableStacks = new ArrayList<>();
        List<String> availableDescriptions = new ArrayList<>();
        List<String> excludedOrDesyncedDescriptions = new ArrayList<>();
        List<String> emptySlotDescriptions = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        Inventory playerInventory = client.player == null ? null : client.player.getInventory();
        for (int invIndex = 0; invIndex < 36; invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack handlerStack = handler.getSlot(handlerSlot).getItem();
            ItemStack inventoryStack = playerInventory != null && invIndex < playerInventory.getNonEquipmentItems().size()
                    ? playerInventory.getNonEquipmentItems().get(invIndex)
                    : ItemStack.EMPTY;
            boolean locked = QuickContainerLock.isLockedSlot(handler, handlerSlot);
            if (locked) {
                if (!handlerStack.isEmpty() || !inventoryStack.isEmpty()) {
                    excludedOrDesyncedDescriptions.add(describeInventoryCandidate(
                            handlerSlot, invIndex, handlerStack, inventoryStack, true));
                }
                continue;
            }
            if (handlerStack.isEmpty()) {
                emptySlotDescriptions.add(handlerSlot + "(inventory=" + invIndex
                        + ",player=" + describeStack(inventoryStack) + ",locked=false)");
            }
            if (!sameStackState(handlerStack, inventoryStack)) {
                excludedOrDesyncedDescriptions.add(describeInventoryCandidate(
                        handlerSlot, invIndex, handlerStack, inventoryStack, false));
            }
            if (!handlerStack.isEmpty()) {
                ItemStack available = handlerStack.copy();
                available.setCount(usableIngredientCount(handlerStack.getCount()));
                availableStacks.add(available);
                availableDescriptions.add(handlerSlot + ":" + handlerStack.getHoverName().getString()
                        + "x" + handlerStack.getCount() + "(可用=" + available.getCount() + ")");
            }
        }

        int missingSlots = 0;
        int matchedMissingSlots = 0;
        List<String> missingDescriptions = new ArrayList<>();
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(existing, template)) {
                    return false;
                }
                continue;
            }

            missingSlots++;

            int availableIndex = findMatchingStackIndex(availableStacks, template);
            if (availableIndex == -1) {
                missingDescriptions.add(i + ":" + template.getHoverName().getString());
                continue;
            }
            matchedMissingSlots++;
            // This availability check also permits the final tail to be split
            // across duplicate slots; the refill method selects the safe
            // quick-craft distribution path when whole source stacks are fewer.
            availableStacks.get(availableIndex).shrink(1);
        }

        boolean complete = missingSlots == 0;
        // A partial match is still unsafe to start; the caller only reaches the
        // refill path when every missing slot has at least one available item.
        boolean result = complete || matchedMissingSlots == missingSlots;
        if (!complete) {
            if (!result || !excludedOrDesyncedDescriptions.isEmpty()) {
                LOGGER.info("普通配方书材料扫描：界面={}，结果={}，缺格={}，可补缺格={}，"
                                + "未找到={}，背包材料={}，空槽={}，排除或不同步={}",
                        layout.name(), result, missingSlots, matchedMissingSlots,
                        missingDescriptions, availableDescriptions, emptySlotDescriptions,
                        excludedOrDesyncedDescriptions);
            } else {
                LOGGER.debug("普通配方书材料齐全：界面={}，缺格={}，背包材料={}",
                        layout.name(), missingSlots, availableDescriptions);
            }
        }
        return result;
    }

    private String describeInventoryCandidate(int handlerSlot,
                                              int inventoryIndex,
                                              ItemStack handlerStack,
                                              ItemStack inventoryStack,
                                              boolean locked) {
        return "handler=" + handlerSlot
                + "/inventory=" + inventoryIndex
                + "/screen=" + describeStack(handlerStack)
                + "/player=" + describeStack(inventoryStack)
                + "/locked=" + locked;
    }

    private boolean sameStackState(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private String describeStack(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? "空"
                : stack.getHoverName().getString() + "x" + stack.getCount();
    }

    private int relocateMismatchedGridItems(Minecraft client, AbstractContainerMenu handler) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null || !handler.getCarried().isEmpty()
                || lockedCraftingPattern.isEmpty()) {
            return 0;
        }

        int relocated = 0;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            int gridSlot = layout.gridSlotId(i);
            ItemStack existing = handler.getSlot(gridSlot).getItem();
            if (existing.isEmpty()) {
                continue;
            }
            boolean matchesPattern = !template.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, template);
            if (matchesPattern) {
                continue;
            }

            ItemStack moving = existing.copy();
            boolean isIngredient = matchesLockedPatternIngredient(moving)
                    || matchesCurrentRecipeIngredient(moving);
            if (!isIngredient) {
                rememberRecipeRemainder(moving);
                LOGGER.info("手动补货整组丢出合成格返还物：界面={}，格={}，物品={}",
                        layout.name(), gridSlot, moving);
                client.gameMode.handleContainerInput(
                        handler.containerId,
                        gridSlot,
                        1,
                        ContainerInput.THROW,
                        client.player
                );
                mouseCraftAckExecutor.recordRemainderThrow();
                if (handler.getSlot(gridSlot).getItem().isEmpty()) {
                    relocated++;
                }
                continue;
            }
            int patternDest = QuickCraftMouseCraftInventory.firstPatternSlotWithRoom(
                    handler, layout, lockedCraftingPattern, moving);
            if (patternDest >= 0 && patternDest != gridSlot) {
                client.gameMode.handleContainerInput(
                        handler.containerId, gridSlot, 0, ContainerInput.PICKUP, client.player);
                client.gameMode.handleContainerInput(
                        handler.containerId, patternDest, 0, ContainerInput.PICKUP, client.player);
                if (handler.getSlot(gridSlot).getItem().isEmpty()
                        && handler.getCarried().isEmpty()) {
                    relocated++;
                    LOGGER.info("手动补货把错位原料合并回配方格：界面={}，格={}，目标={}，物品={}",
                            layout.name(), gridSlot, patternDest, moving);
                    continue;
                }
                if (!handler.getCarried().isEmpty()) {
                    returnCursorStack(client, handler, gridSlot);
                    if (!handler.getCarried().isEmpty()) {
                        break;
                    }
                }
                existing = handler.getSlot(gridSlot).getItem();
                if (existing.isEmpty()) {
                    relocated++;
                    continue;
                }
                moving = existing.copy();
            }
            if (QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(handler, layout, moving) < 0) {
                LOGGER.warn("手动补货无法挪走合成格原料且不丢原料：界面={}，格={}，物品={}",
                        layout.name(), gridSlot, moving);
                continue;
            }

            client.gameMode.handleContainerInput(
                    handler.containerId,
                    gridSlot,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
            existing = handler.getSlot(gridSlot).getItem();
            if (!existing.isEmpty()
                    && (template.isEmpty() || !ItemStack.isSameItemSameComponents(existing, template))) {
                client.gameMode.handleContainerInput(
                        handler.containerId,
                        gridSlot,
                        0,
                        ContainerInput.PICKUP,
                        client.player
                );
                int dest = QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                        handler, layout, handler.getCarried());
                if (dest >= 0 && !handler.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(
                            handler.containerId,
                            dest,
                            0,
                            ContainerInput.PICKUP,
                            client.player
                    );
                }
            }
            if (!handler.getCarried().isEmpty()) {
                LOGGER.warn("手动补货挪走错位物品后光标非空：界面={}，格={}，光标={}",
                        layout.name(), gridSlot, handler.getCarried());
                int dest = QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                        handler, layout, handler.getCarried());
                if (dest >= 0) {
                    returnCursorStack(client, handler, dest);
                }
                break;
            }

            existing = handler.getSlot(gridSlot).getItem();
            boolean cleared = existing.isEmpty()
                    || (!template.isEmpty() && ItemStack.isSameItemSameComponents(existing, template));
            if (cleared) {
                relocated++;
                LOGGER.info("手动补货已挪走合成格错位物品：界面={}，格={}，原物品={}，是否原料={}",
                        layout.name(), gridSlot, moving, isIngredient);
            }
        }
        return relocated;
    }

    private void rememberRecipeRemainder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (ItemStack known : knownRecipeRemainders) {
            if (ItemStack.isSameItemSameComponents(known, stack)) {
                return;
            }
        }
        ItemStack template = stack.copy();
        template.setCount(1);
        knownRecipeRemainders.add(template);
    }

    private int dropKnownRecipeRemainders(Minecraft client, AbstractContainerMenu handler) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null || !handler.getCarried().isEmpty()
                || knownRecipeRemainders.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (int invIndex = 0; invIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE; invIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(invIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (stack.isEmpty() || !knownRecipeRemainders.stream()
                    .anyMatch(known -> ItemStack.isSameItemSameComponents(known, stack))) {
                continue;
            }
            ItemStack droppedStack = stack.copy();
            client.gameMode.handleContainerInput(
                    handler.containerId, handlerSlot, 1, ContainerInput.THROW, client.player);
            dropped++;
            mouseCraftAckExecutor.recordRemainderThrow();
            LOGGER.info("手动补货整组丢出背包返还物：界面={}，槽={}，物品={}",
                    layout.name(), handlerSlot, droppedStack);
        }
        return dropped;
    }

    private ManualPatternState getManualPatternState(AbstractContainerMenu handler) {
        if (lockedCraftingPattern.isEmpty()) {
            return ManualPatternState.INVALID;
        }

        boolean missing = false;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getItem();
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
            if (!ItemStack.isSameItemSameComponents(existing, template)) {
                return ManualPatternState.INVALID;
            }
        }

        return missing ? ManualPatternState.MISSING : ManualPatternState.COMPLETE;
    }

    private int findMatchingStackIndex(List<ItemStack> stacks, ItemStack template) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPlayerInventoryHandlerSlot(Inventory inventory,
                                                       AbstractContainerMenu handler,
                                                       ItemStack template,
                                                       int minimumCount) {
        if (!retainIngredientSamples()) {
            int bestSlot = -1;
            int bestCount = -1;
            for (int inventoryIndex = 0; inventoryIndex < inventory.getNonEquipmentItems().size(); inventoryIndex++) {
                ItemStack stack = inventory.getNonEquipmentItems().get(inventoryIndex);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                    continue;
                }
                int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
                if (handlerSlot != -1
                        && !QuickContainerLock.isLockedSlot(handler, handlerSlot)
                        && handler.getSlot(handlerSlot).hasItem()
                        && handler.getSlot(handlerSlot).getItem().getCount() >= minimumCount) {
                    int stackCount = handler.getSlot(handlerSlot).getItem().getCount();
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
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (stack.isEmpty()
                    || !ItemStack.isSameItemSameComponents(stack, template)
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

    private int findAcceptingPlayerInventoryHandlerSlot(AbstractContainerMenu handler,
                                                        ItemStack cursorStack) {
        return QuickCraftMouseCraftInventory.findAcceptingUnlockedSlot(
                handler,
                layout,
                cursorStack
        );
    }

    private boolean shouldManualRestock(RecipeHolder<CraftingRecipe> recipe) {
        if (recipe == null) {
            return false;
        }
        try {
            return recipe.value().isSpecial();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasRecipeRemainder(AbstractContainerMenu handler,
                                       RecipeHolder<CraftingRecipe> recipe) {
        if (handler == null || recipe == null) {
            return false;
        }
        try {
            for (ItemStack remainder : recipe.value().getRemainingItems(getCraftingRecipeInput(handler))) {
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
                && ItemStack.isSameItemSameComponents(stack, lockedResultTemplate);
    }

    private void lockCurrentRecipe(Minecraft client,
                                   RecipeHolder<CraftingRecipe> recipe,
                                   AbstractContainerMenu handler) {
        knownRecipeRemainders.clear();
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
        lockedResultTemplate = handler.getSlot(OUTPUT_SLOT).hasItem()
                ? handler.getSlot(OUTPUT_SLOT).getItem().copy()
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

    private String describeLiveGrid(AbstractContainerMenu handler) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < layout.gridSize(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (stack == null || stack.isEmpty()) {
                builder.append('-');
            } else {
                builder.append(stack.getHoverName().getString()).append('x').append(stack.getCount());
            }
        }
        return builder.toString();
    }

    private String describePattern(List<ItemStack> pattern) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < pattern.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            ItemStack stack = pattern.get(i);
            builder.append(stack == null || stack.isEmpty() ? '-' : stack.getHoverName().getString());
        }
        return builder.toString();
    }

    private List<ItemStack> snapshotCraftingGrid(AbstractContainerMenu handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getItem().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    private boolean tryQuickMoveOutput(Minecraft client, AbstractContainerMenu handler) {
        if (QuickCraftMouseCraftInventory.canAcceptUnlocked(
                handler, layout, handler.getSlot(OUTPUT_SLOT).getItem())
                && QuickCraftMouseCraftInventory.moveOutputToUnlockedInventory(
                client, handler, layout)) {
            return true;
        }
        return throwCraftingOutput(client, handler);
    }

    private boolean tryTakeOutputForRecipe(Minecraft client,
                                           AbstractContainerMenu handler,
                                           RecipeHolder<CraftingRecipe> recipe) {

        boolean moved;
        if (shouldManualRestock(recipe)) {
            if (getManualPatternState(handler) != ManualPatternState.COMPLETE
                    || !isLockedResult(handler.getSlot(OUTPUT_SLOT).getItem())) {
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

    private boolean tryTakeOneOutput(Minecraft client, AbstractContainerMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(handler, before);
        if (returnSlot == -1) {
            return throwCraftingOutput(client, handler);
        }

        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.gameMode.handleContainerInput(
                handler.containerId,
                OUTPUT_SLOT,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        boolean pickedOutput = !handler.getCarried().isEmpty()
                && ItemStack.isSameItemSameComponents(handler.getCarried(), before);
        if (pickedOutput) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    returnSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        }

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getItem();
        return handler.getCarried().isEmpty()
                && (after.isEmpty()
                || !ItemStack.isSameItemSameComponents(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount);
    }

    private boolean hasUnevenManualPatternStacks(AbstractContainerMenu handler) {
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
                if (otherTemplate.isEmpty() || !ItemStack.isSameItemSameComponents(template, otherTemplate)) {
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

    private int getPatternSlotCount(AbstractContainerMenu handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(layout.gridSlotId(patternIndex)).getItem();
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }

    private boolean matchesLockedPatternIngredient(ItemStack stack) {
        for (ItemStack template : lockedCraftingPattern) {
            if (!template.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
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
            if (!template.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private int countMatchingUnlockedItems(AbstractContainerMenu handler, ItemStack template) {
        int total = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += usableIngredientCount(stack.getCount());
            }
        }
        return total;
    }

    private boolean throwCraftingOutput(Minecraft client, AbstractContainerMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                OUTPUT_SLOT,
                1,
                ContainerInput.THROW,
                client.player
        );
        return true;
    }

    private ItemStack getRecipeResultStack(Minecraft client, RecipeHolder<CraftingRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (client.level == null || client.player == null
                    || QuickCraftMouseCraftLayout.fromHandler(
                    client.player.containerMenu) != layout) {
                return ItemStack.EMPTY;
            }
            return recipe.value().assemble(getCraftingRecipeInput(client.player.containerMenu)).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeHolder<CraftingRecipe> getCurrentCraftingRecipe(Minecraft client, AbstractContainerMenu handler) {
        if (client.level == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return null;
        }

        return tryFindCurrentRecipe(client.level, handler);
    }

    private RecipeHolder<CraftingRecipe> tryFindCurrentRecipe(Level world, AbstractContainerMenu handler) {
        // 1.21.2+ display 时代客户端不再含原始 RecipeManager.getFirstMatch；
        // 从本机合成格无法反查 RecipeHolder<CraftingRecipe>。锁定以输出槽命中为准（display 身份在 carrier 阶段承接，
        // 此处返回空作为让锁定走 pattern+output 后备），以免误把猜测产物当成权威结果。
        return null;
    }

    private CraftingInput getCraftingRecipeInput(AbstractContainerMenu handler) {
        List<ItemStack> inputStacks = new ArrayList<>();
        for (int i = 0; i < layout.gridSize(); i++) {
            inputStacks.add(handler.getSlot(layout.gridSlotId(i)).getItem().copy());
        }
        return CraftingInput.of(layout.gridWidth(), layout.gridHeight(), inputStacks);
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        return layout.handlerSlotForInventoryIndex(invIndex);
    }

    private void handleHotkeys(Minecraft client, AbstractContainerMenu handler) {

        boolean vDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }
        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
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

    private boolean startRapidCraft(Minecraft client,
                                    AbstractContainerMenu handler,
                                    boolean fromButton) {
        mouseCraftAckLegacyFallback = false;
        mouseCraftAckManualRestockFallback = false;
        RecipeHolder<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasItem()) {
            lockCurrentRecipe(client, currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_recipe"));
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

        sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.started"));
        return true;
    }

    private boolean isCraftingContextValid(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && QuickCraftMouseCraftLayout.fromScreen(client.gui.screen()) == layout
                && QuickCraftMouseCraftLayout.fromHandler(client.player.containerMenu) == layout;
    }

    private int countMatchingItems(Inventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client != null && client.player != null && message != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private void stopRapidCraft(Minecraft client, Component message) {
        if (mouseCraftAckExecutor.isActive()) {
            mouseCraftAckExecutor.requestStop(client, message);
            return;
        }
        finishRapidCraft(client, message);
    }

    private void finishRapidCraft(Minecraft client, Component message) {
        if (QuickCraftConfigs.isDropCraftResultsOnStopEnabled()
                && client != null && client.player != null
                && client.player.containerMenu != null) {
            QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                    client, client.player.containerMenu, layout,
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

    private boolean isCraftButtonRapidModeHeld(Minecraft client) {
        return QuickCraftKeyBindings.isAltDown()
                && QuickCraftKeyBindings.isLeftMouseButtonDown(client);
    }

    private boolean tryStartMouseCraftAck(Minecraft client, AbstractContainerMenu handler) {
        if (!rapidCraftingActive || !hasLockedCraftingPlan()) {
            return false;
        }
        if (mouseCraftAckExecutor.isActive()) {
            return mouseCraftAckExecutor.owns(handler);
        }
        if (lockedRecipeId == null) {
            LOGGER.warn("手动补货ACK无法启动：界面={}，配方为空", layout.name());
            return false;
        }
        LOGGER.info("手动补货ACK尝试启动：界面={}，配方={}，产物={}，containerId={}，revision={}，格子={}",
                layout.name(), lockedRecipeId, lockedResultTemplate,
                handler.containerId, handler.getStateId(), describePattern(lockedCraftingPattern));
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
        if (!started) {
            LOGGER.warn("手动补货ACK启动返回false：界面={}，光标={}，输出={}，可启动={}",
                    layout.name(),
                    handler.getCarried().isEmpty() ? "空" : handler.getCarried(),
                    handler.getSlot(OUTPUT_SLOT).hasItem()
                            ? handler.getSlot(OUTPUT_SLOT).getItem()
                            : "空",
                    mouseCraftAckExecutor.canStart(handler, lockedResultTemplate));
        }
        return started;
    }

    private boolean isRapidCraftInputHeld(Minecraft client) {
        return rapidCraftStartedByButton
                ? isCraftButtonRapidModeHeld(client)
                : QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    private int finishMouseCraftAck(Minecraft client,
                                    Component message,
                                    boolean allowTailDrop) {
        finishRapidCraft(client, message);
        return 0;
    }

    private void fallbackMouseCraftAck(Minecraft client,
                                       AbstractContainerMenu screenHandler,
                                       RecipeDisplayId recipeId) {
        mouseCraftAckLegacyFallback = true;
        mouseCraftAckManualRestockFallback = true;
        if (screenHandler == null
                || QuickCraftMouseCraftLayout.fromHandler(screenHandler) != layout
                || !isCraftingContextValid(client)) {
            rapidCraftingActive = false;
            return;
        }
        LOGGER.info("普通配方书切换快照整栈补料：界面={}，配方={}，背包可补一轮={}",
                layout.name(), recipeId, hasItemsForMissingPatternSlots(screenHandler));
        craftingResultWaitTicks = 0;
        manualGridSyncWaitTicks = 0;
        rapidCooldown = 0;
    }

}
