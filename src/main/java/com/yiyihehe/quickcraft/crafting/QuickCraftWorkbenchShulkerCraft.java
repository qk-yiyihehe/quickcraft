package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs.WorkbenchShulkerPipelineMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台潜影盒喷射执行器。只负责潜影盒直填和对应输出策略，不处理普通配方书合成。
 */
public final class QuickCraftWorkbenchShulkerCraft implements ClientModInitializer {
    private static final int OUTPUT_SLOT = 0;
    private static final int GRID_START = 1;
    private static final int GRID_END = 9;
    private static final int MAX_OUTPUT_BURST = 64;
    private static final int MAX_FAILURES = 3;
    private static final int MAX_ACK_LOCAL_STEPS = 8;
    private static final int CURSOR_SETTLE_TICKS = 4;
    private static final int RECOVERY_PAUSE_TICKS = 4;
    private static final int CURSOR_TIMEOUT_TICKS = 20;
    private static final long SERVER_STATS_TIMEOUT_NANOS = 3_000_000_000L;
    // 1.21 原版 REQUEST_STATS 必定回包；10 秒只用于界定连接失效，不参与正常批次节拍。
    private static final long ACK_STATS_PROBE_TIMEOUT_NANOS = 10_000_000_000L;
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
    private int sessionOutputClicks;
    private boolean sessionOutputToShulker;
    private WorkbenchShulkerPipelineMode sessionMode = WorkbenchShulkerPipelineMode.RESPONSE_STABLE;
    private int serverCorrectionPauseTicks;
    private int occupiedCursorTicks;
    private int sessionCursorSettleTicks;
    private int sessionRecoveryPauseTicks;
    private int sessionCursorTimeoutTicks;
    private int serverOutputMismatchTicks;
    private boolean ackBatchRecording;
    private boolean ackBatchAwaiting;
    private boolean ackStopRequested;
    private Text ackStopMessage;
    private int ackBatchClickCount;
    private int ackBatchStartRevision;
    private long ackBatchId;
    private long ackBatchSentAtNanos;
    private long ackBatchLastProgressAtNanos;
    private int ackBatchLastRevision;
    private int ackBatchFullInventoryUpdates;
    private int ackBatchSourceBatches;
    private int ackBatchOutputClicks;
    private AckBatchKind ackBatchKind;
    private boolean ackBatchUsedStatsProbe;
    private List<ItemStack> ackExpectedSlots = List.of();
    private List<Integer> ackExpectedSlotIds = List.of();
    private ItemStack ackExpectedCursor = ItemStack.EMPTY;
    private boolean ackStatsProbePending;
    private long ackStatsProbeBatchId;
    private ClientPlayNetworkHandler ackStatsProbeNetworkHandler;
    private long ackStatsProbeSentAtNanos;
    private long sessionAckMaxRegularLatencyNanos;
    private boolean craftStatsBaselinePending;
    private ClientPlayNetworkHandler craftStatsBaselineNetworkHandler;
    private long craftStatsBaselineRequestedAtNanos;
    private boolean craftStatsBaselineAvailable;

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
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

    public static void onServerStatistics(ClientPlayNetworkHandler source) {
        if (instance != null) {
            instance.handleServerStatistics(MinecraftClient.getInstance(), source);
        }
    }

    private void onClientTick(MinecraftClient client) {
        handleCraftStatsTimeout(client);
        if (!QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()) {
            stopHelperSafely(client);
            reset(client);
            return;
        }
        if (!isWorkbenchOpen(client)) {
            stopHelperSafely(client);
            reset(client);
            return;
        }

        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;
        handleHotkey(client);
        if (active && startedByButton && !isButtonRapidModeHeld(client)) {
            requestAckStopOrStop(client);
            return;
        }
        if (!active) {
            return;
        }
        processAckPipelineTick(client, handler);
    }

    private boolean handleCursorBoundary(CraftingScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            occupiedCursorTicks = 0;
            return false;
        }

        occupiedCursorTicks++;
        if (occupiedCursorTicks == 1) {
            consecutiveFailures = 0;
        }
        if (shouldRecoverShulkerCursor(occupiedCursorTicks, sessionCursorSettleTicks)
                && QuickCraftWorkbenchShulker.recoverShulkerCraftCursor(handler)) {
            occupiedCursorTicks = 0;
            serverCorrectionPauseTicks = sessionRecoveryPauseTicks;
            consecutiveFailures = 0;
            return true;
        }

        if (!shouldStopForOccupiedCursor(occupiedCursorTicks, sessionCursorTimeoutTicks)) {
            return true;
        }

        stop(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
        return true;
    }

