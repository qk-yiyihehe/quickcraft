package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;

final class QuickCrossBoxSortSession {
    private static final int SYNC_TIMEOUT_TICKS = 100;
    private static final int CLOSE_TIMEOUT_TICKS = 20;
    private List<Integer> stagingInventorySlots = List.of();
    private List<QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey>> transfers = List.of();
    private int emptiedBoxes;
    private int remainingBoxes;
    private int crossBoxMoves;
    private List<QuickCrossBoxSortPlanner.SwapCycle<QuickCrossBoxSortStackKey>> swapCycles = List.of();
    private List<QuickCrossBoxSortPlanner.ScheduledSwap<QuickCrossBoxSortStackKey>> scheduledSwaps = List.of();
    private List<Integer> swapHotbarSlots = List.of();
    private int swapStepIndex;
    private State afterSwapCloseState;
    private final boolean onePerBox;
    private final QuickCrossBoxSortContainer container;
    private int openingInventorySlot;
    private final int workspaceHotbarSlot;
    private final int borrowedDestinationSlot;
    private final QuickCrossBoxSortStackKey borrowedOriginalKey;
    private final int borrowedOriginalCount;
    private final boolean borrowedEligibleShulker;
    private boolean backgroundMode;
    private InventoryScreen backgroundInventory;
    private boolean closingOwnedBox;
    private boolean expectingHiddenContainerScreen;
    private Screen hiddenContainerScreen;
    private State state;
    private int age;
    private int deadline;
    private long waitRevision;
    private int waitContainerId;
    private int waitStateId;
    private int clickSentAt;
    private int openContainerId;
    private int transferIndex;
    private List<QuickCrossBoxSortEmptyMerges.Merge> emptyMerges;
    private int emptyMergeIndex;
    private QuickCrossBoxSortEmptyMerges.Merge emptyMerge;
    private int emptyMergeCount;
    private int emptyTargetBeforeCount;
    private Text abortMessage;
    private Batch batch;
    private int batchJobIndex;
    private int targetGroupIndex;
    private int targetTransferIndex;
    private int returnJobIndex;
    private BatchJob activeJob;
    private QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey> batchTransfer;
    private int batchTargetBeforeCount;

    private QuickCrossBoxSortSession(QuickCrossBoxSortContainer container, boolean onePerBox, int workspaceHotbarSlot, int borrowedDestinationSlot, QuickCrossBoxSortStackKey borrowedOriginalKey, int borrowedOriginalCount, boolean borrowedEligibleShulker) {
        this.state = State.BEGIN_OPERATION;
        this.openContainerId = Integer.MIN_VALUE;
        this.emptyMerges = List.of();
        this.container = container;
        this.onePerBox = onePerBox;
        this.workspaceHotbarSlot = workspaceHotbarSlot;
        this.borrowedDestinationSlot = borrowedDestinationSlot;
        this.borrowedOriginalKey = borrowedOriginalKey;
        this.borrowedOriginalCount = borrowedOriginalCount;
        this.borrowedEligibleShulker = borrowedEligibleShulker;
        this.state = borrowedDestinationSlot < 0 ? State.PLAN_OPERATION : State.RESERVE_HOTBAR;
    }