    static boolean shouldRecoverShulkerCursor(int occupiedTicks, int settleTicks) {
        return occupiedTicks >= settleTicks;
    }

    static boolean shouldStopForOccupiedCursor(int occupiedTicks, int timeoutTicks) {
        return occupiedTicks >= timeoutTicks;
    }

    private boolean isAckPipelineEnabled() {
        return active;
    }

    static boolean combinesRefillAndOutput(WorkbenchShulkerPipelineMode mode) {
        return mode == WorkbenchShulkerPipelineMode.COMBINED_ULTRA;
    }

    public static void onWorkbenchClickSent(int syncId) {
        if (instance != null && instance.active && instance.ackBatchRecording
                && instance.snapshotSyncId == syncId) {
            instance.ackBatchClickCount++;
        }
    }

    public static void onServerContainerUpdate(int syncId,
                                               int revision,
                                               boolean fullInventory) {
        if (instance != null) {
            instance.handleServerContainerUpdate(syncId, revision, fullInventory);
        }
    }

    private void processAckPipelineTick(MinecraftClient client, CraftingScreenHandler handler) {
        if (ackBatchAwaiting) {
            long now = System.nanoTime();
            if (ackStatsProbePending) {
                long probeWaitNanos = Math.max(0L, now - ackStatsProbeSentAtNanos);
                if (client.getNetworkHandler() != ackStatsProbeNetworkHandler
                        || hasAckStatsProbeTimedOut(probeWaitNanos)) {
                    clearAckStatsProbe();
                    stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
                }
                return;
            }
            long quietNanos = now - ackBatchLastProgressAtNanos;
            boolean exactStateMatches = handlerMatchesAckExpected(handler);
            boolean terminalStateSafe = isAckBatchTerminalStateSafe(handler);
            boolean revisionAdvanced = ackBatchLastRevision != ackBatchStartRevision;
            boolean settled;
            if (ackBatchKind == AckBatchKind.OUTPUT) {
                settled = shouldConfirmSettledSingleOutput(ackBatchClickCount,
                        revisionAdvanced, exactStateMatches, terminalStateSafe, quietNanos);
            } else if (requiresCompleteAckState(ackBatchKind, ackBatchSourceBatches)) {
                settled = false;
            } else {
                settled = shouldConfirmSettledNonOutputBatch(
                        revisionAdvanced, terminalStateSafe, quietNanos);
            }
            if (settled) {
                completeAckBatch(client, handler, now);
                return;
            }
            long timeoutMillis = ackStallTimeoutMillis(
                    sessionCursorTimeoutTicks, nanosToMillis(sessionAckMaxRegularLatencyNanos));
            long stalledMillis = quietNanos / 1_000_000L;
            if (stalledMillis >= timeoutMillis) {
                if (shouldRequestAckStatsProbe(ackBatchKind, ackBatchSourceBatches,
                        craftStatsBaselinePending, craftStatsBaselineAvailable,
                        ackStatsProbePending, stalledMillis, timeoutMillis)
                        && requestAckStatsProbe(client, handler, now, timeoutMillis)) {
                    return;
                }
                stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            }
            return;
        }
        if (ackStopRequested || !isRapidInputHeld(client)) {
            stop(client, getAckStopMessage());
            return;
        }
        if (handleCursorBoundary(handler)) {
            return;
        }
        if (serverCorrectionPauseTicks > 0) {
            serverCorrectionPauseTicks--;
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> driveAckPipeline(client, handler));
    }

    private void driveAckPipeline(MinecraftClient client, CraftingScreenHandler handler) {
        if (!active || !isAckPipelineEnabled() || ackBatchAwaiting) {
            return;
        }
        if (ackStopRequested || !isRapidInputHeld(client)) {
            stop(client, getAckStopMessage());
            return;
        }

        for (int step = 0; step < MAX_ACK_LOCAL_STEPS && active && !ackBatchAwaiting; step++) {
            int outputClicksBefore = sessionOutputClicks;
            int sourceBatchesBefore = QuickCraftWorkbenchShulker.sessionSourceBatches();
            int failuresBefore = consecutiveFailures;
            boolean busyBefore = QuickCraftWorkbenchShulker.isShulkerCraftBusy();
            List<ItemStack> stateBefore = snapshotHandler(handler);
            ItemStack cursorBefore = handler.getCursorStack().copy();

            ackBatchRecording = true;
            ackBatchClickCount = 0;
            ackBatchStartRevision = handler.getRevision();
            try {
                if (busyBefore) {
                    QuickCraftWorkbenchShulker.tickShulkerCraft(client);
                    processRefillResult(client, handler);
                } else {
                    processCraftTick(client, handler);
                }
            } finally {
                ackBatchRecording = false;
            }
            if (!active) {
                return;
            }

            if (ackBatchClickCount > 0) {
                int sourceBatches = QuickCraftWorkbenchShulker.sessionSourceBatches()
                        - sourceBatchesBefore;
                int outputClicks = sessionOutputClicks - outputClicksBefore;
                AckBatchKind kind = classifyAckBatch(outputClicks, sourceBatches);
                awaitAckBatch(handler, kind, stateBefore, sourceBatches, outputClicks);
                return;
            }

            boolean progressed = sessionOutputClicks != outputClicksBefore
                    || QuickCraftWorkbenchShulker.sessionSourceBatches() != sourceBatchesBefore
                    || consecutiveFailures != failuresBefore
                    || QuickCraftWorkbenchShulker.isShulkerCraftBusy() != busyBefore
                    || !handlerMatchesSnapshot(handler, stateBefore, cursorBefore);
            if (!progressed || serverCorrectionPauseTicks > 0) {
                return;
            }
        }
    }