    static QuickCrossBoxSortSession create(MinecraftClient client, boolean onePerBox, List<Integer> containerSlots) {
        ClientPlayerEntity player = client.player;
        if (player != null && client.currentScreen instanceof HandledScreen<?> screen && (containerSlots != null || screen instanceof InventoryScreen) && player.currentScreenHandler == screen.getScreenHandler()) {
            if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
                message(player, "quickcraft.message.cross_box_sort.cursor_not_empty");
                return null;
            } else {
                PlayerInventory inventory = player.getInventory();
                int emptySlot = -1;

                for(int slot = 0; slot < 36; ++slot) {
                    if (!QuickCrossBoxSortProtection.isLocked(slot) && inventory.getStack(slot).isEmpty()) {
                        emptySlot = slot;
                        break;
                    }
                }

                if (emptySlot < 0) {
                    message(player, "quickcraft.message.cross_box_sort.inventory_full");
                    return null;
                } else {
                    int workspace = -1;

                    for(int slot = 0; slot < 9; ++slot) {
                        if (!QuickCrossBoxSortProtection.isLocked(slot) && inventory.getStack(slot).isEmpty()) {
                            workspace = slot;
                            break;
                        }
                    }

                    int borrowedDestination = -1;
                    QuickCrossBoxSortStackKey borrowedKey = null;
                    int borrowedCount = 0;
                    boolean borrowedShulker = false;
                    if (workspace < 0) {
                        workspace = java.util.stream.IntStream.range(0, 9).filter(i -> !QuickCrossBoxSortProtection.isLocked(i)).findFirst().orElse(-1);
                        if (workspace < 0) { message(player, "quickcraft.message.cross_box_sort.inventory_full"); return null; }
                        borrowedDestination = emptySlot;
                        ItemStack borrowed = inventory.getStack(workspace);
                        borrowedKey = new QuickCrossBoxSortStackKey(borrowed);
                        borrowedCount = borrowed.getCount();
                        borrowedShulker = QuickCrossBoxSortInventory.isVanillaShulkerBox(borrowed) && !QuickCrossBoxSortInventory.isNamedOrSpecial(borrowed) && borrowed.getCount() == 1;
                    }

                    QuickCrossBoxSortContainer container = null;
                    if (containerSlots != null) {
                        int temporary = -1;
                        for (int slot = 0; slot < 36; slot++) {
                            if (slot != workspace && slot != borrowedDestination && !QuickCrossBoxSortProtection.isLocked(slot) && inventory.getStack(slot).isEmpty()) { temporary = slot; break; }
                        }
                        if (temporary < 0) { message(player, "quickcraft.message.cross_box_sort.container_space"); return null; }
                        container = QuickCrossBoxSortContainer.create(client, screen, containerSlots, temporary);
                        if (container == null) { message(player, "quickcraft.message.cross_box_sort.container_unavailable"); return null; }
                    }
                    return new QuickCrossBoxSortSession(container, onePerBox, workspace, borrowedDestination, borrowedKey, borrowedCount, borrowedShulker);
                }
            }
        } else {
            message(player, "quickcraft.message.cross_box_sort.inventory_only");
            return null;
        }
    }

    void enterBackgroundMode(MinecraftClient client, InventoryScreen inventory) {
        this.backgroundMode = true;
        this.backgroundInventory = inventory;
        if (inventory == null) {
            client.setScreen((Screen)null);
        }
    }

    boolean consumeExpectedContainerScreen(Screen screen) {
        if (screen == null && closingOwnedBox && backgroundInventory != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            return client.currentScreen == backgroundInventory && client.player != null
                    && client.player.currentScreenHandler == client.player.playerScreenHandler;
        }
        if (container != null && container.consume(screen)) { return true; }
        if (this.backgroundMode && this.expectingHiddenContainerScreen && screen instanceof HandledScreen<?> containerScreen) {
            if (!(screen instanceof InventoryScreen)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null && containerScreen.getScreenHandler() == client.player.currentScreenHandler && containerScreen.getScreenHandler() instanceof net.minecraft.screen.ShulkerBoxScreenHandler) {
                    this.expectingHiddenContainerScreen = false;
                    this.hiddenContainerScreen = containerScreen;
                    com.yiyihehe.quickcraft.QuickContainerLock.bindInventoryShulkerScreen(containerScreen, openingInventorySlot);
                    return true;
                }

                return false;
            }
        }

        return false;
    }

    void cancel(MinecraftClient client) {
        if (expectingHiddenContainerScreen) {
            // Let the in-flight open arrive under this session's ownership before recovering.
            return;
        }
        if (state != State.STOPPED && state != State.ABORT_RECOVERY_WAIT && state != State.ABORT_WAIT_INVENTORY
        && state != State.ABORT_RETURN_CONTAINER && state != State.ABORT_RESTORE_HOTBAR && state != State.ABORT_WAIT_HOTBAR_RESTORE) {
            abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    boolean tick(MinecraftClient client) {
        if (this.state == State.STOPPED) {
            return false;
        } else if (client.player != null && client.getNetworkHandler() != null) {
            ClientPlayerEntity player = client.player;
            ++this.age;
            if (this.state == State.ABORT_RETURN_CONTAINER) {
                recoverContainer(client);
                return this.state != State.STOPPED;
            }
            if (this.state == State.ABORT_RECOVERY_WAIT) {
                this.finishAbortRecovery(player);
                return this.state != State.STOPPED;
            } else if (player.isDead()) {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
                return this.state != State.STOPPED;
            } else {
                Screen screen = client.currentScreen;
                if (this.backgroundMode && screen != this.backgroundInventory) {
                    this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
                    return this.state != State.STOPPED;
                } else if (!this.backgroundMode && screen != null && !(screen instanceof HandledScreen)) {
                    this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
                    return this.state != State.STOPPED;
                } else {
                    for(int immediateSteps = 0; immediateSteps < 64 && this.state != State.STOPPED; ++immediateSteps) {
                        State stateBeforeStep = this.state;
                        switch (this.state) {
                            case RESERVE_HOTBAR -> this.reserveHotbar(client);
                            case WAIT_RESERVE_HOTBAR -> this.waitForClick(client, () -> player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty() && inventoryStackMatches(player, this.borrowedDestinationSlot, this.borrowedOriginalKey, this.borrowedOriginalCount), State.PLAN_OPERATION);
                            case PLAN_OPERATION -> this.planOperation(client);
                            case BEGIN_OPERATION -> this.beginBatch();
                            case BATCH_OPEN_SOURCE -> this.openBox(client, this.batch.sourceInventorySlot(), State.BATCH_WAIT_SOURCE_OPEN);
                            case BATCH_WAIT_SOURCE_OPEN -> this.waitForOpenContainer(client, State.BATCH_PICK_SOURCE);
                            case BATCH_PICK_SOURCE -> this.batchPickSource(client);
                            case BATCH_WAIT_PICK_SOURCE -> this.waitForClick(client, () -> carriedMatches(player, this.activeJob.key, this.activeJob.originalCount), State.BATCH_STAGE_SOURCE);
                            case BATCH_STAGE_SOURCE -> this.batchStageSource(client);
                            case BATCH_WAIT_STAGE_SOURCE -> this.waitForClick(client, () -> player.currentScreenHandler.getCursorStack().isEmpty() && inventoryStackMatches(player, this.activeJob.stagingSlot, this.activeJob.key, this.activeJob.originalCount), State.BATCH_PICK_SOURCE);
                            case BATCH_CLOSE_SOURCE -> this.closeContainer(player, State.BATCH_WAIT_SOURCE_CLOSE);
                            case BATCH_WAIT_SOURCE_CLOSE -> this.waitForClosed(player, State.BATCH_BEGIN_TARGET);
                            case BATCH_BEGIN_TARGET -> this.batchBeginTarget();
                            case BATCH_OPEN_TARGET -> this.openBox(client, (this.batch.targets().get(this.targetGroupIndex)).targetInventorySlot(), State.BATCH_WAIT_TARGET_OPEN);
                            case BATCH_WAIT_TARGET_OPEN -> this.waitForOpenContainer(client, State.BATCH_PICK_STAGE);
                            case BATCH_PICK_STAGE -> this.batchPickStage(client);
                            case BATCH_WAIT_PICK_STAGE -> this.waitForClick(client, () -> carriedMatches(player, this.activeJob.key, this.activeJob.stagedCount) && player.getInventory().getStack(this.activeJob.stagingSlot).isEmpty(), State.BATCH_PLACE_TARGET);
                            case BATCH_PLACE_TARGET -> this.batchPlaceTarget(client);
                            case BATCH_WAIT_PLACE_TARGET -> this.waitForBatchTarget(player, client);
                            case BATCH_STORE_REMAINDER -> this.clickPlayerInventorySlot(client, this.activeJob.stagingSlot, 0, State.BATCH_WAIT_STORE_REMAINDER);
                            case BATCH_WAIT_STORE_REMAINDER -> this.waitForClick(client, () -> player.currentScreenHandler.getCursorStack().isEmpty() && inventoryStackMatches(player, this.activeJob.stagingSlot, this.activeJob.key, this.activeJob.stagedCount), State.BATCH_ADVANCE_TARGET);
                            case BATCH_ADVANCE_TARGET -> this.batchAdvanceTarget();
                            case BATCH_CLOSE_TARGET -> this.closeContainer(player, State.BATCH_WAIT_TARGET_CLOSE);
                            case BATCH_WAIT_TARGET_CLOSE -> this.waitForClosed(player, State.BATCH_BEGIN_TARGET);
                            case BATCH_BEGIN_RETURN -> this.batchBeginReturn();
                            case BATCH_OPEN_RETURN_SOURCE -> this.openBox(client, this.batch.sourceInventorySlot(), State.BATCH_WAIT_RETURN_SOURCE_OPEN);
                            case BATCH_WAIT_RETURN_SOURCE_OPEN -> this.waitForOpenContainer(client, State.BATCH_PICK_RETURN);
                            case BATCH_PICK_RETURN -> this.batchPickReturn(client);
                            case BATCH_WAIT_PICK_RETURN -> this.waitForClick(client, () -> carriedMatches(player, this.activeJob.key, this.activeJob.stagedCount) && player.getInventory().getStack(this.activeJob.stagingSlot).isEmpty(), State.BATCH_PLACE_RETURN);
                            case BATCH_PLACE_RETURN -> this.batchPlaceReturn(client);
                            case BATCH_WAIT_PLACE_RETURN -> this.waitForClick(client, () -> player.currentScreenHandler.getCursorStack().isEmpty() && containerStackMatches(player.currentScreenHandler, this.activeJob.sourceBoxSlot, this.activeJob.key, this.activeJob.stagedCount), State.BATCH_PICK_RETURN);
                            case BATCH_CLOSE_RETURN_SOURCE -> this.closeContainer(player, State.BATCH_WAIT_RETURN_SOURCE_CLOSE);
                            case BATCH_WAIT_RETURN_SOURCE_CLOSE -> this.waitForClosed(player, State.BATCH_COMPLETE);
                            case BATCH_COMPLETE -> this.completeBatch();
                            case BEGIN_SWAP_CYCLE -> this.beginSwaps(client);
                            case OPEN_SWAP_BOX -> this.openCurrentSwapBox(client);
                            case WAIT_SWAP_OPEN -> this.waitForOpenContainer(client, State.EXECUTE_SWAP);
                            case EXECUTE_SWAP -> this.executeSwap(client);
                            case WAIT_SWAP -> this.waitForSwap(client);
                            case CLOSE_SWAP_BOX -> this.closeContainer(player, State.WAIT_SWAP_CLOSE);
                            case WAIT_SWAP_CLOSE -> this.waitForClosed(player, this.afterSwapCloseState);
                            case RESTORE_HOTBAR -> this.restoreHotbar(client);
                            case WAIT_RESTORE_HOTBAR -> this.waitForClick(client, () -> player.getInventory().getStack(this.borrowedDestinationSlot).isEmpty() && !player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty() && player.currentScreenHandler.getCursorStack().isEmpty(), State.PREPARE_EMPTY_MERGES);
                            case PREPARE_EMPTY_MERGES -> this.prepareEmptyMerges(player);
                            case BEGIN_EMPTY_MERGE -> this.beginEmptyMerge(player);
                            case PICK_EMPTY_SOURCE -> this.pickEmptySource(client);
                            case WAIT_PICK_EMPTY_SOURCE -> this.waitForClick(client, () -> carriedMatches(player, this.emptyMerge.key(), this.emptyMergeCount) && emptyMergeStack(player, this.emptyMerge.sourceInventorySlot()).isEmpty(), State.PLACE_EMPTY_TARGET);
                            case PLACE_EMPTY_TARGET -> this.placeEmptyTarget(client);
                            case WAIT_PLACE_EMPTY_TARGET -> this.waitForClick(client, () -> carriedMatches(player, this.emptyMerge.key(), this.emptyMergeCount - this.emptyMerge.amount()) && emptyMergeStackMatches(player, this.emptyMerge.targetInventorySlot(), this.emptyMerge.key(), this.emptyTargetBeforeCount + this.emptyMerge.amount()), this.emptyMergeCount == this.emptyMerge.amount() ? State.BEGIN_EMPTY_MERGE : State.RETURN_EMPTY_REMAINDER);
                            case RETURN_EMPTY_REMAINDER -> this.returnEmptyRemainder(client);
                            case WAIT_RETURN_EMPTY_REMAINDER -> this.waitForClick(client, () -> player.currentScreenHandler.getCursorStack().isEmpty() && emptyMergeStackMatches(player, this.emptyMerge.sourceInventorySlot(), this.emptyMerge.key(), this.emptyMergeCount - this.emptyMerge.amount()), State.BEGIN_EMPTY_MERGE);
                            case ABORT_RETURN_CONTAINER -> this.recoverContainer(client);
                            case ABORT_RECOVERY_WAIT -> this.finishAbortRecovery(player);
                            case ABORT_WAIT_INVENTORY -> this.waitForAbortInventory(player);
                            case ABORT_RESTORE_HOTBAR -> this.restoreHotbarAfterAbort(client);
                            case ABORT_WAIT_HOTBAR_RESTORE -> this.waitForAbortHotbarRestore(player);
                            case STOPPED -> {
                                return false;
                            }
                        }

                        if (this.state == stateBeforeStep) {
                            break;
                        }
                    }

                    return this.state != State.STOPPED;
                }
            }
        } else {
            this.state = State.STOPPED;
            return false;
        }
    }

    private void beginBatch() {
        if (this.transferIndex >= this.transfers.size()) {
            this.state = State.BEGIN_SWAP_CYCLE;
        } else {
            int sourceInventorySlot = (this.transfers.get(this.transferIndex)).sourceInventorySlot();
            Map<Integer, BatchJob> jobs = new LinkedHashMap<>();
            Set<Integer> sourceSlots = new HashSet<>();
            Set<Integer> localTargetSlots = new HashSet<>();

            int end;
            for(end = this.transferIndex; end < this.transfers.size(); ++end) {
                QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey> move = this.transfers.get(end);
                if (move.sourceInventorySlot() != sourceInventorySlot) {
                    break;
                }

                Set<Integer> nextSources = new HashSet<>(sourceSlots);
                Set<Integer> nextTargets = new HashSet<>(localTargetSlots);
                nextSources.add(move.sourceBoxSlot());
                if (move.targetInventorySlot() == sourceInventorySlot) {
                    nextTargets.add(move.targetBoxSlot());
                }

                if (nextSources.stream().anyMatch(nextTargets::contains)) {
                    break;
                }

                BatchJob job = jobs.get(move.sourceBoxSlot());
                if (job == null) {
                    if (jobs.size() >= this.stagingInventorySlots.size()) {
                        break;
                    }

                    job = new BatchJob(move.sourceBoxSlot(), move.key(), this.stagingInventorySlots.get(jobs.size()));
                    jobs.put(move.sourceBoxSlot(), job);
                } else if (!job.key.equals(move.key())) {
                    break;
                }

                job.moves.add(move);
                sourceSlots = nextSources;
                localTargetSlots = nextTargets;
            }

            Map<Integer, List<QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey>>> groupedTargets = new LinkedHashMap<>();

            for(BatchJob job : jobs.values()) {
                for(QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey> move : job.moves) {
                    (groupedTargets.computeIfAbsent(move.targetInventorySlot(), (ignored) -> new ArrayList<>())).add(move);
                }
            }

            List<TargetGroup> targets = groupedTargets.entrySet().stream().map((entry) -> new TargetGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList();
            this.batch = new Batch(sourceInventorySlot, end, List.copyOf(jobs.values()), targets, Map.copyOf(jobs));
            this.batchJobIndex = 0;
            this.targetGroupIndex = 0;
            this.state = State.BATCH_OPEN_SOURCE;
        }
    }

    private void reserveHotbar(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player != null && isBaseMenu(player.currentScreenHandler) && this.borrowedDestinationSlot >= 0 && inventoryStackMatches(player, this.workspaceHotbarSlot, this.borrowedOriginalKey, this.borrowedOriginalCount) && player.getInventory().getStack(this.borrowedDestinationSlot).isEmpty()) {
            this.sendInventorySwap(client, this.borrowedDestinationSlot, this.workspaceHotbarSlot, State.WAIT_RESERVE_HOTBAR);
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.hotbar_staging_failed"));
        }
    }

    private void planOperation(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player != null && isBaseMenu(player.currentScreenHandler) && player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty()) {
            QuickCrossBoxSortInventory.ScanResult scan = container == null ? QuickCrossBoxSortInventory.scan(player.getInventory()) : QuickCrossBoxSortInventory.scan(container.snapshot());
            var staging = QuickCrossBoxSortInventory.scan(player.getInventory()).stagingInventorySlots().stream()
                    .filter(slot -> container == null || slot != container.temporarySlot()).toList();
            if (!staging.contains(this.workspaceHotbarSlot)) {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.hotbar_staging_failed"));
            } else {
                QuickCrossBoxSortPlanner.Plan<QuickCrossBoxSortStackKey> plan;
                try {
                    plan = QuickCrossBoxSortPlanner.plan(scan.boxes(), container == null ? QuickCrossBoxSortSpares.singles(player.getInventory()) : QuickCrossBoxSortSpares.singles(container.snapshot(), slot -> false), QuickCrossBoxSort.comparator(), onePerBox);
                } catch (RuntimeException exception) {
                    QuickCrossBoxSort.LOGGER.error("Failed to build global shulker sort plan", exception);
                    this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.plan_failed"));
                    return;
                }

                QuickCrossBoxSort.reportPlan(QuickCrossBoxSortSpares.needed(scan.boxes()), plan.targets().size(), onePerBox);
                this.stagingInventorySlots = staging;

                this.transfers = plan.merges();
                this.swapCycles = plan.swapCycles();
                this.transferIndex = 0;
                this.batch = null;
                this.emptiedBoxes += plan.emptiedBoxes();
                this.remainingBoxes = plan.remainingNonEmptyBoxes();
                this.crossBoxMoves += plan.crossBoxMoves();
                this.state = State.BEGIN_OPERATION;
            }
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.hotbar_staging_failed"));
        }
    }

    private void beginSwaps(MinecraftClient client) {
        if (this.swapCycles.isEmpty()) {
            this.state = State.RESTORE_HOTBAR;
            return;
        }
        var inventory = client.player.getInventory();
        List<Integer> hotbarSlots = new ArrayList<>();
        hotbarSlots.add(this.workspaceHotbarSlot);
        // The external-container path reserves its own temporary slot and keeps one swap register.
        if (container == null) {
            for (int slot = 0; slot < 9; slot++) {
                if (slot != this.workspaceHotbarSlot && !QuickCrossBoxSortProtection.isLocked(slot)
                        && inventory.getStack(slot).isEmpty()) {
                    hotbarSlots.add(slot);
                }
            }
        }
        this.scheduledSwaps = QuickCrossBoxSortPlanner.scheduleSwaps(this.swapCycles, hotbarSlots.size());
        int usedRegisters = scheduledSwaps.stream()
                .mapToInt(QuickCrossBoxSortPlanner.ScheduledSwap::register).max().orElse(0) + 1;
        this.swapHotbarSlots = List.copyOf(hotbarSlots.subList(0, usedRegisters));
        this.swapStepIndex = 0;
        this.state = State.OPEN_SWAP_BOX;
    }

    private QuickCrossBoxSortPlanner.ScheduledSwap<QuickCrossBoxSortStackKey> currentSwap() {
        return this.scheduledSwaps.get(this.swapStepIndex);
    }

    private void openCurrentSwapBox(MinecraftClient client) {
        this.openBox(client, this.currentSwap().step().slot().inventorySlot(), State.WAIT_SWAP_OPEN);
    }

    private void executeSwap(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        var swap = this.currentSwap();
        var step = swap.step();
        int hotbarSlot = this.swapHotbarSlots.get(swap.register());
        if (player != null && this.validOpenBoxMenu(player.currentScreenHandler)
                && stackMatches(player.currentScreenHandler.getSlot(step.slot().boxSlot()).getStack(), step.slotBefore())
                && stackMatches(player.getInventory().getStack(hotbarSlot), step.registerBefore())
                && player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.sendSwap(client, player.currentScreenHandler, step.slot().boxSlot(), hotbarSlot, State.WAIT_SWAP);
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.layout_changed"));
        }
    }

    private void waitForSwap(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        var swap = this.currentSwap();
        var step = swap.step();
        int hotbarSlot = this.swapHotbarSlots.get(swap.register());
        BooleanSupplier validation = () -> stackMatches(player.currentScreenHandler.getSlot(step.slot().boxSlot()).getStack(), step.slotAfter())
                && stackMatches(player.getInventory().getStack(hotbarSlot), step.registerAfter())
                && player.currentScreenHandler.getCursorStack().isEmpty();
        if (!this.clickSettled(player, validation)) {
            this.abortIfClickTimedOut(client, player, State.EXECUTE_SWAP);
            return;
        }

        boolean hasNextStep = ++this.swapStepIndex < this.scheduledSwaps.size();
        if (hasNextStep && this.currentSwap().step().slot().inventorySlot() == step.slot().inventorySlot()) {
            this.state = State.EXECUTE_SWAP;
            return;
        }
        this.afterSwapCloseState = hasNextStep ? State.OPEN_SWAP_BOX : State.RESTORE_HOTBAR;
        this.state = State.CLOSE_SWAP_BOX;
    }

    private void restoreHotbar(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player != null && (!player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty()
                || hasItemsInExtraSwapSlots(player.getInventory()))) {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
            return;
        }
        if (this.borrowedDestinationSlot < 0) {
            this.state = State.PREPARE_EMPTY_MERGES;
        } else if (player != null && isBaseMenu(player.currentScreenHandler) && player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty() && this.borrowedStackMatches(player.getInventory().getStack(this.borrowedDestinationSlot))) {
            this.sendInventorySwap(client, this.borrowedDestinationSlot, this.workspaceHotbarSlot, State.WAIT_RESTORE_HOTBAR);
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
        }
    }

    private void batchPickSource(MinecraftClient client) {
        if (this.batchJobIndex >= this.batch.jobs().size()) {
            this.state = State.BATCH_CLOSE_SOURCE;
        } else {
            ClientPlayerEntity player = client.player;
            this.activeJob = this.batch.jobs().get(this.batchJobIndex);
            ItemStack source = player.currentScreenHandler.getSlot(this.activeJob.sourceBoxSlot).getStack();
            if (this.validOpenBoxMenu(player.currentScreenHandler) && this.activeJob.key.matches(source) && source.getCount() >= this.activeJob.plannedAmount() && player.getInventory().getStack(this.activeJob.stagingSlot).isEmpty()) {
                this.activeJob.originalCount = source.getCount();
                this.activeJob.stagedCount = source.getCount();
                this.clickContainerSlot(client, this.activeJob.sourceBoxSlot, 0, State.BATCH_WAIT_PICK_SOURCE);
            } else {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
            }
        }
    }

    private void batchBeginTarget() {
        if (this.targetGroupIndex >= this.batch.targets().size()) {
            this.state = State.BATCH_BEGIN_RETURN;
        } else {
            this.targetTransferIndex = 0;
            this.state = State.BATCH_OPEN_TARGET;
        }
    }

    private void batchStageSource(MinecraftClient client) {
        this.clickPlayerInventorySlot(client, this.activeJob.stagingSlot, 0, State.BATCH_WAIT_STAGE_SOURCE);
        ++this.batchJobIndex;
    }

    private void batchPickStage(MinecraftClient client) {
        TargetGroup group = this.batch.targets().get(this.targetGroupIndex);
        if (this.targetTransferIndex >= group.moves().size()) {
            ++this.targetGroupIndex;
            this.state = State.BATCH_CLOSE_TARGET;
        } else {
            this.batchTransfer = group.moves().get(this.targetTransferIndex);
            this.activeJob = this.batch.jobsBySourceSlot().get(this.batchTransfer.sourceBoxSlot());
            if (this.activeJob != null && this.activeJob.stagedCount >= this.batchTransfer.amount() && inventoryStackMatches(client.player, this.activeJob.stagingSlot, this.activeJob.key, this.activeJob.stagedCount)) {
                this.clickPlayerInventorySlot(client, this.activeJob.stagingSlot, 0, State.BATCH_WAIT_PICK_STAGE);
            } else {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
            }
        }
    }

    private void batchPlaceTarget(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ItemStack target = player.currentScreenHandler.getSlot(this.batchTransfer.targetBoxSlot()).getStack();
        if (this.validOpenBoxMenu(player.currentScreenHandler) && (target.isEmpty() || (this.batchTransfer.key()).matches(target))) {
            this.batchTargetBeforeCount = target.isEmpty() ? 0 : target.getCount();
            if (this.batchTargetBeforeCount + this.batchTransfer.amount() > (this.batchTransfer.key()).template().getMaxCount()) {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
            } else {
                this.clickContainerSlot(client, this.batchTransfer.targetBoxSlot(), 0, State.BATCH_WAIT_PLACE_TARGET);
            }
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
        }
    }

    private void waitForBatchTarget(ClientPlayerEntity player, MinecraftClient client) {
        int remaining = this.activeJob.stagedCount - this.batchTransfer.amount();
        BooleanSupplier validation = () -> carriedMatches(player, this.activeJob.key, remaining) && containerStackMatches(player.currentScreenHandler, this.batchTransfer.targetBoxSlot(), this.batchTransfer.key(), this.batchTargetBeforeCount + this.batchTransfer.amount());
        if (this.clickSettled(player, validation)) {
            this.activeJob.stagedCount = remaining;
            this.state = remaining == 0 ? State.BATCH_ADVANCE_TARGET : State.BATCH_STORE_REMAINDER;
        } else {
            this.abortIfClickTimedOut(client, player, State.BATCH_ADVANCE_TARGET);
        }
    }

    private void batchAdvanceTarget() {
        ++this.targetTransferIndex;
        this.state = State.BATCH_PICK_STAGE;
    }

    private void batchBeginReturn() {
        boolean hasRemainder = this.batch.jobs().stream().anyMatch((job) -> job.stagedCount > 0);
        if (!hasRemainder) {
            this.state = State.BATCH_COMPLETE;
        } else {
            this.returnJobIndex = 0;
            this.state = State.BATCH_OPEN_RETURN_SOURCE;
        }
    }

    private void batchPickReturn(MinecraftClient client) {
        while(this.returnJobIndex < this.batch.jobs().size() && (this.batch.jobs().get(this.returnJobIndex)).stagedCount == 0) {
            ++this.returnJobIndex;
        }

        if (this.returnJobIndex >= this.batch.jobs().size()) {
            this.state = State.BATCH_CLOSE_RETURN_SOURCE;
        } else {
            this.activeJob = this.batch.jobs().get(this.returnJobIndex);
            if (!inventoryStackMatches(client.player, this.activeJob.stagingSlot, this.activeJob.key, this.activeJob.stagedCount)) {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
            } else {
                this.clickPlayerInventorySlot(client, this.activeJob.stagingSlot, 0, State.BATCH_WAIT_PICK_RETURN);
            }
        }
    }

    private void batchPlaceReturn(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (this.validOpenBoxMenu(player.currentScreenHandler) && player.currentScreenHandler.getSlot(this.activeJob.sourceBoxSlot).getStack().isEmpty()) {
            this.clickContainerSlot(client, this.activeJob.sourceBoxSlot, 0, State.BATCH_WAIT_PLACE_RETURN);
            ++this.returnJobIndex;
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
        }
    }

    private void completeBatch() {
        this.transferIndex = this.batch.endTransferIndex();
        this.batch = null;
        this.state = State.BEGIN_OPERATION;
    }

    private void openBox(MinecraftClient client, int inventorySlot, State waitState) {
        ClientPlayerEntity player = client.player;
        if (container != null) {
            int ready = container.prepareOpen(client, inventorySlot, age);
            if (ready < 0) { this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed")); return; }
            if (ready == 0) { return; }
            inventorySlot = container.temporarySlot();
        }
        openingInventorySlot = inventorySlot;
        if (player != null && player.currentScreenHandler == player.playerScreenHandler && player.currentScreenHandler.getCursorStack().isEmpty()) {
            ItemStack box = player.getInventory().getStack(inventorySlot);
            if (QuickCrossBoxSortInventory.isVanillaShulkerBox(box) && box.getCount() == 1 && !QuickCrossBoxSortInventory.isNamedOrSpecial(box)) {
                this.waitRevision = QuickCrossBoxSortSync.revision();
                this.deadline = this.age + 100;
                int menuSlot = QuickCrossBoxSortProtection.menuSlot(player.playerScreenHandler, inventorySlot);
                this.expectingHiddenContainerScreen = this.backgroundMode;
                if (menuSlot >= 0 && com.yiyihehe.quickcraft.compat.QuickShulkerConnection.sendOpen(menuSlot)) {
                    this.state = waitState;
                } else {
                    this.expectingHiddenContainerScreen = false;
                    this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.quickshulker_unavailable"));
                }
            } else {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
            }
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    private void waitForOpenContainer(MinecraftClient client, State next) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            this.state = State.STOPPED;
        } else {
            ScreenHandler menu = player.currentScreenHandler;
            if (menu != player.playerScreenHandler && menu instanceof net.minecraft.screen.ShulkerBoxScreenHandler && menu.slots.size() == 63 && QuickCrossBoxSortSync.contentAdvancedFor(this.waitRevision, menu.syncId)) {
                this.openContainerId = menu.syncId;
                this.expectingHiddenContainerScreen = false;
                this.state = next;
            } else {
                if (this.age >= this.deadline) {
                    QuickCrossBoxSort.LOGGER.warn("Click timed out: state={}, next={}, expectedContainer={}, actualContainer={}, expectedStateId={}, actualStateId={}, cursor={}", new Object[]{this.state, next, this.waitContainerId, player.currentScreenHandler.syncId, this.waitStateId, player.currentScreenHandler.getRevision(), player.currentScreenHandler.getCursorStack()});
                    this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.sync_timeout"));
                }

            }
        }
    }

    private void closeContainer(ClientPlayerEntity player, State waitState) {
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.abort(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        } else {
            closeOwnedBox(player);
            this.hiddenContainerScreen = null;
            this.openContainerId = Integer.MIN_VALUE;
            this.deadline = this.age + (container == null ? 20 : 120);
            this.state = waitState;
        }
    }

    private void closeOwnedBox(ClientPlayerEntity player) {
        // Keep the inventory view while vanilla closes and resets the real, hidden menu.
        closingOwnedBox = true;
        try {
            player.closeHandledScreen();
        } finally {
            closingOwnedBox = false;
        }
    }

    private void waitForClosed(ClientPlayerEntity player, State next) {
        if (container != null) {
            int ready = container.finishClose(MinecraftClient.getInstance(), age);
            if (ready > 0) { this.state = next; }
            else if (ready < 0 || age >= deadline) { this.abort(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.cross_box_sort.inventory_changed")); }
            return;
        }
        if (player.currentScreenHandler == player.playerScreenHandler && player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.state = next;
        } else {
            if (this.age >= this.deadline) {
                this.abort(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.cross_box_sort.sync_timeout"));
            }

        }
    }

    private void prepareEmptyMerges(ClientPlayerEntity player) {
        if (isBaseMenu(player.currentScreenHandler) && player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.emptyMerges = container == null ? QuickCrossBoxSortEmptyMerges.plan(player.getInventory()) : QuickCrossBoxSortEmptyMerges.plan(container.snapshot());
            this.emptyMergeIndex = 0;
            this.state = State.BEGIN_EMPTY_MERGE;
        } else {
            this.abort(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    private void beginEmptyMerge(ClientPlayerEntity player) {
        if (this.emptyMergeIndex >= this.emptyMerges.size()) {
            this.finish(player);
        } else {
            this.emptyMerge = this.emptyMerges.get(this.emptyMergeIndex);
            this.state = State.PICK_EMPTY_SOURCE;
        }
    }

    private void pickEmptySource(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ItemStack source = emptyMergeStack(player, this.emptyMerge.sourceInventorySlot());
        ItemStack target = emptyMergeStack(player, this.emptyMerge.targetInventorySlot());
        if (this.emptyMerge.key().matches(source) && this.emptyMerge.key().matches(target) && source.getCount() >= this.emptyMerge.amount() && target.getCount() + this.emptyMerge.amount() <= target.getMaxCount()) {
            this.emptyMergeCount = source.getCount();
            this.emptyTargetBeforeCount = target.getCount();
            this.clickInventoryMenuSlot(client, this.emptyMerge.sourceInventorySlot(), 0, State.WAIT_PICK_EMPTY_SOURCE);
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
        }
    }

    private void placeEmptyTarget(MinecraftClient client) {
        this.clickInventoryMenuSlot(client, this.emptyMerge.targetInventorySlot(), 0, State.WAIT_PLACE_EMPTY_TARGET);
        ++this.emptyMergeIndex;
    }

    private void returnEmptyRemainder(MinecraftClient client) {
        this.clickInventoryMenuSlot(client, this.emptyMerge.sourceInventorySlot(), 0, State.WAIT_RETURN_EMPTY_REMAINDER);
    }

    private void finish(ClientPlayerEntity player) {
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.abort(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        } else {
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.completed", this.emptiedBoxes, this.remainingBoxes, this.crossBoxMoves));
            this.state = State.STOPPED;
            if (container != null) { container.reveal(MinecraftClient.getInstance()); }
        }
    }

    private void clickContainerSlot(MinecraftClient client, int menuSlot, int button, State waitState) {
        this.sendClick(client, client.player.currentScreenHandler, menuSlot, button, waitState);
    }

    private void clickPlayerInventorySlot(MinecraftClient client, int inventorySlot, int button, State waitState) {
        ClientPlayerEntity player = client.player;
        int menuSlot = QuickCrossBoxSortProtection.menuSlot(player.currentScreenHandler, inventorySlot);
        if (menuSlot < 0) {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.inventory_changed"));
        } else {
            this.sendClick(client, player.currentScreenHandler, menuSlot, button, waitState);
        }
    }

    private void clickInventoryMenuSlot(MinecraftClient client, int inventorySlot, int button, State waitState) {
        ClientPlayerEntity player = client.player;
        if (!isBaseMenu(player.currentScreenHandler)) {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        } else {
            if (container == null) { this.clickPlayerInventorySlot(client, inventorySlot, button, waitState); }
            else { this.sendClick(client, player.currentScreenHandler, container.slot(inventorySlot), button, waitState); }
        }
    }

    private void sendInventorySwap(MinecraftClient client, int inventorySlot, int hotbarSlot, State waitState) {
        ClientPlayerEntity player = client.player;
        if (player != null && isBaseMenu(player.currentScreenHandler)) {
            int menuSlot = QuickCrossBoxSortProtection.menuSlot(player.currentScreenHandler, inventorySlot);
            this.sendSwap(client, player.currentScreenHandler, menuSlot, hotbarSlot, waitState);
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    private void sendSwap(MinecraftClient client, ScreenHandler menu, int menuSlot, int hotbarSlot, State waitState) {
        this.sendInput(client, menu, menuSlot, hotbarSlot, SlotActionType.SWAP, waitState);
    }

    private void sendClick(MinecraftClient client, ScreenHandler menu, int menuSlot, int button, State waitState) {
        this.sendInput(client, menu, menuSlot, button, SlotActionType.PICKUP, waitState);
    }

    private void sendInput(MinecraftClient client, ScreenHandler menu, int menuSlot, int button, SlotActionType input, State waitState) {
        if (client.interactionManager != null && client.player != null && menuSlot >= 0 && menuSlot < menu.slots.size()) {
            if (com.yiyihehe.quickcraft.QuickContainerLock.isLockedSlot(menu, menuSlot)
            || input == SlotActionType.SWAP && QuickCrossBoxSortProtection.isLocked(button)) {
                this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
                return;
            }
            this.waitRevision = QuickCrossBoxSortSync.revision();
            this.waitContainerId = menu.syncId;
            this.waitStateId = menu.getRevision();
            this.clickSentAt = this.age;
            this.deadline = this.age + 100;
            client.interactionManager.clickSlot(menu.syncId, menuSlot, button, input, client.player);
            this.state = waitState;
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    private void waitForClick(MinecraftClient client, BooleanSupplier validation, State next) {
        ClientPlayerEntity player = client.player;
        if (player != null && player.currentScreenHandler.syncId == this.waitContainerId) {
            if (this.clickSettled(player, validation)) {
                this.state = next;
            } else {
                this.abortIfClickTimedOut(client, player, next);
            }
        } else {
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.safe_abort"));
        }
    }

    private boolean clickSettled(ClientPlayerEntity player, BooleanSupplier validation) {
        if (player.currentScreenHandler.syncId == this.waitContainerId && validation.getAsBoolean()) {
            boolean serverConfirmed = QuickCrossBoxSortSync.confirmedAfter(this.waitRevision, this.waitContainerId, this.waitStateId, player.currentScreenHandler.getRevision());
            return serverConfirmed || this.age >= this.clickSentAt + 2;
        } else {
            return false;
        }
    }

    private void abortIfClickTimedOut(MinecraftClient client, ClientPlayerEntity player, State next) {
        if (this.age >= this.deadline) {
            QuickCrossBoxSort.LOGGER.warn("Click timed out: state={}, next={}, expectedContainer={}, actualContainer={}, expectedStateId={}, actualStateId={}, cursor={}", new Object[]{this.state, next, this.waitContainerId, player.currentScreenHandler.syncId, this.waitStateId, player.currentScreenHandler.getRevision(), player.currentScreenHandler.getCursorStack()});
            this.abort(client, Text.translatable("quickcraft.message.cross_box_sort.sync_timeout"));
        }
    }

    private void abort(MinecraftClient client, Text reason) {
        if (this.state != State.STOPPED && this.state != State.ABORT_RECOVERY_WAIT && this.state != State.ABORT_WAIT_INVENTORY && this.state != State.ABORT_RETURN_CONTAINER && this.state != State.ABORT_RESTORE_HOTBAR && this.state != State.ABORT_WAIT_HOTBAR_RESTORE) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                this.state = State.STOPPED;
            } else {
                QuickCrossBoxSort.LOGGER.warn("Cross-box sorting aborted: state={}, reason={}", state, reason.getString());
                this.abortMessage = reason;
                this.expectingHiddenContainerScreen = false;
                ItemStack carried = player.currentScreenHandler.getCursorStack();
                if (carried.isEmpty()) {
                    this.continueAbortWithEmptyCursor(player);
                } else {
                    int recoverySlot = this.findRecoveryMenuSlot(player, carried);
                    if (recoverySlot >= 0 && client.interactionManager != null) {
                        this.sendClick(client, player.currentScreenHandler, recoverySlot, 0, State.ABORT_RECOVERY_WAIT);
                    } else {
                        this.revealHiddenContainerForRecovery();
                        QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.cursor_recovery_failed"));
                        this.state = State.STOPPED;
                    }
                }
            }
        }
    }

    private void finishAbortRecovery(ClientPlayerEntity player) {
        boolean serverConfirmed = QuickCrossBoxSortSync.confirmedAfter(this.waitRevision, this.waitContainerId, this.waitStateId, player.currentScreenHandler.getRevision());
        if ((serverConfirmed || this.age >= this.clickSentAt + 4) && player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.continueAbortWithEmptyCursor(player);
        } else {
            if (this.age >= this.deadline) {
                this.revealHiddenContainerForRecovery();
                QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.cursor_recovery_failed"));
                this.state = State.STOPPED;
            }

        }
    }

    private void continueAbortWithEmptyCursor(ClientPlayerEntity player) {
        if (container != null && container.hasBorrowedBox()) {
            if (validOpenBoxMenu(player.currentScreenHandler) || this.hiddenContainerScreen instanceof HandledScreen<?> hidden && hidden.getScreenHandler() == player.currentScreenHandler) { closeOwnedBox(player); }
            this.state = State.ABORT_RETURN_CONTAINER;
            return;
        }
        PlayerInventory inventory = player.getInventory();
        boolean borrowOutstanding = this.borrowedDestinationSlot >= 0 && !inventory.getStack(this.borrowedDestinationSlot).isEmpty();
        boolean liveWorkspaceToken = !inventory.getStack(this.workspaceHotbarSlot).isEmpty() && (this.borrowedDestinationSlot < 0 || borrowOutstanding);
        if (liveWorkspaceToken || hasItemsInExtraSwapSlots(inventory)) {
            this.revealHiddenContainerForRecovery();
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
            this.state = State.STOPPED;
        } else if (borrowOutstanding) {
            if (player.currentScreenHandler != player.playerScreenHandler) {
                closeOwnedBox(player);
                this.deadline = this.age + 20;
                this.state = State.ABORT_WAIT_INVENTORY;
            } else {
                this.state = State.ABORT_RESTORE_HOTBAR;
            }

        } else {
            if (player.currentScreenHandler != player.playerScreenHandler) {
                closeOwnedBox(player);
            }

            QuickCrossBoxSort.message(this.abortMessage);
            this.state = State.STOPPED;
        }
    }

    private void waitForAbortInventory(ClientPlayerEntity player) {
        if (player.currentScreenHandler == player.playerScreenHandler && player.currentScreenHandler.getCursorStack().isEmpty()) {
            this.state = State.ABORT_RESTORE_HOTBAR;
        } else if (this.age >= this.deadline) {
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
            this.state = State.STOPPED;
        }

    }

    private void restoreHotbarAfterAbort(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player != null && player.currentScreenHandler == player.playerScreenHandler && this.borrowedDestinationSlot >= 0 && player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty() && this.borrowedStackMatches(player.getInventory().getStack(this.borrowedDestinationSlot))) {
            this.sendInventorySwap(client, this.borrowedDestinationSlot, this.workspaceHotbarSlot, State.ABORT_WAIT_HOTBAR_RESTORE);
        } else {
            if (player != null) {
                QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
            }

            this.state = State.STOPPED;
        }
    }

    private void waitForAbortHotbarRestore(ClientPlayerEntity player) {
        BooleanSupplier validation = () -> player.getInventory().getStack(this.borrowedDestinationSlot).isEmpty() && !player.getInventory().getStack(this.workspaceHotbarSlot).isEmpty() && player.currentScreenHandler.getCursorStack().isEmpty();
        if (this.clickSettled(player, validation)) {
            QuickCrossBoxSort.message(this.abortMessage);
            this.state = State.STOPPED;
        } else if (this.age >= this.deadline) {
            QuickCrossBoxSort.message(Text.translatable("quickcraft.message.cross_box_sort.hotbar_restore_failed"));
            this.state = State.STOPPED;
        }

    }

    private boolean hasItemsInExtraSwapSlots(PlayerInventory inventory) {
        for (int slot : swapHotbarSlots) {
            if (slot != workspaceHotbarSlot && !inventory.getStack(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void revealHiddenContainerForRecovery() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler && this.hiddenContainerScreen != null) {
            client.setScreen(this.hiddenContainerScreen);
        }

    }

    private int findRecoveryMenuSlot(ClientPlayerEntity player, ItemStack carried) {
        PlayerInventory inventory = player.getInventory();

        for(int i = 0; i < 36; ++i) {
            if (QuickCrossBoxSortProtection.isLocked(i)) { continue; }
            ItemStack target = inventory.getStack(i);
            if (target.isEmpty() || ItemStack.areItemsAndComponentsEqual(target, carried) && target.getCount() + carried.getCount() <= target.getMaxCount()) {
                int menuSlot = QuickCrossBoxSortProtection.menuSlot(player.currentScreenHandler, i);
                if (menuSlot >= 0) {
                    return menuSlot;
                }
            }
        }

        return -1;
    }

    private boolean isBaseMenu(ScreenHandler menu) {
        var player = MinecraftClient.getInstance().player;
        return container == null ? player != null && menu == player.playerScreenHandler : container.isBase(menu);
    }

    private ItemStack emptyMergeStack(ClientPlayerEntity player, int slot) {
        return container == null ? player.getInventory().getStack(slot) : container.stack(slot);
    }

    private boolean emptyMergeStackMatches(ClientPlayerEntity player, int slot, QuickCrossBoxSortStackKey key, int count) {
        ItemStack stack = emptyMergeStack(player, slot);
        return count == 0 ? stack.isEmpty() : key.matches(stack) && stack.getCount() == count;
    }

    private void recoverContainer(MinecraftClient client) {
        int ready = container.finishClose(client, age);
        if (ready > 0) { continueAbortWithEmptyCursor(client.player); }
        else if (ready < 0) {
            state = State.STOPPED;
            message(client.player, "quickcraft.message.cross_box_sort.container_recovery_failed");
            container.reveal(client);
        }
    }

    private boolean validOpenBoxMenu(ScreenHandler menu) {
        return menu.syncId == this.openContainerId && menu instanceof net.minecraft.screen.ShulkerBoxScreenHandler && menu.slots.size() == 63;
    }

    private static boolean carriedMatches(ClientPlayerEntity player, QuickCrossBoxSortStackKey key, int count) {
        ItemStack carried = player.currentScreenHandler.getCursorStack();
        return count == 0 ? carried.isEmpty() : key.matches(carried) && carried.getCount() == count;
    }

    private static boolean inventoryStackMatches(ClientPlayerEntity player, int inventorySlot, QuickCrossBoxSortStackKey key, int count) {
        ItemStack stack = player.getInventory().getStack(inventorySlot);
        return count == 0 ? stack.isEmpty() : key.matches(stack) && stack.getCount() == count;
    }

    private static boolean containerStackMatches(ScreenHandler menu, int slot, QuickCrossBoxSortStackKey key, int count) {
        ItemStack stack = menu.getSlot(slot).getStack();
        return count == 0 ? stack.isEmpty() : key.matches(stack) && stack.getCount() == count;
    }

    private static boolean stackMatches(ItemStack actual, QuickCrossBoxSortStacks.Stack<QuickCrossBoxSortStackKey> expected) {
        return expected == null ? actual.isEmpty() : (expected.key()).matches(actual) && actual.getCount() == expected.count();
    }

    private boolean borrowedStackMatches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        } else if (this.borrowedEligibleShulker) {
            return QuickCrossBoxSortInventory.isVanillaShulkerBox(stack) && !QuickCrossBoxSortInventory.isNamedOrSpecial(stack) && stack.getCount() == 1;
        } else {
            return this.borrowedOriginalKey != null && this.borrowedOriginalKey.matches(stack) && stack.getCount() == this.borrowedOriginalCount;
        }
    }

    private static void message(ClientPlayerEntity player, String translationKey) {
        if (player != null) {
            QuickCrossBoxSort.message(Text.translatable(translationKey));
        }

    }

    private enum State {
        RESERVE_HOTBAR,
        WAIT_RESERVE_HOTBAR,
        PLAN_OPERATION,
        BEGIN_OPERATION,
        BATCH_OPEN_SOURCE,
        BATCH_WAIT_SOURCE_OPEN,
        BATCH_PICK_SOURCE,
        BATCH_WAIT_PICK_SOURCE,
        BATCH_STAGE_SOURCE,
        BATCH_WAIT_STAGE_SOURCE,
        BATCH_CLOSE_SOURCE,
        BATCH_WAIT_SOURCE_CLOSE,
        BATCH_BEGIN_TARGET,
        BATCH_OPEN_TARGET,
        BATCH_WAIT_TARGET_OPEN,
        BATCH_PICK_STAGE,
        BATCH_WAIT_PICK_STAGE,
        BATCH_PLACE_TARGET,
        BATCH_WAIT_PLACE_TARGET,
        BATCH_STORE_REMAINDER,
        BATCH_WAIT_STORE_REMAINDER,
        BATCH_ADVANCE_TARGET,
        BATCH_CLOSE_TARGET,
        BATCH_WAIT_TARGET_CLOSE,
        BATCH_BEGIN_RETURN,
        BATCH_OPEN_RETURN_SOURCE,
        BATCH_WAIT_RETURN_SOURCE_OPEN,
        BATCH_PICK_RETURN,
        BATCH_WAIT_PICK_RETURN,
        BATCH_PLACE_RETURN,
        BATCH_WAIT_PLACE_RETURN,
        BATCH_CLOSE_RETURN_SOURCE,
        BATCH_WAIT_RETURN_SOURCE_CLOSE,
        BATCH_COMPLETE,
        BEGIN_SWAP_CYCLE,
        OPEN_SWAP_BOX,
        WAIT_SWAP_OPEN,
        EXECUTE_SWAP,
        WAIT_SWAP,
        CLOSE_SWAP_BOX,
        WAIT_SWAP_CLOSE,
        RESTORE_HOTBAR,
        WAIT_RESTORE_HOTBAR,
        PREPARE_EMPTY_MERGES,
        BEGIN_EMPTY_MERGE,
        PICK_EMPTY_SOURCE,
        WAIT_PICK_EMPTY_SOURCE,
        PLACE_EMPTY_TARGET,
        WAIT_PLACE_EMPTY_TARGET,
        RETURN_EMPTY_REMAINDER,
        WAIT_RETURN_EMPTY_REMAINDER,
        ABORT_RETURN_CONTAINER,
        ABORT_RECOVERY_WAIT,
        ABORT_WAIT_INVENTORY,
        ABORT_RESTORE_HOTBAR,
        ABORT_WAIT_HOTBAR_RESTORE,
        STOPPED;

        // $FF: synthetic method

    }

    private static final class BatchJob {
        private final int sourceBoxSlot;
        private final QuickCrossBoxSortStackKey key;
        private final int stagingSlot;
        private final List<QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey>> moves = new ArrayList<>();
        private int originalCount;
        private int stagedCount;

        private BatchJob(int sourceBoxSlot, QuickCrossBoxSortStackKey key, int stagingSlot) {
            this.sourceBoxSlot = sourceBoxSlot;
            this.key = key;
            this.stagingSlot = stagingSlot;
        }

        private int plannedAmount() {
            return this.moves.stream().mapToInt(QuickCrossBoxSortStacks.Transfer::amount).sum();
        }
    }

    private record TargetGroup(int targetInventorySlot, List<QuickCrossBoxSortStacks.Transfer<QuickCrossBoxSortStackKey>> moves) {
    }

    private record Batch(int sourceInventorySlot, int endTransferIndex, List<BatchJob> jobs, List<TargetGroup> targets, Map<Integer, BatchJob> jobsBySourceSlot) {
    }
}