    private void awaitAckBatch(CraftingScreenHandler handler,
                               AckBatchKind kind,
                               List<ItemStack> initialSlots,
                               int sourceBatches,
                               int outputClicks) {
        ackBatchId++;
        ackBatchKind = kind;
        ackExpectedSlots = snapshotHandler(handler);
        ackExpectedSlotIds = collectAckRelevantSlots(initialSlots, ackExpectedSlots);
        ackExpectedCursor = handler.getCursorStack().copy();
        ackBatchSentAtNanos = System.nanoTime();
        ackBatchLastProgressAtNanos = ackBatchSentAtNanos;
        ackBatchLastRevision = ackBatchStartRevision;
        ackBatchFullInventoryUpdates = 0;
        ackBatchSourceBatches = sourceBatches;
        ackBatchOutputClicks = outputClicks;
        ackBatchUsedStatsProbe = false;
        ackBatchAwaiting = true;
    }

    private boolean requestAckStatsProbe(MinecraftClient client,
                                         CraftingScreenHandler handler,
                                         long now,
                                         long triggerMillis) {
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler == null) {
            return false;
        }
        ackStatsProbePending = true;
        ackStatsProbeBatchId = ackBatchId;
        ackStatsProbeNetworkHandler = networkHandler;
        ackStatsProbeSentAtNanos = now;
        ackBatchUsedStatsProbe = true;
        networkHandler.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        return true;
    }

    private void handleServerContainerUpdate(int syncId,
                                             int revision,
                                             boolean fullInventory) {
        if (!active || !isAckPipelineEnabled() || !ackBatchAwaiting
                || !isAckRelevantSyncId(syncId, snapshotSyncId)) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null
                || !(client.player.currentScreenHandler instanceof CraftingScreenHandler handler)
                || handler.syncId != snapshotSyncId) {
            return;
        }
        long now = System.nanoTime();
        if (syncId == snapshotSyncId && revision != ackBatchLastRevision) {
            ackBatchLastRevision = revision;
            ackBatchLastProgressAtNanos = now;
        }
        if (syncId == snapshotSyncId && fullInventory) {
            ackBatchFullInventoryUpdates++;
        }
        if (ackStatsProbePending) {
            return;
        }
        boolean exactStateMatches = handlerMatchesAckExpected(handler);
        boolean terminalStateSafe = isAckBatchTerminalStateSafe(handler);
        boolean confirmed;
        if (ackBatchKind == AckBatchKind.OUTPUT) {
            confirmed = shouldConfirmOutputAckBatch(ackBatchClickCount, ackBatchOutputClicks,
                    ackBatchFullInventoryUpdates, fullInventory, exactStateMatches, terminalStateSafe);
        } else if (requiresCompleteAckState(ackBatchKind, ackBatchSourceBatches)) {
            confirmed = shouldConfirmCompleteAckBatch(
                    ackBatchFullInventoryUpdates, exactStateMatches, terminalStateSafe);
        } else {
            confirmed = shouldConfirmAckBatch(ackBatchClickCount, fullInventory,
                    exactStateMatches, terminalStateSafe, false);
        }
        if (!confirmed) {
            return;
        }

        completeAckBatch(client, handler, now);
    }

    private void completeAckBatch(MinecraftClient client,
                                  CraftingScreenHandler handler,
                                  long now) {
        long latencyNanos = Math.max(0L, now - ackBatchSentAtNanos);
        if (!ackBatchUsedStatsProbe) {
            sessionAckMaxRegularLatencyNanos = Math.max(sessionAckMaxRegularLatencyNanos, latencyNanos);
        }
        ackBatchAwaiting = false;

        ackExpectedSlots = List.of();
        ackExpectedSlotIds = List.of();
        ackExpectedCursor = ItemStack.EMPTY;
        if (ackStopRequested || !isRapidInputHeld(client)) {
            stop(client, Text.translatable("quickcraft.message.crafting.stopped"));
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> driveAckPipeline(client, handler));
    }

    static boolean isAckRelevantSyncId(int syncId, int workbenchSyncId) {
        return syncId == workbenchSyncId || syncId == -1 || syncId == -2;
    }

    static AckBatchKind classifyAckBatch(int outputClicks, int sourceBatches) {
        if (outputClicks > 0) {
            return sourceBatches > 0 ? AckBatchKind.REFILL_OUTPUT : AckBatchKind.OUTPUT;
        }
        return sourceBatches > 0 ? AckBatchKind.REFILL : AckBatchKind.PREPARATION;
    }

    static boolean includesOutput(AckBatchKind kind) {
        return kind == AckBatchKind.OUTPUT || kind == AckBatchKind.REFILL_OUTPUT;
    }

    static boolean requiresCompleteAckState(AckBatchKind kind, int sourceBatches) {
        return includesOutput(kind) || sourceBatches > 0;
    }

    static boolean shouldConfirmCompleteAckBatch(int fullInventoryUpdates,
                                                 boolean exactStateMatches,
                                                 boolean terminalStateSafe) {
        return fullInventoryUpdates > 0 && exactStateMatches && terminalStateSafe;
    }

    static boolean shouldConfirmAckBatch(int clickCount,
                                         boolean fullInventory,
                                         boolean exactStateMatches,
                                         boolean terminalStateSafe,
                                         boolean requireExactState) {
        if (clickCount > 1 && !fullInventory) {
            return false;
        }
        return requireExactState ? exactStateMatches : terminalStateSafe;
    }

    static boolean shouldConfirmOutputAckBatch(int batchClickCount,
                                               int outputClickCount,
                                               int fullInventoryUpdates,
                                               boolean fullInventory,
                                               boolean exactStateMatches,
                                               boolean terminalStateSafe) {
        if (outputClickCount <= 0 || batchClickCount < outputClickCount) {
            return false;
        }
        if (batchClickCount > outputClickCount) {
            return fullInventoryUpdates >= outputClickCount
                    && exactStateMatches
                    && terminalStateSafe;
        }
        if (outputClickCount == 1) {
            return terminalStateSafe && fullInventory;
        }
        return fullInventory
                && fullInventoryUpdates >= outputClickCount - 1
                && exactStateMatches
                && terminalStateSafe;
    }

    static boolean shouldConfirmSettledSingleOutput(int clickCount,
                                                    boolean revisionAdvanced,
                                                    boolean exactStateMatches,
                                                    boolean terminalStateSafe,
                                                    long quietNanos) {
        return clickCount <= 1
                && revisionAdvanced
                && exactStateMatches
                && terminalStateSafe
                && quietNanos >= 5_000_000L;
    }

    static boolean shouldConfirmSettledNonOutputBatch(boolean revisionAdvanced,
                                                      boolean terminalStateSafe,
                                                      long quietNanos) {
        return revisionAdvanced
                && terminalStateSafe
                && quietNanos >= 5_000_000L;
    }

    static long ackStallTimeoutMillis(int configuredTimeoutTicks, long observedMaxAckMillis) {
        long configuredMillis = Math.max(0, configuredTimeoutTicks) * 50L;
        long adaptiveMillis = Math.max(0L, observedMaxAckMillis) * 8L;
        return Math.max(3_000L, Math.max(configuredMillis, adaptiveMillis));
    }

    static boolean shouldRequestAckStatsProbe(AckBatchKind kind,
                                              int sourceBatches,
                                              boolean baselinePending,
                                              boolean baselineAvailable,
                                              boolean probePending,
                                              long stalledMillis,
                                              long timeoutMillis) {
        return requiresCompleteAckState(kind, sourceBatches)
                && !baselinePending
                && baselineAvailable
                && !probePending
                && stalledMillis >= timeoutMillis;
    }

    static boolean isCurrentAckStatsProbe(boolean probePending,
                                          long probeBatchId,
                                          long currentBatchId,
                                          boolean batchAwaiting,
                                          boolean sameNetworkHandler) {
        return probePending
                && probeBatchId == currentBatchId
                && batchAwaiting
                && sameNetworkHandler;
    }

    static boolean shouldConfirmAckStatsProbe(boolean currentProbe,
                                              boolean terminalStateSafe) {
        return currentProbe && terminalStateSafe;
    }

    static boolean shouldDeferStopForAck(boolean pipelineEnabled,
                                         boolean batchAwaiting,
                                         boolean batchRecording,
                                         int recordedClicks) {
        return pipelineEnabled
                && (batchAwaiting || (batchRecording && recordedClicks > 0));
    }

    static boolean hasAckStatsProbeTimedOut(long elapsedNanos) {
        return elapsedNanos >= ACK_STATS_PROBE_TIMEOUT_NANOS;
    }

    private List<ItemStack> snapshotHandler(CraftingScreenHandler handler) {
        List<ItemStack> snapshot = new ArrayList<>(handler.slots.size());
        for (Slot slot : handler.slots) {
            snapshot.add(slot.getStack().copy());
        }
        return snapshot;
    }

    private boolean handlerMatchesSnapshot(CraftingScreenHandler handler,
                                           List<ItemStack> expectedSlots,
                                           ItemStack expectedCursor) {
        if (expectedSlots.size() != handler.slots.size()
                || !ItemStack.areEqual(expectedCursor, handler.getCursorStack())) {
            return false;
        }
        for (int slotId = 0; slotId < expectedSlots.size(); slotId++) {
            if (!ItemStack.areEqual(expectedSlots.get(slotId), handler.getSlot(slotId).getStack())) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> collectAckRelevantSlots(List<ItemStack> initialSlots,
                                                  List<ItemStack> expectedSlots) {
        List<Integer> slotIds = new ArrayList<>();
        for (int slotId = 0; slotId < expectedSlots.size(); slotId++) {
            if (slotId <= GRID_END
                    || slotId >= initialSlots.size()
                    || !ItemStack.areEqual(initialSlots.get(slotId), expectedSlots.get(slotId))) {
                slotIds.add(slotId);
            }
        }
        return slotIds;
    }

    private boolean handlerMatchesAckExpected(CraftingScreenHandler handler) {
        if (ackExpectedSlots.size() != handler.slots.size()
                || !ItemStack.areEqual(ackExpectedCursor, handler.getCursorStack())) {
            return false;
        }
        for (int slotId : ackExpectedSlotIds) {
            if (!ItemStack.areEqual(ackExpectedSlots.get(slotId), handler.getSlot(slotId).getStack())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAckBatchTerminalStateSafe(CraftingScreenHandler handler) {
        if (!isAckGridAndCursorSafe(handler)) {
            return false;
        }
        if (!includesOutput(ackBatchKind)) {
            return true;
        }
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        return output.isEmpty() || isExpectedOutput(output);
    }

    private boolean isAckGridAndCursorSafe(CraftingScreenHandler handler) {
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }
        return isPatternGridCompatible(handler);
    }

    private boolean isPatternGridCompatible(CraftingScreenHandler handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            ItemStack actual = handler.getSlot(GRID_START + patternIndex).getStack();
            if (expected.isEmpty()) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else if (!actual.isEmpty()
                    && !ItemStack.areItemsAndComponentsEqual(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private void requestAckStopOrStop(MinecraftClient client) {
        requestAckStopOrStop(client, Text.translatable("quickcraft.message.crafting.stopped"));
    }

    private void requestAckStopOrStop(MinecraftClient client, Text message) {
        if (shouldDeferStopForAck(isAckPipelineEnabled(), ackBatchAwaiting,
                ackBatchRecording, ackBatchClickCount)) {
            if (!ackStopRequested) {
                ackStopRequested = true;
                ackStopMessage = message;
            }
            return;
        }
        stop(client, message);
    }

    private Text getAckStopMessage() {
        return ackStopRequested && ackStopMessage != null
                ? ackStopMessage
                : Text.translatable("quickcraft.message.crafting.stopped");
    }

    private void clearAckSession() {
        ackBatchRecording = false;
        ackBatchAwaiting = false;
        ackStopRequested = false;
        ackStopMessage = null;
        ackBatchClickCount = 0;
        ackBatchStartRevision = 0;
        ackBatchId = 0L;
        ackBatchSentAtNanos = 0L;
        ackBatchLastProgressAtNanos = 0L;
        ackBatchLastRevision = 0;
        ackBatchFullInventoryUpdates = 0;
        ackBatchSourceBatches = 0;
        ackBatchOutputClicks = 0;
        ackBatchKind = null;
        ackBatchUsedStatsProbe = false;
        ackExpectedSlots = List.of();
        ackExpectedSlotIds = List.of();
        ackExpectedCursor = ItemStack.EMPTY;
        clearAckStatsProbe();
        sessionAckMaxRegularLatencyNanos = 0L;
    }

    private void clearAckStatsProbe() {
        ackStatsProbePending = false;
        ackStatsProbeBatchId = 0L;
        ackStatsProbeNetworkHandler = null;
        ackStatsProbeSentAtNanos = 0L;
    }

    private static long nanosToMillis(long nanos) {
        return nanos <= 0L ? 0L : nanos / 1_000_000L;
    }

    private void processCraftTick(MinecraftClient client, CraftingScreenHandler handler) {
        if (waitForServerOutputCorrection(client, handler)) {
            return;
        }
        if (hasMissingPerCraftMaterial(handler)) {
            int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
            if (rebalancedItems > 0) {
                consecutiveFailures = 0;
                return;
            }
            int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
            if (movedLooseItems > 0) {
                primeOutputLocally(client, handler);
                consecutiveFailures = 0;
                return;
            }
            QuickCraftWorkbenchShulker.RefillStart refill =
                    QuickCraftWorkbenchShulker.beginShulkerCraftRefill(handler, pattern);
            if (!handleRefillStart(client, handler, refill)) {
                recordProgressOrStop(client, false);
            }
            return;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()
                && isExpectedOutput(handler.getSlot(OUTPUT_SLOT).getStack())) {
            boolean progressed = drainOutputBurst(client, handler);
            recordProgressOrStop(client, progressed);
            return;
        }

        if (primeOutputLocally(client, handler)) {
            consecutiveFailures = 0;
            return;
        }
        int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
        if (rebalancedItems > 0) {
            consecutiveFailures = 0;
            return;
        }
        int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
        if (movedLooseItems > 0) {
            primeOutputLocally(client, handler);
            consecutiveFailures = 0;
            return;
        }

        QuickCraftWorkbenchShulker.RefillStart refill =
                QuickCraftWorkbenchShulker.beginShulkerCraftRefill(handler, pattern);
        if (handleRefillStart(client, handler, refill)) {
            return;
        }
        recordProgressOrStop(client, false);
    }

    private boolean handleRefillStart(MinecraftClient client,
                                      CraftingScreenHandler handler,
                                      QuickCraftWorkbenchShulker.RefillStart refill) {
        if (refill == QuickCraftWorkbenchShulker.RefillStart.STARTED) {
            runFirstRefillActionImmediately(client, handler);
            return true;
        }
        if (refill == QuickCraftWorkbenchShulker.RefillStart.RECOVERED_DESYNC) {
            consecutiveFailures = 0;
            return true;
        }
        if (refill == QuickCraftWorkbenchShulker.RefillStart.GRID_MISMATCH) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            return true;
        }
        return false;
    }

    private void runFirstRefillActionImmediately(MinecraftClient client,
                                                 CraftingScreenHandler handler) {
        consecutiveFailures = 0;
        QuickCraftWorkbenchShulker.tickShulkerCraft(client);
        processRefillResult(client, handler);
    }

    private boolean throwOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
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
            sessionOutputClicks++;
        }
        return completed > 0;
    }

    private boolean storeOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
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
                    requestAckStopOrStop(client,
                            Text.translatable("quickcraft.message.crafting.no_output_shulker"));
                    return completed > 0;
                }
            }
            if (!QuickCraftWorkbenchShulkerOutput.storeOnce(client, handler, output)) {
                boolean returned = QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot);
                if (returned) {
                    requestAckStopOrStop(client,
                            Text.translatable("quickcraft.message.crafting.shulker_output_failed"));
                } else {
                    stop(client, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                }
                return completed > 0;
            }
            completed++;
            sessionOutputClicks++;
        }
        if (!QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot)) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
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
            return;
        }
        if (!combinesRefillAndOutput(sessionMode)) {
            consecutiveFailures = 0;
            return;
        }
        if (!active) {
            return;
        }
        boolean progressed = drainOutputBurst(client, handler);
        recordProgressOrStop(client, progressed);
    }

    private boolean drainOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
        return sessionOutputToShulker
                ? storeOutputBurst(client, handler)
                : throwOutputBurst(client, handler);
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
        if (active || craftStatsBaselinePending) {
            return false;
        }
        boolean workbenchOpen = isWorkbenchOpen(client);
        boolean available = QuickCraftWorkbenchShulker.isAvailable();
        if (!workbenchOpen || !available) {
            sendMessage(client, Text.translatable("quickcraft.message.crafting.shulker_unavailable"));
            return false;
        }
        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;
        RecipeEntry<CraftingRecipe> currentRecipe = findCurrentRecipe(client, handler);
        boolean capturedVisibleRecipe = handler.getSlot(OUTPUT_SLOT).hasStack();
        boolean canReuseSnapshot = canReuseSnapshot(handler.syncId, snapshotSyncId,
                !pattern.isEmpty() && !resultTemplate.isEmpty());
        if (!canStartWithRecipeState(capturedVisibleRecipe, canReuseSnapshot)) {
            sendMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }
        if (capturedVisibleRecipe) {
            if (hasRemainder(currentRecipe, handler)) {
                sendMessage(client, Text.translatable("quickcraft.message.crafting.shulker_recipe_remainder"));
                return false;
            }
            recipe = currentRecipe;
            pattern = snapshotPattern(handler);
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
            snapshotSyncId = handler.syncId;
        }
        active = true;
        startedByButton = fromButton;
        consecutiveFailures = 0;
        sessionOutputClicks = 0;
        sessionOutputToShulker = QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled();
        sessionMode = QuickCraftConfigs.getWorkbenchQuickShulkerPipelineMode();
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorSettleTicks = CURSOR_SETTLE_TICKS;
        sessionRecoveryPauseTicks = RECOVERY_PAUSE_TICKS;
        sessionCursorTimeoutTicks = CURSOR_TIMEOUT_TICKS;
        serverOutputMismatchTicks = 0;
        craftStatsBaselinePending = true;
        craftStatsBaselineNetworkHandler = client.getNetworkHandler();
        craftStatsBaselineRequestedAtNanos = System.nanoTime();
        craftStatsBaselineAvailable = false;
        clearAckSession();
        if (!requestServerStats(client)) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
        }
        beginSessionImmediately(client);
        return true;
    }

    private boolean requestServerStats(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return false;
        }
        client.getNetworkHandler().sendPacket(
                new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        return true;
    }

    private void beginSessionImmediately(MinecraftClient client) {
        if (!active || !isWorkbenchOpen(client)
                || !(client.player.currentScreenHandler instanceof CraftingScreenHandler handler)
                || handler.syncId != snapshotSyncId) {
            active = false;
            return;
        }
        QuickCraftWorkbenchShulker.beginSession(sessionMode);
        sendMessage(client, Text.translatable("quickcraft.message.crafting.started"));
    }

    private void handleServerStatistics(MinecraftClient client,
                                        ClientPlayNetworkHandler source) {
        if (client == null || source == null || client.getNetworkHandler() != source) {
            discardStatsBarrierForDisconnectedHandler(source);
            return;
        }
        if (craftStatsBaselinePending) {
            if (source != craftStatsBaselineNetworkHandler) {
                return;
            }
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            craftStatsBaselineAvailable = true;
            return;
        }
        handleAckStatsProbeResponse(client, source);
    }

    private boolean handleAckStatsProbeResponse(MinecraftClient client,
                                                ClientPlayNetworkHandler source) {
        if (!ackStatsProbePending || source != ackStatsProbeNetworkHandler) {
            return false;
        }
        long now = System.nanoTime();
        long probeBatchId = ackStatsProbeBatchId;
        boolean currentProbe = isCurrentAckStatsProbe(
                ackStatsProbePending, probeBatchId, ackBatchId,
                active && ackBatchAwaiting, source == ackStatsProbeNetworkHandler);
        clearAckStatsProbe();

        if (!currentProbe
                || client.player == null
                || !(client.player.currentScreenHandler instanceof CraftingScreenHandler handler)
                || handler.syncId != snapshotSyncId) {
            if (active) {
                stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            }
            return true;
        }

        boolean terminalStateSafe = isAckBatchTerminalStateSafe(handler);
        if (!shouldConfirmAckStatsProbe(currentProbe, terminalStateSafe)) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            return true;
        }

        completeAckBatch(client, handler, now);
        return true;
    }

    private void handleCraftStatsTimeout(MinecraftClient client) {
        long now = System.nanoTime();
        ClientPlayNetworkHandler currentNetworkHandler = client == null
                ? null : client.getNetworkHandler();
        if (craftStatsBaselinePending
                && currentNetworkHandler != craftStatsBaselineNetworkHandler) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            active = false;
        }
        if (craftStatsBaselinePending
                && now - craftStatsBaselineRequestedAtNanos >= SERVER_STATS_TIMEOUT_NANOS) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
        }
    }

    private void discardStatsBarrierForDisconnectedHandler(ClientPlayNetworkHandler source) {
        if (craftStatsBaselinePending && source == craftStatsBaselineNetworkHandler) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            active = false;
        }
        if (ackStatsProbePending && source == ackStatsProbeNetworkHandler) {
            clearAckStatsProbe();
            active = false;
        }
    }

    private boolean primeOutputLocally(MinecraftClient client, CraftingScreenHandler handler) {
        if (client.world == null || resultTemplate.isEmpty()) {
            return false;
        }
        try {
            CraftingRecipeInput input = createInput(handler);
            ItemStack result;
            if (recipe != null) {
                if (!recipe.value().matches(input, client.world)) {
                    return false;
                }
                result = recipe.value().craft(input, client.world.getRegistryManager());
            } else {
                if (!isPatternShapeComplete(handler)) {
                    return false;
                }
                result = resultTemplate.copy();
            }
            if (!isExpectedOutput(result)) {
                return false;
            }
            Slot outputSlot = handler.getSlot(OUTPUT_SLOT);
            if (!(outputSlot.inventory instanceof CraftingResultInventory resultInventory)) {
                return false;
            }
            if (recipe != null) {
                resultInventory.setLastRecipe(recipe);
            }
            resultInventory.setStack(outputSlot.getIndex(), result.copy());
            return isExpectedOutput(outputSlot.getStack());
        } catch (Throwable throwable) {
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
        if (client == null || client.world == null
                || !(client.world.getRecipeManager() instanceof ServerRecipeManager recipeManager)) {
            return null;
        }
        try {
            Optional<RecipeEntry<CraftingRecipe>> match = recipeManager.getFirstMatch(
                    RecipeType.CRAFTING, createInput(handler), client.world);
            return match.orElse(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean hasRemainder(RecipeEntry<CraftingRecipe> currentRecipe,
                                 CraftingScreenHandler handler) {
        try {
            CraftingRecipeInput input = createInput(handler);
            List<ItemStack> remainders = currentRecipe != null
                    ? currentRecipe.value().getRecipeRemainders(input)
                    : CraftingRecipe.collectRecipeRemainders(input);
            for (ItemStack remainder : remainders) {
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

    static boolean canStartWithRecipeState(boolean outputVisible, boolean snapshotReusable) {
        return outputVisible || snapshotReusable;
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

        int movedItems = 0;
        patternLoop:
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
                if (isAckPipelineEnabled()) {
                    break patternLoop;
                }
            }
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
            if (isAckPipelineEnabled() && movedItems > 0) {
                break;
            }
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
                    if (isAckPipelineEnabled()) {
                        return movedItems;
                    }
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
        for (int inventoryIndex = 0; inventoryIndex < inventory.getMainStacks().size(); inventoryIndex++) {
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
            requestAckStopOrStop(client);
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

    private void reset(MinecraftClient client) {
        if (active) {
            active = false;
        }
        active = false;
        craftStatsBaselinePending = false;
        craftStatsBaselineNetworkHandler = null;
        craftStatsBaselineRequestedAtNanos = 0L;
        craftStatsBaselineAvailable = false;
        startedByButton = false;
        consecutiveFailures = 0;
        recipe = null;
        pattern = List.of();
        resultTemplate = ItemStack.EMPTY;
        snapshotSyncId = -1;
        lastSingleKeyDown = false;
        lastRapidKeyDown = false;
        sessionOutputClicks = 0;
        sessionOutputToShulker = false;
        sessionMode = WorkbenchShulkerPipelineMode.RESPONSE_STABLE;
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorSettleTicks = 0;
        sessionRecoveryPauseTicks = 0;
        sessionCursorTimeoutTicks = 0;
        serverOutputMismatchTicks = 0;
        clearAckSession();
        QuickCraftWorkbenchShulker.clearSession();
        QuickCraftWorkbenchShulker.resetShulkerCraft();
    }

    private boolean waitForServerOutputCorrection(MinecraftClient client,
                                                   CraftingScreenHandler handler) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        if (output.isEmpty() || isExpectedOutput(output)) {
            serverOutputMismatchTicks = 0;
            return false;
        }

        boolean patternShapeComplete = isPatternShapeComplete(handler);
        if (!patternShapeComplete) {
            serverOutputMismatchTicks = 0;
            if (canContinuePastIntermediateOutput(
                    patternShapeComplete, isPatternGridCompatible(handler))) {
                return false;
            }
            serverCorrectionPauseTicks = Math.max(serverCorrectionPauseTicks, 1);
            return true;
        }

        serverOutputMismatchTicks++;
        if (serverOutputMismatchTicks <= Math.max(1, sessionCursorSettleTicks)) {
            serverCorrectionPauseTicks = Math.max(serverCorrectionPauseTicks, 1);
            return true;
        }

        stop(client, Text.translatable("quickcraft.message.crafting.shulker_grid_desync"));
        return true;
    }

    static boolean canContinuePastIntermediateOutput(boolean patternShapeComplete,
                                                     boolean patternGridCompatible) {
        return !patternShapeComplete && patternGridCompatible;
    }

    private boolean isPatternShapeComplete(CraftingScreenHandler handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            ItemStack actual = handler.getSlot(GRID_START + patternIndex).getStack();
            if (expected.isEmpty()) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else if (actual.isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private void sendMessage(MinecraftClient client, Text message) {
        if (message != null && client != null && client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    enum AckBatchKind {
        REFILL,
        REFILL_OUTPUT,
        OUTPUT,
        PREPARATION
    }
}
