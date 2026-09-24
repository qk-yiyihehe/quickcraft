package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs.WorkbenchShulkerPipelineMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.stats.Stats;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
    private RecipeHolder<CraftingRecipe> recipe;
    private List<ItemStack> pattern = List.of();
    private List<ItemStack> remainderPattern = List.of();
    private boolean recipeHasRemainder;
    private ItemStack resultTemplate = ItemStack.EMPTY;
    private int snapshotSyncId = -1;
    private long sessionStartedAtNanos;
    private int sessionOutputClicks;
    private int sessionOutputBursts;
    private boolean sessionOutputToShulker;
    private boolean sessionRequiresOrderedProbe;
    private int sessionOutputBoxTakes;
    private int sessionOutputBoxReturns;
    private int sessionOutputBoxSwitches;
    private int sessionOutputBoxMisses;
    private int sessionOutputStoreFailures;
    private WorkbenchShulkerPipelineMode sessionMode = WorkbenchShulkerPipelineMode.RESPONSE_STABLE;
    private int serverCorrectionPauseTicks;
    private int occupiedCursorTicks;
    private int sessionCursorWaitEvents;
    private int sessionCursorWaitTicks;
    private int sessionCursorRecoveries;
    private int sessionCorrectionPauseTicks;
    private int sessionCursorSettleTicks;
    private int sessionRecoveryPauseTicks;
    private int sessionCursorTimeoutTicks;
    private boolean sessionOutputMismatchLogged;
    private int serverOutputMismatchTicks;
    private boolean ackBatchRecording;
    private boolean ackBatchAwaiting;
    private boolean ackStopRequested;
    private Component ackStopMessage;
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
    private ClientPacketListener ackStatsProbeNetworkHandler;
    private long ackStatsProbeSentAtNanos;
    private int sessionAckBatches;
    private int sessionAckConfirmedBatches;
    private int sessionAckReconciledBatches;
    private int sessionAckClicks;
    private int sessionAckTimeouts;
    private long sessionAckLatencyNanos;
    private long sessionAckMinLatencyNanos;
    private long sessionAckMaxLatencyNanos;
    private long sessionAckMaxRegularLatencyNanos;
    private int sessionAckRefillBatches;
    private int sessionAckRefillSourceBatches;
    private int sessionAckCombinedBatches;
    private int sessionAckStatsProbesSent;
    private int sessionAckStatsProbesCompleted;
    private int sessionAckStatsProbeTimeouts;
    private long sessionAckStatsProbeLatencyNanos;
    private long ackWindowStartedAtNanos;
    private int ackWindowConfirmedBatches;
    private int ackWindowClicks;
    private long ackWindowLatencyNanos;
    private long ackWindowMinLatencyNanos;
    private long ackWindowMaxLatencyNanos;
    private boolean craftStatsBaselinePending;
    private ClientPacketListener craftStatsBaselineNetworkHandler;
    private long craftStatsBaselineRequestedAtNanos;
    private long sessionCraftStatsBaselineWaitNanos;
    private int sessionCraftedStatBaseline = -1;
    private PendingCraftStats pendingCraftStats;

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
        return instance.start(Minecraft.getInstance(), true);
    }

    public static boolean shouldSuppressRecipeGhostSlots() {
        return instance != null && (instance.active || QuickCraftWorkbenchShulker.isShulkerCraftBusy());
    }

    public static void onServerStatistics(ClientPacketListener source) {
        if (instance != null) {
            instance.handleServerStatistics(Minecraft.getInstance(), source);
        }
    }

    private void onClientTick(Minecraft client) {
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

        CraftingMenu handler = (CraftingMenu) client.player.containerMenu;
        handleHotkey(client);
        if (active && startedByButton && !isButtonRapidModeHeld(client)) {
            requestAckStopOrStop(client);
            return;
        }
        if (!active) {
            return;
        }
        QuickCraftWorkbenchShulkerTelemetry.onClientTick(handler);
        processAckPipelineTick(client, handler);
    }

    private boolean handleCursorBoundary(CraftingMenu handler) {
        ItemStack cursor = handler.getCarried();
        if (cursor.isEmpty()) {
            if (occupiedCursorTicks > 0) {
                LOGGER.debug("服务端同步后光标已清空：会话t+{} ms，等待={} Tick，revision={}",
                        sessionElapsedMillis(), occupiedCursorTicks, handler.getStateId());
            }
            occupiedCursorTicks = 0;
            return false;
        }

        occupiedCursorTicks++;
        sessionCursorWaitTicks++;
        if (occupiedCursorTicks == 1) {
            sessionCursorWaitEvents++;
            consecutiveFailures = 0;
            LOGGER.debug("持续合成等待服务端光标最终态：会话t+{} ms，光标={}，revision={}，containerId={}，"
                            + "潜影盒恢复等待={} Tick，最长等待={} Tick",
                    sessionElapsedMillis(), describeStack(cursor), handler.getStateId(), handler.containerId,
                    sessionCursorSettleTicks, sessionCursorTimeoutTicks);
        }
        if (shouldRecoverShulkerCursor(occupiedCursorTicks, sessionCursorSettleTicks)
                && QuickCraftWorkbenchShulker.recoverShulkerCraftCursor(handler)) {
            LOGGER.warn("持续合成检测到服务端回退潜影盒：会话t+{} ms，已安全放回并暂停={} Tick，"
                            + "检测前等待={} Tick，revision={}，containerId={}",
                    sessionElapsedMillis(), sessionRecoveryPauseTicks,
                    occupiedCursorTicks, handler.getStateId(), handler.containerId);
            occupiedCursorTicks = 0;
            serverCorrectionPauseTicks = sessionRecoveryPauseTicks;
            sessionCursorRecoveries++;
            consecutiveFailures = 0;
            return true;
        }

        if (!shouldStopForOccupiedCursor(occupiedCursorTicks, sessionCursorTimeoutTicks)) {
            return true;
        }

        LOGGER.error("持续合成光标同步超时：会话t+{} ms，光标={}，等待={} Tick，revision={}，containerId={}",
                sessionElapsedMillis(), describeStack(cursor), occupiedCursorTicks,
                handler.getStateId(), handler.containerId);
        stop(Minecraft.getInstance(), Component.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
        return true;
    }

    static boolean shouldRecoverShulkerCursor(int occupiedTicks, int settleTicks) {
        return occupiedTicks >= settleTicks;
    }

    static boolean shouldStopForOccupiedCursor(int occupiedTicks, int timeoutTicks) {
        return occupiedTicks >= timeoutTicks;
    }

    static double craftsPerSecond(int crafted, long elapsedMillis) {
        if (crafted <= 0 || elapsedMillis <= 0L) {
            return 0.0D;
        }
        return Math.round(crafted * 100_000.0D / elapsedMillis) / 100.0D;
    }

    private boolean isAckPipelineEnabled() {
        return active;
    }

    static boolean combinesRefillAndOutput(WorkbenchShulkerPipelineMode mode) {
        return mode == WorkbenchShulkerPipelineMode.COMBINED_ULTRA;
    }

    static String pipelineDescription(WorkbenchShulkerPipelineMode mode) {
        return switch (mode) {
            case RESPONSE_STABLE -> "每个来源盒单独确认/输出单独确认";
            case BALANCED -> "最多三个来源盒合并确认/输出单独确认";
            case COMBINED_ULTRA -> "最多三个来源盒+最多64次输出同批确认";
        };
    }

    public static void onWorkbenchClickSent(int containerId) {
        if (instance != null && instance.active && instance.ackBatchRecording
                && instance.snapshotSyncId == containerId) {
            instance.ackBatchClickCount++;
        }
    }

    public static void onServerContainerUpdate(int containerId,
                                               int revision,
                                               boolean fullInventory) {
        if (instance != null) {
            instance.handleServerContainerUpdate(containerId, revision, fullInventory);
        }
    }

    private void processAckPipelineTick(Minecraft client, CraftingMenu handler) {
        if (ackBatchAwaiting) {
            long now = System.nanoTime();
            if (ackStatsProbePending) {
                long probeWaitNanos = Math.max(0L, now - ackStatsProbeSentAtNanos);
                if (client.getConnection() != ackStatsProbeNetworkHandler
                        || hasAckStatsProbeTimedOut(probeWaitNanos)) {
                    sessionAckTimeouts++;
                    sessionAckStatsProbeTimeouts++;
                    LOGGER.error("确认驱动流水线静默探针超时：批次=#{}，类型={}，点击={}，探针等待={} ms，"
                                    + "总等待={} ms，连接一致={}，revision={}->{}, 差异={}，光标={}，合成格={}",
                            ackBatchId, ackBatchKind, ackBatchClickCount,
                            nanosToMillis(probeWaitNanos), nanosToMillis(now - ackBatchSentAtNanos),
                            client.getConnection() == ackStatsProbeNetworkHandler,
                            ackBatchStartRevision, handler.getStateId(), describeAckDifferences(handler),
                            describeStack(handler.getCarried()), describePattern(snapshotPattern(handler)));
                    clearAckStatsProbe();
                    stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
                }
                return;
            }
            if (shouldRequestImmediateAckStatsProbe(
                    sessionRequiresOrderedProbe,
                    ackBatchKind,
                    ackBatchSourceBatches,
                    craftStatsBaselinePending,
                    sessionCraftedStatBaseline >= 0,
                    ackStatsProbePending)
                    && requestAckStatsProbe(client, handler, now, 0L)) {
                return;
            }
            long quietNanos = now - ackBatchLastProgressAtNanos;
            boolean exactStateMatches = handlerMatchesAckExpected(handler);
            boolean terminalStateSafe = isAckBatchTerminalStateSafe(handler);
            boolean revisionAdvanced = ackBatchLastRevision != ackBatchStartRevision;
            if (shouldProbeReconciledOutput(ackBatchKind, ackBatchClickCount,
                    ackBatchOutputClicks, ackBatchFullInventoryUpdates,
                    exactStateMatches, terminalStateSafe, craftStatsBaselinePending,
                    sessionCraftedStatBaseline >= 0, ackStatsProbePending)
                    && requestAckStatsProbe(client, handler, now, 0L)) {
                return;
            }
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
                completeAckBatch(client, handler, "槽位更新收敛", handler.getStateId(),
                        exactStateMatches, now);
                return;
            }
            long timeoutMillis = ackStallTimeoutMillis(
                    sessionCursorTimeoutTicks, nanosToMillis(sessionAckMaxRegularLatencyNanos));
            long stalledMillis = quietNanos / 1_000_000L;
            if (stalledMillis >= timeoutMillis) {
                if (shouldRequestAckStatsProbe(ackBatchKind, ackBatchSourceBatches,
                        craftStatsBaselinePending, sessionCraftedStatBaseline >= 0,
                        ackStatsProbePending, stalledMillis, timeoutMillis)
                        && requestAckStatsProbe(client, handler, now, timeoutMillis)) {
                    return;
                }
                sessionAckTimeouts++;
                LOGGER.error("确认驱动流水线批次停滞：批次=#{}，类型={}，点击={}，总等待={} ms，无revision推进={} ms，"
                                + "起始revision={}，当前revision={}，安全边界={}，统计基线={}/{}，差异={}，光标={}，合成格={}",
                        ackBatchId, ackBatchKind, ackBatchClickCount,
                        (now - ackBatchSentAtNanos) / 1_000_000L, stalledMillis,
                        ackBatchStartRevision, handler.getStateId(), isAckBatchTerminalStateSafe(handler),
                        craftStatsBaselinePending ? "等待中" : "已结束",
                        sessionCraftedStatBaseline >= 0 ? "可用" : "不可用",
                        describeAckDifferences(handler),
                        describeStack(handler.getCarried()), describePattern(snapshotPattern(handler)));
                stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
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
            sessionCorrectionPauseTicks++;
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> driveAckPipeline(client, handler));
    }

    private void driveAckPipeline(Minecraft client, CraftingMenu handler) {
        if (!active || !isAckPipelineEnabled() || ackBatchAwaiting) {
            return;
        }
        if (ackStopRequested || !isRapidInputHeld(client)) {
            stop(client, getAckStopMessage());
            return;
        }

        for (int step = 0; step < MAX_ACK_LOCAL_STEPS && active && !ackBatchAwaiting; step++) {
            int outputClicksBefore = sessionOutputClicks;
            int sourceBatchesBefore = QuickCraftWorkbenchShulker.debugSessionSourceBatches();
            int failuresBefore = consecutiveFailures;
            boolean busyBefore = QuickCraftWorkbenchShulker.isShulkerCraftBusy();
            List<ItemStack> stateBefore = snapshotHandler(handler);
            ItemStack cursorBefore = handler.getCarried().copy();

            ackBatchRecording = true;
            ackBatchClickCount = 0;
            ackBatchStartRevision = handler.getStateId();
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
                int sourceBatches = QuickCraftWorkbenchShulker.debugSessionSourceBatches()
                        - sourceBatchesBefore;
                int outputClicks = sessionOutputClicks - outputClicksBefore;
                AckBatchKind kind = classifyAckBatch(outputClicks, sourceBatches);
                awaitAckBatch(handler, kind, stateBefore, sourceBatches, outputClicks);
                return;
            }

            boolean progressed = sessionOutputClicks != outputClicksBefore
                    || QuickCraftWorkbenchShulker.debugSessionSourceBatches() != sourceBatchesBefore
                    || consecutiveFailures != failuresBefore
                    || QuickCraftWorkbenchShulker.isShulkerCraftBusy() != busyBefore
                    || !handlerMatchesSnapshot(handler, stateBefore, cursorBefore);
            if (!progressed || serverCorrectionPauseTicks > 0) {
                return;
            }
        }
    }

    private void awaitAckBatch(CraftingMenu handler,
                               AckBatchKind kind,
                               List<ItemStack> initialSlots,
                               int sourceBatches,
                               int outputClicks) {
        ackBatchId++;
        ackBatchKind = kind;
        ackExpectedSlots = snapshotHandler(handler);
        ackExpectedSlotIds = collectAckRelevantSlots(initialSlots, ackExpectedSlots);
        ackExpectedCursor = handler.getCarried().copy();
        ackBatchSentAtNanos = System.nanoTime();
        ackBatchLastProgressAtNanos = ackBatchSentAtNanos;
        ackBatchLastRevision = ackBatchStartRevision;
        ackBatchFullInventoryUpdates = 0;
        ackBatchSourceBatches = sourceBatches;
        ackBatchOutputClicks = outputClicks;
        ackBatchUsedStatsProbe = false;
        ackBatchAwaiting = true;
        sessionAckBatches++;
        sessionAckClicks += ackBatchClickCount;
        if (includesRefill(kind)) {
            sessionAckRefillBatches++;
            sessionAckRefillSourceBatches += sourceBatches;
        }
        if (kind == AckBatchKind.REFILL_OUTPUT) {
            sessionAckCombinedBatches++;
        }
        LOGGER.debug("确认驱动流水线批次发送：批次=#{}，类型={}，来源盒={}，输出={}，点击={}，revision={}，"
                        + "会话t+{} ms，累计输出点击={}，合成格={}，光标={}",
                ackBatchId, kind, sourceBatches, outputClicks, ackBatchClickCount, ackBatchStartRevision,
                sessionElapsedMillis(), sessionOutputClicks, describePattern(snapshotPattern(handler)),
                describeStack(handler.getCarried()));
    }

    private boolean requestAckStatsProbe(Minecraft client,
                                         CraftingMenu handler,
                                         long now,
                                         long triggerMillis) {
        ClientPacketListener networkHandler = client.getConnection();
        if (networkHandler == null) {
            return false;
        }
        ackStatsProbePending = true;
        ackStatsProbeBatchId = ackBatchId;
        ackStatsProbeNetworkHandler = networkHandler;
        ackStatsProbeSentAtNanos = now;
        ackBatchUsedStatsProbe = true;
        sessionAckStatsProbesSent++;
        networkHandler.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        LOGGER.warn("确认驱动流水线静默探针发送：批次=#{}，类型={}，来源盒={}，输出={}，点击={}，"
                        + "触发等待={} ms，总等待={} ms，revision={}->{}, 安全边界={}，差异={}",
                ackBatchId, ackBatchKind, ackBatchSourceBatches, ackBatchOutputClicks,
                ackBatchClickCount, triggerMillis, nanosToMillis(now - ackBatchSentAtNanos),
                ackBatchStartRevision, handler.getStateId(), isAckBatchTerminalStateSafe(handler),
                describeAckDifferences(handler));
        return true;
    }

    private void handleServerContainerUpdate(int containerId,
                                             int revision,
                                             boolean fullInventory) {
        if (!active || !isAckPipelineEnabled() || !ackBatchAwaiting
                || !isAckRelevantSyncId(containerId, snapshotSyncId)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null
                || !(client.player.containerMenu instanceof CraftingMenu handler)
                || handler.containerId != snapshotSyncId) {
            return;
        }
        long now = System.nanoTime();
        if (containerId == snapshotSyncId && revision != ackBatchLastRevision) {
            ackBatchLastRevision = revision;
            ackBatchLastProgressAtNanos = now;
        }
        if (containerId == snapshotSyncId && fullInventory) {
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
            ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
            if (!output.isEmpty() && !isExpectedOutput(output)
                    && shouldProbeReconciledOutput(ackBatchKind, ackBatchClickCount,
                    ackBatchOutputClicks, ackBatchFullInventoryUpdates,
                    exactStateMatches, terminalStateSafe, craftStatsBaselinePending,
                    sessionCraftedStatBaseline >= 0, ackStatsProbePending)) {
                requestAckStatsProbe(client, handler, now, 0L);
            }
            return;
        }

        completeAckBatch(client, handler, fullInventory ? "全量库存" : "槽位更新",
                revision, exactStateMatches, now);
    }

    private void completeAckBatch(Minecraft client,
                                  CraftingMenu handler,
                                  String confirmation,
                                  int revision,
                                  boolean exactStateMatches,
                                  long now) {
        long latencyNanos = Math.max(0L, now - ackBatchSentAtNanos);
        sessionAckConfirmedBatches++;
        if (!exactStateMatches) {
            sessionAckReconciledBatches++;
        }
        sessionAckLatencyNanos += latencyNanos;
        sessionAckMinLatencyNanos = minNonZero(sessionAckMinLatencyNanos, latencyNanos);
        sessionAckMaxLatencyNanos = Math.max(sessionAckMaxLatencyNanos, latencyNanos);
        if (!ackBatchUsedStatsProbe) {
            sessionAckMaxRegularLatencyNanos = Math.max(sessionAckMaxRegularLatencyNanos, latencyNanos);
        }
        ackBatchAwaiting = false;
        recordAckWindow(latencyNanos, ackBatchClickCount, now);
        LOGGER.debug("确认驱动流水线批次完成：批次=#{}，类型={}，来源盒={}，输出={}，点击={}，全量={}，确认={}，状态={}，revision={}->{}, "
                        + "耗时={} us，会话t+{} ms",
                ackBatchId, ackBatchKind, ackBatchSourceBatches, ackBatchOutputClicks, ackBatchClickCount,
                ackBatchFullInventoryUpdates, confirmation,
                exactStateMatches ? "精确" : "服务端安全重算", ackBatchStartRevision, revision,
                latencyNanos / 1_000L, sessionElapsedMillis());

        ackExpectedSlots = List.of();
        ackExpectedSlotIds = List.of();
        ackExpectedCursor = ItemStack.EMPTY;
        if (ackStopRequested || !isRapidInputHeld(client)) {
            stop(client, Component.translatable("quickcraft.message.crafting.stopped"));
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> driveAckPipeline(client, handler));
    }

    static boolean isAckRelevantSyncId(int containerId, int workbenchSyncId) {
        return containerId == workbenchSyncId || containerId == -1 || containerId == -2;
    }

    static AckBatchKind classifyAckBatch(int outputClicks, int sourceBatches) {
        if (outputClicks > 0) {
            return sourceBatches > 0 ? AckBatchKind.REFILL_OUTPUT : AckBatchKind.OUTPUT;
        }
        return sourceBatches > 0 ? AckBatchKind.REFILL : AckBatchKind.PREPARATION;
    }

    static boolean includesRefill(AckBatchKind kind) {
        return kind == AckBatchKind.REFILL || kind == AckBatchKind.REFILL_OUTPUT;
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

    static boolean shouldRequestImmediateAckStatsProbe(boolean orderedProbeRecipe,
                                                       AckBatchKind kind,
                                                       int sourceBatches,
                                                       boolean baselinePending,
                                                       boolean baselineAvailable,
                                                       boolean probePending) {
        return orderedProbeRecipe
                && requiresCompleteAckState(kind, sourceBatches)
                && !baselinePending
                && baselineAvailable
                && !probePending;
    }

    static boolean shouldProbeReconciledOutput(AckBatchKind kind,
                                               int batchClickCount,
                                               int outputClickCount,
                                               int fullInventoryUpdates,
                                               boolean exactStateMatches,
                                               boolean terminalStateSafe,
                                               boolean baselinePending,
                                               boolean baselineAvailable,
                                               boolean probePending) {
        return includesOutput(kind)
                && outputClickCount > 1
                && batchClickCount >= outputClickCount
                && fullInventoryUpdates >= (batchClickCount > outputClickCount
                        ? outputClickCount : outputClickCount - 1)
                && !exactStateMatches
                && terminalStateSafe
                && !baselinePending
                && baselineAvailable
                && !probePending;
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

    private List<ItemStack> snapshotHandler(CraftingMenu handler) {
        List<ItemStack> snapshot = new ArrayList<>(handler.slots.size());
        for (Slot slot : handler.slots) {
            snapshot.add(slot.getItem().copy());
        }
        return snapshot;
    }

    private boolean handlerMatchesSnapshot(CraftingMenu handler,
                                           List<ItemStack> expectedSlots,
                                           ItemStack expectedCursor) {
        if (expectedSlots.size() != handler.slots.size()
                || !ItemStack.matches(expectedCursor, handler.getCarried())) {
            return false;
        }
        for (int slotId = 0; slotId < expectedSlots.size(); slotId++) {
            if (!ItemStack.matches(expectedSlots.get(slotId), handler.getSlot(slotId).getItem())) {
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
                    || !ItemStack.matches(initialSlots.get(slotId), expectedSlots.get(slotId))) {
                slotIds.add(slotId);
            }
        }
        return slotIds;
    }

    private boolean handlerMatchesAckExpected(CraftingMenu handler) {
        if (ackExpectedSlots.size() != handler.slots.size()
                || !ItemStack.matches(ackExpectedCursor, handler.getCarried())) {
            return false;
        }
        for (int slotId : ackExpectedSlotIds) {
            if (!ItemStack.matches(ackExpectedSlots.get(slotId), handler.getSlot(slotId).getItem())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAckBatchTerminalStateSafe(CraftingMenu handler) {
        if (!handler.getCarried().isEmpty()) {
            return false;
        }
        boolean patternGridCompatible = isPatternGridCompatible(handler);
        if (!patternGridCompatible) {
            return false;
        }
        if (!includesOutput(ackBatchKind)) {
            return true;
        }
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
        return isAckTerminalOutputSafe(
                output.isEmpty(),
                isExpectedOutput(output),
                isPatternShapeComplete(handler),
                patternGridCompatible);
    }

    static boolean isAckTerminalOutputSafe(boolean outputEmpty,
                                           boolean expectedOutput,
                                           boolean patternShapeComplete,
                                           boolean patternGridCompatible) {
        return outputEmpty
                || expectedOutput
                || canContinuePastIntermediateOutput(patternShapeComplete, patternGridCompatible);
    }

    private boolean isPatternGridCompatible(CraftingMenu handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            ItemStack actual = handler.getSlot(GRID_START + patternIndex).getItem();
            if (expected.isEmpty()) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else if (!actual.isEmpty()
                    && !ItemStack.isSameItemSameComponents(expected, actual)
                    && !isExpectedRemainder(patternIndex, actual)) {
                return false;
            }
        }
        return true;
    }

    private String describeAckDifferences(CraftingMenu handler) {
        List<String> differences = new ArrayList<>();
        if (!ItemStack.matches(ackExpectedCursor, handler.getCarried())) {
            differences.add("光标:" + describeStack(ackExpectedCursor)
                    + "->" + describeStack(handler.getCarried()));
        }
        for (int slotId : ackExpectedSlotIds) {
            ItemStack expected = ackExpectedSlots.get(slotId);
            ItemStack actual = handler.getSlot(slotId).getItem();
            if (!ItemStack.matches(expected, actual)) {
                differences.add(slotId + ":" + describeStack(expected) + "->" + describeStack(actual));
                if (differences.size() >= 8) {
                    differences.add("...");
                    break;
                }
            }
        }
        return differences.toString();
    }

    private void recordAckWindow(long latencyNanos, int clicks, long now) {
        ackWindowConfirmedBatches++;
        ackWindowClicks += clicks;
        ackWindowLatencyNanos += latencyNanos;
        ackWindowMinLatencyNanos = minNonZero(ackWindowMinLatencyNanos, latencyNanos);
        ackWindowMaxLatencyNanos = Math.max(ackWindowMaxLatencyNanos, latencyNanos);
        if (now - ackWindowStartedAtNanos < 1_000_000_000L) {
            return;
        }
        LOGGER.info("确认驱动流水线窗口：模式={}，会话t+{} ms，确认批次={}，点击={}，平均={} ms，"
                        + "最小={} ms，最大={} ms，当前在途={}，累计输出点击={}",
                sessionMode, sessionElapsedMillis(), ackWindowConfirmedBatches, ackWindowClicks,
                averageMillis(ackWindowLatencyNanos, ackWindowConfirmedBatches),
                nanosToMillis(ackWindowMinLatencyNanos), nanosToMillis(ackWindowMaxLatencyNanos),
                ackBatchAwaiting ? 1 : 0, sessionOutputClicks);
        ackWindowStartedAtNanos = now;
        ackWindowConfirmedBatches = 0;
        ackWindowClicks = 0;
        ackWindowLatencyNanos = 0L;
        ackWindowMinLatencyNanos = 0L;
        ackWindowMaxLatencyNanos = 0L;
    }

    private void requestAckStopOrStop(Minecraft client) {
        requestAckStopOrStop(client, Component.translatable("quickcraft.message.crafting.stopped"));
    }

    private void requestAckStopOrStop(Minecraft client, Component message) {
        if (shouldDeferStopForAck(isAckPipelineEnabled(), ackBatchAwaiting,
                ackBatchRecording, ackBatchClickCount)) {
            if (!ackStopRequested) {
                ackStopRequested = true;
                ackStopMessage = message;
                LOGGER.debug("确认驱动流水线收到停止请求：等待当前批次完成，批次=#{}，类型={}，"
                                + "等待中={}，记录中={}，点击={}",
                        ackBatchId, ackBatchKind, ackBatchAwaiting,
                        ackBatchRecording, ackBatchClickCount);
            }
            return;
        }
        stop(client, message);
    }

    private Component getAckStopMessage() {
        return ackStopRequested && ackStopMessage != null
                ? ackStopMessage
                : Component.translatable("quickcraft.message.crafting.stopped");
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
        sessionAckBatches = 0;
        sessionAckConfirmedBatches = 0;
        sessionAckReconciledBatches = 0;
        sessionAckClicks = 0;
        sessionAckTimeouts = 0;
        sessionAckLatencyNanos = 0L;
        sessionAckMinLatencyNanos = 0L;
        sessionAckMaxLatencyNanos = 0L;
        sessionAckMaxRegularLatencyNanos = 0L;
        sessionAckRefillBatches = 0;
        sessionAckRefillSourceBatches = 0;
        sessionAckCombinedBatches = 0;
        sessionAckStatsProbesSent = 0;
        sessionAckStatsProbesCompleted = 0;
        sessionAckStatsProbeTimeouts = 0;
        sessionAckStatsProbeLatencyNanos = 0L;
        ackWindowStartedAtNanos = 0L;
        ackWindowConfirmedBatches = 0;
        ackWindowClicks = 0;
        ackWindowLatencyNanos = 0L;
        ackWindowMinLatencyNanos = 0L;
        ackWindowMaxLatencyNanos = 0L;
    }

    private void clearAckStatsProbe() {
        ackStatsProbePending = false;
        ackStatsProbeBatchId = 0L;
        ackStatsProbeNetworkHandler = null;
        ackStatsProbeSentAtNanos = 0L;
    }

    private static long minNonZero(long current, long candidate) {
        return current == 0L ? candidate : Math.min(current, candidate);
    }

    private static long averageMillis(long totalNanos, int count) {
        return count <= 0 ? 0L : totalNanos / count / 1_000_000L;
    }

    static double averageHundredths(int total, int count) {
        return count <= 0 ? 0.0D : Math.round(total * 100.0D / count) / 100.0D;
    }

    static double percentageHundredths(long part, long total) {
        return total <= 0L ? 0.0D : Math.round(part * 10_000.0D / total) / 100.0D;
    }

    private static long nanosToMillis(long nanos) {
        return nanos <= 0L ? 0L : nanos / 1_000_000L;
    }

    private void processCraftTick(Minecraft client, CraftingMenu handler) {
        int discardedRemainders = discardGridRemainders(client, handler);
        if (discardedRemainders > 0) {
            consecutiveFailures = 0;
            return;
        }
        if (waitForServerOutputCorrection(client, handler)) {
            return;
        }
        if (hasMissingPerCraftMaterial(handler)) {
            int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
            if (rebalancedItems > 0) {
                if (primeOutputLocally(client, handler) && combinesRefillAndOutput(sessionMode)) {
                    drainOutputBurst(client, handler);
                }
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
            LOGGER.debug("不可堆叠材料已消耗，优先请求补料：会话t+{} ms，结果={}，配方={}，合成格={}",
                    sessionElapsedMillis(), refill, recipe == null ? "NONE" : recipe.id(),
                    describePattern(pattern));
            if (!handleRefillStart(client, handler, refill)) {
                recordProgressOrStop(client, false);
            }
            return;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()
                && isExpectedOutput(handler.getSlot(OUTPUT_SLOT).getItem())) {
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
            if (primeOutputLocally(client, handler) && combinesRefillAndOutput(sessionMode)) {
                drainOutputBurst(client, handler);
            }
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
        LOGGER.debug("潜影盒工作台喷射请求补料：会话t+{} ms，结果={}，配方={}，合成格={}",
                sessionElapsedMillis(), refill, recipe == null ? "NONE" : recipe.id(),
                describePattern(pattern));
        if (handleRefillStart(client, handler, refill)) {
            return;
        }
        recordProgressOrStop(client, false);
    }

    private boolean handleRefillStart(Minecraft client,
                                      CraftingMenu handler,
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
            stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            return true;
        }
        return false;
    }

    private void runFirstRefillActionImmediately(Minecraft client,
                                                 CraftingMenu handler) {
        consecutiveFailures = 0;
        QuickCraftWorkbenchShulker.tickShulkerCraft(client);
        processRefillResult(client, handler);
    }

    private boolean throwOutputBurst(Minecraft client, CraftingMenu handler) {
        long startedAtNanos = System.nanoTime();
        int attempts = getAvailableCraftCount(handler);
        int completed = 0;
        while (completed < attempts && active) {
            if (!handler.getSlot(OUTPUT_SLOT).hasItem() && !primeOutputLocally(client, handler)) {
                break;
            }
            if (!isExpectedOutput(handler.getSlot(OUTPUT_SLOT).getItem())) {
                logUnexpectedOutput(handler);
                break;
            }
            client.gameMode.handleContainerInput(handler.containerId, OUTPUT_SLOT, 1,
                    ContainerInput.THROW, client.player);
            completed++;
            sessionOutputClicks++;
            if (completed == 1) {
                sessionOutputBursts++;
            }
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接丢弃 burst：会话t+{} ms，输出点击={} 次，耗时={} us，配方={}",
                    sessionElapsedMillis(), completed, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return completed > 0;
    }

    private boolean storeOutputBurst(Minecraft client, CraftingMenu handler) {
        long startedAtNanos = System.nanoTime();
        if (QuickCraftWorkbenchShulkerOutput.isShulkerBox(resultTemplate)) {
            stop(client, Component.translatable("quickcraft.message.crafting.shulker_output_unsupported"));
            return false;
        }

        int attempts = getAvailableCraftCount(handler);
        int sourceSlot = -1;
        int completed = 0;
        int boxTakes = 0;
        int boxReturns = 0;
        int boxSwitches = 0;
        while (completed < attempts && active) {
            if (!handler.getSlot(OUTPUT_SLOT).hasItem() && !primeOutputLocally(client, handler)) {
                break;
            }
            ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem().copy();
            if (!isExpectedOutput(output)) {
                logUnexpectedOutput(handler);
                break;
            }
            if (sourceSlot == -1
                    || QuickCraftWorkbenchShulkerOutput.getCapacity(handler.getCarried(), output) < output.getCount()) {
                boolean switchingBox = sourceSlot != -1;
                boolean hadBoxOnCursor = !handler.getCarried().isEmpty();
                if (!returnOutputBox(client, handler, sourceSlot)) {
                    stop(client, Component.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                    return completed > 0;
                }
                if (hadBoxOnCursor) {
                    boxReturns++;
                    sessionOutputBoxReturns++;
                }
                sourceSlot = QuickCraftWorkbenchShulkerOutput.takeBox(client, handler, output);
                if (sourceSlot == -1) {
                    sessionOutputBoxMisses++;
                    requestAckStopOrStop(client,
                            Component.translatable("quickcraft.message.crafting.no_output_shulker"));
                    return completed > 0;
                }
                boxTakes++;
                sessionOutputBoxTakes++;
                if (switchingBox) {
                    boxSwitches++;
                    sessionOutputBoxSwitches++;
                }
            }
            if (!QuickCraftWorkbenchShulkerOutput.storeOnce(client, handler, output)) {
                boolean hadBoxOnCursor = !handler.getCarried().isEmpty();
                boolean returned = returnOutputBox(client, handler, sourceSlot);
                if (returned && hadBoxOnCursor) {
                    boxReturns++;
                    sessionOutputBoxReturns++;
                }
                sessionOutputStoreFailures++;
                if (returned) {
                    requestAckStopOrStop(client,
                            Component.translatable("quickcraft.message.crafting.shulker_output_failed"));
                } else {
                    stop(client, Component.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                }
                return completed > 0;
            }
            completed++;
            sessionOutputClicks++;
            if (completed == 1) {
                sessionOutputBursts++;
            }
        }
        boolean hadBoxOnCursor = !handler.getCarried().isEmpty();
        if (!returnOutputBox(client, handler, sourceSlot)) {
            stop(client, Component.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
        } else if (hadBoxOnCursor) {
            boxReturns++;
            sessionOutputBoxReturns++;
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接装盒 burst：会话t+{} ms，输出点击={} 次，盒点击={} 次（拿盒={}，归还={}，换盒={}），"
                            + "耗时={} us，不受补货间隔限制，配方={}",
                    sessionElapsedMillis(), completed, boxTakes + boxReturns,
                    boxTakes, boxReturns, boxSwitches, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return completed > 0;
    }

    private boolean returnOutputBox(Minecraft client,
                                    CraftingMenu handler,
                                    int sourceSlotId) {
        if (sourceSlotId >= 0 && sourceSlotId < handler.slots.size()
                && handler.getSlot(sourceSlotId).hasItem()) {
            ItemStack blockingStack = handler.getSlot(sourceSlotId).getItem();
            if (!shouldDiscardOutputBoxSlot(recipeHasRemainder,
                    isCachedRemainder(blockingStack))) {
                LOGGER.warn("产物盒原槽被非返还物占用：会话t+{} ms，槽位={}，物品={}，光标={}，配方={}",
                        sessionElapsedMillis(), sourceSlotId, describeStack(blockingStack),
                        describeStack(handler.getCarried()), recipe == null ? "NONE" : recipe.id());
                return false;
            }
            ItemStack discarded = blockingStack.copy();
            if (!QuickCraftWorkbenchShulkerOutput.isShulkerBox(handler.getCarried())
                    || handler.getCarried().getCount() != 1) {
                return false;
            }
            client.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0,
                    ContainerInput.PICKUP, client.player);
            if (!QuickCraftWorkbenchShulkerOutput.isShulkerBox(
                    handler.getSlot(sourceSlotId).getItem())
                    || !isCachedRemainder(handler.getCarried())) {
                LOGGER.warn("产物盒与返还物交换失败：会话t+{} ms，槽位={}，槽内={}，光标={}，配方={}",
                        sessionElapsedMillis(), sourceSlotId,
                        describeStack(handler.getSlot(sourceSlotId).getItem()),
                        describeStack(handler.getCarried()), recipe == null ? "NONE" : recipe.id());
                return false;
            }
            client.gameMode.handleContainerInput(handler.containerId, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0,
                    ContainerInput.PICKUP, client.player);
            if (!handler.getCarried().isEmpty()) {
                LOGGER.warn("丢弃产物盒原槽返还物失败：会话t+{} ms，槽位={}，光标={}，配方={}",
                        sessionElapsedMillis(), sourceSlotId, describeStack(handler.getCarried()),
                        recipe == null ? "NONE" : recipe.id());
                return false;
            }
            LOGGER.debug("丢弃占用产物盒原槽的配方返还物：会话t+{} ms，槽位={}，物品={}，配方={}",
                    sessionElapsedMillis(), sourceSlotId, describeStack(discarded),
                    recipe == null ? "NONE" : recipe.id());
            return true;
        }
        return QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlotId);
    }

    private boolean isCachedRemainder(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemStack remainder : remainderPattern) {
            if (!remainder.isEmpty() && stack.getItem() == remainder.getItem()) {
                return true;
            }
        }
        return false;
    }

    static boolean shouldDiscardOutputBoxSlot(boolean hasRemainder,
                                              boolean matchesCachedRemainder) {
        return hasRemainder && matchesCachedRemainder;
    }

    private void processRefillResult(Minecraft client, CraftingMenu handler) {
        QuickCraftWorkbenchShulker.TaskResult result = QuickCraftWorkbenchShulker.consumeShulkerCraftResult();
        if (result == QuickCraftWorkbenchShulker.TaskResult.NONE) {
            return;
        }
        Component message = QuickCraftWorkbenchShulker.consumeShulkerCraftMessage();
        if (result == QuickCraftWorkbenchShulker.TaskResult.STOPPED || !isRapidInputHeld(client)) {
            stop(client, message != null ? message : Component.translatable("quickcraft.message.crafting.stopped"));
            return;
        }
        int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
        if (rebalancedItems > 0) {
            consecutiveFailures = 0;
        }
        if (!primeOutputLocally(client, handler)) {
            if (rebalancedItems > 0) {
                return;
            }
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
        long outputStartedAtNanos = System.nanoTime();
        boolean progressed = drainOutputBurst(client, handler);
        LOGGER.debug("组合极速补货完成同批输出：会话t+{} ms，任务结果={}，模式={}，成功={}，耗时={} us",
                sessionElapsedMillis(), result, sessionMode, progressed,
                (System.nanoTime() - outputStartedAtNanos) / 1_000L);
        recordProgressOrStop(client, progressed);
    }

    private boolean drainOutputBurst(Minecraft client, CraftingMenu handler) {
        return sessionOutputToShulker
                ? storeOutputBurst(client, handler)
                : throwOutputBurst(client, handler);
    }

    private void recordProgressOrStop(Minecraft client, boolean progressed) {
        if (progressed) {
            consecutiveFailures = 0;
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_FAILURES) {
            stop(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean start(Minecraft client, boolean fromButton) {
        if (active || craftStatsBaselinePending || pendingCraftStats != null) {
            LOGGER.debug("忽略重复启动潜影盒工作台喷射：active={}，基线等待={}，结算等待={}",
                    active, craftStatsBaselinePending, pendingCraftStats != null);
            return false;
        }
        boolean workbenchOpen = isWorkbenchOpen(client);
        boolean available = QuickCraftWorkbenchShulker.isAvailable();
        LOGGER.debug("请求启动潜影盒工作台喷射：fromButton={}，workbenchOpen={}，featureEnabled={}，"
                        + "quickShulkerConfigured={}，available={}",
                fromButton, workbenchOpen, QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled(),
                QuickCraftWorkbenchShulker.isConfigured(), available);
        if (!workbenchOpen || !available) {
            sendMessage(client, Component.translatable("quickcraft.message.crafting.shulker_unavailable"));
            return false;
        }
        CraftingMenu handler = (CraftingMenu) client.player.containerMenu;
        RecipeHolder<CraftingRecipe> currentRecipe = findCurrentRecipe(client, handler);
        boolean capturedVisibleRecipe = handler.getSlot(OUTPUT_SLOT).hasItem();
        boolean canReuseSnapshot = canReuseSnapshot(handler.containerId, snapshotSyncId,
                !pattern.isEmpty() && !resultTemplate.isEmpty());
        if (!canStartWithRecipeState(capturedVisibleRecipe, canReuseSnapshot)) {
            sendMessage(client, Component.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }
        if (capturedVisibleRecipe) {
            List<ItemStack> capturedRemainders;
            try {
                CraftingInput input = createInput(handler);
                capturedRemainders = currentRecipe != null
                        ? currentRecipe.value().getRemainingItems(input)
                        : CraftingRecipe.defaultCraftingReminder(input);
            } catch (Throwable throwable) {
                LOGGER.error("读取工作台配方返还物失败", throwable);
                sendMessage(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
                return false;
            }
            recipe = currentRecipe;
            pattern = snapshotPattern(handler);
            remainderPattern = capturedRemainders;
            recipeHasRemainder = containsRemainder(capturedRemainders);
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
            snapshotSyncId = handler.containerId;
            LOGGER.debug("锁定潜影盒工作台配方快照：containerId={}，配方={}，合成格={}",
                    snapshotSyncId, "UNKNOWN", describePattern(pattern));
        } else {
            LOGGER.debug("复用潜影盒工作台配方快照：containerId={}，配方={}，合成格={}",
                    snapshotSyncId, recipe == null ? "UNKNOWN" : recipe.id(), describePattern(pattern));
        }
        active = true;
        startedByButton = fromButton;
        consecutiveFailures = 0;
        sessionStartedAtNanos = 0L;
        sessionOutputClicks = 0;
        sessionOutputBursts = 0;
        sessionOutputToShulker = QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled();
        sessionRequiresOrderedProbe = requiresOrderedAckProbe(
                isIgnoredInRecipeBook(recipe),
                resultTemplate.getMaxStackSize(),
                hasPerCraftMaterial(pattern),
                recipeHasRemainder);
        sessionOutputBoxTakes = 0;
        sessionOutputBoxReturns = 0;
        sessionOutputBoxSwitches = 0;
        sessionOutputBoxMisses = 0;
        sessionOutputStoreFailures = 0;
        sessionMode = QuickCraftConfigs.getWorkbenchQuickShulkerPipelineMode();
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorWaitEvents = 0;
        sessionCursorWaitTicks = 0;
        sessionCursorRecoveries = 0;
        sessionCorrectionPauseTicks = 0;
        sessionCursorSettleTicks = CURSOR_SETTLE_TICKS;
        sessionRecoveryPauseTicks = RECOVERY_PAUSE_TICKS;
        sessionCursorTimeoutTicks = CURSOR_TIMEOUT_TICKS;
        sessionOutputMismatchLogged = false;
        serverOutputMismatchTicks = 0;
        craftStatsBaselinePending = true;
        craftStatsBaselineNetworkHandler = client.getConnection();
        craftStatsBaselineRequestedAtNanos = System.nanoTime();
        sessionCraftStatsBaselineWaitNanos = 0L;
        sessionCraftedStatBaseline = -1;
        clearAckSession();
        if (!requestServerStats(client)) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
        } else {
            LOGGER.debug("请求服务端合成统计基线：配方={}，产物={}",
                    recipe == null ? "UNKNOWN" : recipe.id(), describeStack(resultTemplate));
        }
        beginSessionImmediately(client);
        return true;
    }

    private boolean requestServerStats(Minecraft client) {
        if (client == null || client.getConnection() == null) {
            return false;
        }
        client.getConnection().send(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        return true;
    }

    private void beginSessionImmediately(Minecraft client) {
        if (!active || !isWorkbenchOpen(client)
                || !(client.player.containerMenu instanceof CraftingMenu handler)
                || handler.containerId != snapshotSyncId) {
            active = false;
            return;
        }
        sessionStartedAtNanos = System.nanoTime();
        ackWindowStartedAtNanos = sessionStartedAtNanos;
        QuickCraftWorkbenchShulker.beginDebugSession(sessionMode);
        QuickCraftWorkbenchShulkerTelemetry.begin(handler);
        LOGGER.info("工作台潜影盒网络毫秒时序已启用：containerId={}，revision={}，统计基线状态={}",
                handler.containerId, handler.getStateId(),
                craftStatsBaselinePending ? "已先发请求/不等待回包" : "不可用");
        sendMessage(client, Component.translatable("quickcraft.message.crafting.started"));
        int effectiveSourceBatches = QuickCraftWorkbenchShulker.sourceBatchesPerAck(sessionMode);
        LOGGER.info("开始潜影盒工作台喷射：会话t+0 ms，流水线模式={}，执行模式=服务端回包立即驱动，"
                        + "确认窗口={}，来源盒上限={}，光标策略={}/{}/{} Tick，配方={}，输出装盒={}",
                sessionMode, pipelineDescription(sessionMode), effectiveSourceBatches,
                sessionCursorSettleTicks, sessionRecoveryPauseTicks,
                sessionCursorTimeoutTicks, recipe == null ? "UNKNOWN" : recipe.id(), sessionOutputToShulker);
    }

    private void handleServerStatistics(Minecraft client,
                                        ClientPacketListener source) {
        if (client == null || source == null || client.getConnection() != source) {
            discardCraftStatsForDisconnectedHandler(source);
            return;
        }
        if (craftStatsBaselinePending) {
            if (source != craftStatsBaselineNetworkHandler) {
                return;
            }
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            Item baselineItem = pendingCraftStats == null
                    ? resultTemplate.getItem() : pendingCraftStats.resultItem();
            int baseline = readCraftedStat(client, baselineItem);
            sessionCraftedStatBaseline = baseline;
            sessionCraftStatsBaselineWaitNanos = Math.max(
                    0L, System.nanoTime() - craftStatsBaselineRequestedAtNanos);
            if (pendingCraftStats != null
                    && source == pendingCraftStats.networkHandler()) {
                pendingCraftStats = pendingCraftStats.withBaselineStat(
                        baseline, nanosToMillis(sessionCraftStatsBaselineWaitNanos));
            }
            LOGGER.debug("收到服务端合成统计基线：等待={} ms，基线={}，会话仍活动={}",
                    nanosToMillis(sessionCraftStatsBaselineWaitNanos), baseline, active);
            return;
        }
        if (handleAckStatsProbeResponse(client, source)) {
            return;
        }
        PendingCraftStats pending = pendingCraftStats;
        if (pending == null) {
            return;
        }
        if (source != pending.networkHandler()) {
            return;
        }
        pendingCraftStats = null;
        int finalStat = readCraftedStat(client, pending.resultItem());
        logServerCraftStats(pending, finalStat, System.nanoTime());
    }

    private boolean handleAckStatsProbeResponse(Minecraft client,
                                                ClientPacketListener source) {
        if (!ackStatsProbePending || source != ackStatsProbeNetworkHandler) {
            return false;
        }
        long now = System.nanoTime();
        long probeBatchId = ackStatsProbeBatchId;
        long probeLatencyNanos = Math.max(0L, now - ackStatsProbeSentAtNanos);
        boolean currentProbe = isCurrentAckStatsProbe(
                ackStatsProbePending, probeBatchId, ackBatchId,
                active && ackBatchAwaiting, source == ackStatsProbeNetworkHandler);
        clearAckStatsProbe();
        sessionAckStatsProbeLatencyNanos += probeLatencyNanos;

        if (!currentProbe
                || client.player == null
                || !(client.player.containerMenu instanceof CraftingMenu handler)
                || handler.containerId != snapshotSyncId) {
            LOGGER.error("确认驱动流水线静默探针失配：探针批次=#{}，当前批次=#{}，活动={}，在途={}，"
                            + "工作台={}，探针耗时={} ms",
                    probeBatchId, ackBatchId, active, ackBatchAwaiting,
                    client.player != null
                            && client.player.containerMenu instanceof CraftingMenu,
                    nanosToMillis(probeLatencyNanos));
            if (active) {
                stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            }
            return true;
        }

        boolean exactStateMatches = handlerMatchesAckExpected(handler);
        boolean terminalStateSafe = isAckBatchTerminalStateSafe(handler);
        if (!shouldConfirmAckStatsProbe(currentProbe, terminalStateSafe)) {
            LOGGER.error("确认驱动流水线静默探针发现不安全终态：批次=#{}，类型={}，探针耗时={} ms，"
                            + "总等待={} ms，revision={}->{}, 精确状态={}，差异={}，光标={}，合成格={}",
                    ackBatchId, ackBatchKind, nanosToMillis(probeLatencyNanos),
                    nanosToMillis(now - ackBatchSentAtNanos), ackBatchStartRevision,
                    handler.getStateId(), exactStateMatches, describeAckDifferences(handler),
                    describeStack(handler.getCarried()), describePattern(snapshotPattern(handler)));
            stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
            return true;
        }

        sessionAckStatsProbesCompleted++;
        LOGGER.info("确认驱动流水线静默探针完成：批次=#{}，类型={}，探针耗时={} ms，总等待={} ms，"
                        + "revision={}->{}, 状态={}，差异={}",
                ackBatchId, ackBatchKind, nanosToMillis(probeLatencyNanos),
                nanosToMillis(now - ackBatchSentAtNanos), ackBatchStartRevision,
                handler.getStateId(), exactStateMatches ? "精确" : "服务端安全重算",
                describeAckDifferences(handler));
        completeAckBatch(client, handler, "统计顺序屏障", handler.getStateId(),
                exactStateMatches, now);
        return true;
    }

    private int readCraftedStat(Minecraft client, Item item) {
        if (client == null || client.player == null || item == null) {
            return -1;
        }
        return client.player.getStats().getValue(Stats.ITEM_CRAFTED.get(item));
    }

    private void handleCraftStatsTimeout(Minecraft client) {
        long now = System.nanoTime();
        ClientPacketListener currentNetworkHandler = client == null
                ? null : client.getConnection();
        if (craftStatsBaselinePending
                && currentNetworkHandler != craftStatsBaselineNetworkHandler) {
            boolean wasActive = active;
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            active = false;
            if (wasActive) {
                logSessionSummary("服务器连接变化");
            }
            LOGGER.warn("服务端实际合成汇总：统计基线因连接变化取消，输出点击发送={} 次，"
                            + "实际成功=未确认",
                    sessionOutputClicks);
        }
        if (pendingCraftStats != null
                && currentNetworkHandler != pendingCraftStats.networkHandler()) {
            PendingCraftStats discarded = pendingCraftStats;
            pendingCraftStats = null;
            LOGGER.warn("服务端实际合成汇总：连接已变化，输出点击发送={} 次，实际成功=未确认，"
                            + "配方={}，结束原因={}",
                    discarded.sentOutputClicks(), discarded.recipeId(), discarded.reason());
        }
        if (craftStatsBaselinePending
                && now - craftStatsBaselineRequestedAtNanos >= SERVER_STATS_TIMEOUT_NANOS) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            LOGGER.warn("等待服务端合成统计基线超时：等待={} ms；本次继续运行但实际产量将标记为未确认",
                    nanosToMillis(now - craftStatsBaselineRequestedAtNanos));
            sessionCraftStatsBaselineWaitNanos = now - craftStatsBaselineRequestedAtNanos;
        }
        PendingCraftStats pending = pendingCraftStats;
        if (pending != null
                && now - pending.requestedAtNanos() >= SERVER_STATS_TIMEOUT_NANOS) {
            pendingCraftStats = null;
            LOGGER.warn("服务端实际合成汇总：统计回包超时，输出点击发送={} 次，实际成功=未确认，"
                            + "配方={}，结束原因={}",
                    pending.sentOutputClicks(), pending.recipeId(), pending.reason());
        }
    }

    private void queueFinalCraftStats(Minecraft client, String reason) {
        if (sessionStartedAtNanos == 0L) {
            LOGGER.info("服务端实际合成汇总：耗时=0 ms，服务端成功=0 次，真实速度=0.0 次/秒，"
                            + "输出点击发送=0 次，无效或被纠正=0 次，配方={}，结束原因={}",
                    recipe == null ? "NONE" : recipe.id(), reason);
            return;
        }
        PendingCraftStats pending = new PendingCraftStats(
                client == null ? null : client.getConnection(),
                resultTemplate.getItem(), Math.max(1, resultTemplate.getCount()),
                sessionCraftedStatBaseline, sessionOutputClicks, sessionStartedAtNanos,
                System.nanoTime(), nanosToMillis(sessionCraftStatsBaselineWaitNanos),
                recipe == null ? "NONE" : recipe.id().toString(), reason, sessionMode);
        boolean baselineMayStillArrive = craftStatsBaselinePending
                && client != null
                && client.getConnection() == craftStatsBaselineNetworkHandler;
        if (sessionCraftedStatBaseline < 0 && !baselineMayStillArrive) {
            LOGGER.warn("服务端实际合成汇总：统计不可用，输出点击发送={} 次，实际成功=未确认，"
                            + "配方={}，结束原因={}",
                    pending.sentOutputClicks(), pending.recipeId(), pending.reason());
            return;
        }
        pendingCraftStats = pending;
        if (!requestServerStats(client)) {
            pendingCraftStats = null;
            LOGGER.warn("服务端实际合成汇总：无法请求统计，输出点击发送={} 次，实际成功=未确认，"
                            + "配方={}，结束原因={}",
                    pending.sentOutputClicks(), pending.recipeId(), pending.reason());
            return;
        }
        LOGGER.debug("请求服务端最终合成统计：输出点击发送={}，配方={}",
                pending.sentOutputClicks(), pending.recipeId());
    }

    private void discardCraftStatsForDisconnectedHandler(ClientPacketListener source) {
        if (craftStatsBaselinePending && source == craftStatsBaselineNetworkHandler) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            active = false;
        }
        if (pendingCraftStats != null && source == pendingCraftStats.networkHandler()) {
            PendingCraftStats discarded = pendingCraftStats;
            pendingCraftStats = null;
            LOGGER.warn("服务端实际合成汇总：旧连接统计回包已丢弃，输出点击发送={} 次，"
                            + "实际成功=未确认，配方={}",
                    discarded.sentOutputClicks(), discarded.recipeId());
        }
        if (ackStatsProbePending && source == ackStatsProbeNetworkHandler) {
            LOGGER.warn("确认驱动流水线静默探针因连接变化丢弃：批次=#{}，等待={} ms",
                    ackStatsProbeBatchId,
                    nanosToMillis(System.nanoTime() - ackStatsProbeSentAtNanos));
            clearAckStatsProbe();
            active = false;
        }
    }

    private void logServerCraftStats(PendingCraftStats pending, int finalStat, long now) {
        int confirmedCrafts = confirmedCraftsFromStats(
                pending.baselineStat(), finalStat,
                pending.outputItemsPerCraft(), pending.sentOutputClicks());
        long elapsedMillis = Math.max(0L, (now - pending.startedAtNanos()) / 1_000_000L);
        long operationMillis = Math.max(0L,
                (pending.requestedAtNanos() - pending.startedAtNanos()) / 1_000_000L);
        long finalStatsWaitMillis = Math.max(0L, (now - pending.requestedAtNanos()) / 1_000_000L);
        if (confirmedCrafts < 0) {
            LOGGER.warn("服务端实际合成汇总：统计不一致，基线={}，最终={}，单次产物={}，"
                            + "输出点击发送={}，实际成功=未确认，耗时={} ms，配方={}，结束原因={}",
                    pending.baselineStat(), finalStat, pending.outputItemsPerCraft(),
                    pending.sentOutputClicks(), elapsedMillis, pending.recipeId(), pending.reason());
            return;
        }
        int ineffectiveClicks = pending.sentOutputClicks() - confirmedCrafts;
        LOGGER.info("服务端实际合成汇总：端到端耗时={} ms，操作窗口={} ms，服务端成功={} 次/{} 件，"
                        + "端到端真实速度={} 次/秒，操作窗口参考速度={} 次/秒，"
                        + "输出点击发送={} 次，无效或被纠正={} 次，统计等待=开始{} ms/结束{} ms，"
                        + "流水线模式={}，配方={}，结束原因={}",
                elapsedMillis, operationMillis, confirmedCrafts,
                (long) confirmedCrafts * pending.outputItemsPerCraft(),
                craftsPerSecond(confirmedCrafts, elapsedMillis),
                craftsPerSecond(confirmedCrafts, operationMillis),
                pending.sentOutputClicks(), ineffectiveClicks,
                pending.baselineWaitMillis(), finalStatsWaitMillis,
                pending.pipelineMode(), pending.recipeId(), pending.reason());
    }

    static int confirmedCraftsFromStats(int baselineStat,
                                        int finalStat,
                                        int outputItemsPerCraft,
                                        int sentOutputClicks) {
        if (baselineStat < 0 || finalStat < baselineStat
                || outputItemsPerCraft <= 0 || sentOutputClicks < 0) {
            return -1;
        }
        long itemDelta = (long) finalStat - baselineStat;
        if (itemDelta % outputItemsPerCraft != 0L) {
            return -1;
        }
        long crafts = itemDelta / outputItemsPerCraft;
        if (crafts > sentOutputClicks
                || (finalStat == Integer.MAX_VALUE && crafts < sentOutputClicks)) {
            return -1;
        }
        return (int) crafts;
    }

    private boolean primeOutputLocally(Minecraft client, CraftingMenu handler) {
        if (client.level == null || resultTemplate.isEmpty()) {
            return false;
        }
        try {
            CraftingInput input = createInput(handler);
            ItemStack result;
            if (recipe != null) {
                if (!recipe.value().matches(input, client.level)) {
                    return false;
                }
                result = recipe.value().assemble(input);
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
            if (!(outputSlot.container instanceof ResultContainer resultInventory)) {
                return false;
            }
            if (recipe != null) {
                resultInventory.setRecipeUsed(recipe);
            }
            resultInventory.setItem(outputSlot.getContainerSlot(), result.copy());
            return isExpectedOutput(outputSlot.getItem());
        } catch (Throwable throwable) {
            LOGGER.debug("本地计算潜影盒合成产物失败：配方={}", recipe == null ? "UNKNOWN" : recipe.id(), throwable);
            return false;
        }
    }

    private int getAvailableCraftCount(CraftingMenu handler) {
        int attempts = MAX_OUTPUT_BURST;
        boolean hasIngredient = false;
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getItem();
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
                && ItemStack.isSameItemSameComponents(stack, resultTemplate);
    }

    private RecipeHolder<CraftingRecipe> findCurrentRecipe(Minecraft client,
                                                           CraftingMenu handler) {
        // 26.1+ 客户端只同步配方展示数据；产物槽与合成格快照仍可安全驱动这条执行链。
        return null;
    }

    private boolean hasRemainder(RecipeHolder<CraftingRecipe> currentRecipe,
                                 CraftingMenu handler) {
        try {
            CraftingInput input = createInput(handler);
            List<ItemStack> remainders = currentRecipe != null
                    ? currentRecipe.value().getRemainingItems(input)
                    : CraftingRecipe.defaultCraftingReminder(input);
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

    static List<ItemStack> expandRemainderPattern(List<ItemStack> compactRemainders,
                                                   int width,
                                                   int height,
                                                   int left,
                                                   int top) {
        List<ItemStack> captured = new ArrayList<>(9);
        for (int patternIndex = 0; patternIndex < 9; patternIndex++) {
            captured.add(ItemStack.EMPTY);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int compactIndex = x + y * width;
                if (compactIndex >= compactRemainders.size()) {
                    continue;
                }
                ItemStack remainder = compactRemainders.get(compactIndex);
                int patternIndex = expandRemainderIndex(compactIndex, width, left, top);
                captured.set(patternIndex, remainder.isEmpty() ? ItemStack.EMPTY : remainder.copy());
            }
        }
        return List.copyOf(captured);
    }

    static int expandRemainderIndex(int compactIndex, int width, int left, int top) {
        int x = compactIndex % width;
        int y = compactIndex / width;
        return left + x + (top + y) * 3;
    }

    static boolean isPerCraftMaterial(int maxCount) {
        return maxCount == 1;
    }

    static boolean requiresOrderedAckProbe(boolean ignoredInRecipeBook,
                                           int outputMaxCount,
                                           boolean perCraftMaterial,
                                           boolean hasRemainder) {
        return ignoredInRecipeBook || outputMaxCount == 1 || perCraftMaterial || hasRemainder;
    }

    private boolean isIgnoredInRecipeBook(RecipeHolder<CraftingRecipe> currentRecipe) {
        try {
            return currentRecipe != null && currentRecipe.value().isSpecial();
        } catch (Throwable throwable) {
            return true;
        }
    }

    private boolean hasPerCraftMaterial(List<ItemStack> currentPattern) {
        for (ItemStack stack : currentPattern) {
            if (!stack.isEmpty() && isPerCraftMaterial(stack.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRemainder(List<ItemStack> remainders) {
        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpectedRemainder(int patternIndex, ItemStack actual) {
        if (patternIndex < 0 || patternIndex >= remainderPattern.size() || actual.isEmpty()) {
            return false;
        }
        ItemStack remainder = remainderPattern.get(patternIndex);
        return !remainder.isEmpty() && actual.getItem() == remainder.getItem();
    }

    private int discardGridRemainders(Minecraft client,
                                      CraftingMenu handler) {
        if (!recipeHasRemainder || client.player == null || client.gameMode == null
                || !handler.getCarried().isEmpty()) {
            return 0;
        }

        int discarded = 0;
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            int slotId = GRID_START + patternIndex;
            ItemStack actual = handler.getSlot(slotId).getItem();
            boolean matchesPattern = !actual.isEmpty()
                    && ItemStack.isSameItemSameComponents(pattern.get(patternIndex), actual);
            if (!shouldDiscardGridRemainder(matchesPattern,
                    isExpectedRemainder(patternIndex, actual))) {
                continue;
            }
            client.gameMode.handleContainerInput(
                    handler.containerId, slotId, 1, ContainerInput.THROW, client.player);
            discarded++;
        }
        if (discarded > 0) {
            LOGGER.debug("丢弃工作台配方返还物：会话t+{} ms，槽位={}，配方={}",
                    sessionElapsedMillis(), discarded, recipe == null ? "NONE" : recipe.id());
        }
        return discarded;
    }

    static boolean shouldDiscardGridRemainder(boolean matchesLockedPattern,
                                              boolean matchesRemainder) {
        return !matchesLockedPattern && matchesRemainder;
    }

    static boolean canReuseSnapshot(int currentSyncId, int capturedSyncId, boolean snapshotComplete) {
        return currentSyncId == capturedSyncId && snapshotComplete;
    }

    static boolean canStartWithRecipeState(boolean outputVisible, boolean snapshotReusable) {
        return outputVisible || snapshotReusable;
    }

    private boolean hasMissingPerCraftMaterial(CraftingMenu handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            if (!expected.isEmpty()
                    && isPerCraftMaterial(expected.getMaxStackSize())
                    && handler.getSlot(GRID_START + patternIndex).getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int fillMissingSlotsFromPlayerInventory(Minecraft client,
                                                    CraftingMenu handler) {
        if (client.player == null || client.gameMode == null
                || !handler.getCarried().isEmpty()) {
            return 0;
        }

        long startedAtNanos = System.nanoTime();
        int movedItems = 0;
        int operations = 0;
        patternLoop:
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack template = pattern.get(patternIndex);
            if (template.isEmpty() || hasEarlierMatchingPatternStack(patternIndex, template)) {
                continue;
            }

            for (int attempt = 0; attempt < Inventory.INVENTORY_SIZE; attempt++) {
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
                if (isAckPipelineEnabled()) {
                    break patternLoop;
                }
            }
        }

        if (movedItems > 0) {
            LOGGER.debug("背包散装材料补格：会话t+{} ms，移动={} 个，来源堆={}，耗时={} us，配方={}",
                    sessionElapsedMillis(), movedItems, operations,
                    (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return movedItems;
    }

    private boolean hasEarlierMatchingPatternStack(int patternIndex, ItemStack template) {
        for (int i = 0; i < patternIndex; i++) {
            ItemStack earlier = pattern.get(i);
            if (!earlier.isEmpty() && ItemStack.isSameItemSameComponents(earlier, template)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getFillablePatternSlots(CraftingMenu handler, ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            ItemStack expected = pattern.get(i);
            if (expected.isEmpty() || !ItemStack.isSameItemSameComponents(expected, template)) {
                continue;
            }
            int slotId = GRID_START + i;
            Slot slot = handler.getSlot(slotId);
            ItemStack current = slot.getItem();
            if ((current.isEmpty() || ItemStack.isSameItemSameComponents(current, template))
                    && current.getCount() < slot.getMaxStackSize(template)
                    && slot.mayPlace(template)) {
                slots.add(slotId);
            }
        }
        slots.sort((left, right) -> Integer.compare(
                handler.getSlot(left).getItem().getCount(),
                handler.getSlot(right).getItem().getCount()));
        return slots;
    }

    private int rebalanceIncompleteRepeatedMaterials(Minecraft client,
                                                     CraftingMenu handler) {
        if (client.player == null || client.gameMode == null
                || !handler.getCarried().isEmpty()) {
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
            if (isAckPipelineEnabled() && movedItems > 0) {
                break;
            }
        }
        if (movedItems > 0) {
            LOGGER.debug("复杂配方尾数工作台内重新均分：会话t+{} ms，移动={} 个，耗时={} us，配方={}",
                    sessionElapsedMillis(), movedItems, (System.nanoTime() - startedAtNanos) / 1_000L,
                    recipe == null ? "NONE" : recipe.id());
        }
        return movedItems;
    }

    private int rebalancePatternGroupInGrid(Minecraft client,
                                             CraftingMenu handler,
                                             List<Integer> groupSlots,
                                             ItemStack template) {
        int total = 0;
        for (int slotId : groupSlots) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        if (total <= 0) {
            return 0;
        }

        int soleSourceSlotId = -1;
        for (int slotId : groupSlots) {
            if (matchingGridCount(handler.getSlot(slotId).getItem(), template) > 0) {
                if (soleSourceSlotId != -1) {
                    soleSourceSlotId = -1;
                    break;
                }
                soleSourceSlotId = slotId;
            }
        }
        if (soleSourceSlotId != -1 && total >= groupSlots.size()) {
            int moved = distributeGridStack(client, handler, soleSourceSlotId, groupSlots, template);
            if (moved > 0) {
                return moved;
            }
        }

        int base = total / groupSlots.size();
        int remainder = total % groupSlots.size();
        int movedItems = 0;
        for (int targetIndex = 0; targetIndex < groupSlots.size(); targetIndex++) {
            int targetSlotId = groupSlots.get(targetIndex);
            int targetGoal = base + (targetIndex < remainder ? 1 : 0);
            int targetCount = matchingGridCount(handler.getSlot(targetSlotId).getItem(), template);
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
                    int sourceCount = matchingGridCount(handler.getSlot(sourceSlotId).getItem(), template);
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

    private int distributeGridStack(Minecraft client,
                                    CraftingMenu handler,
                                    int sourceSlotId,
                                    List<Integer> targetSlots,
                                    ItemStack template) {
        int sourceBefore = matchingGridCount(handler.getSlot(sourceSlotId).getItem(), template);
        client.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0,
                ContainerInput.PICKUP, client.player);
        if (!sameCursorMaterial(handler.getCarried(), template)) {
            returnCursorToGridSlot(client, handler, sourceSlotId);
            return 0;
        }
        client.gameMode.handleContainerInput(handler.containerId, -999,
                AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, client.player);
        for (int slotId : targetSlots) {
            client.gameMode.handleContainerInput(handler.containerId, slotId,
                    AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, client.player);
        }
        client.gameMode.handleContainerInput(handler.containerId, -999,
                AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, client.player);
        if (!returnCursorToGridSlot(client, handler, sourceSlotId)) {
            return 0;
        }
        return Math.max(0, sourceBefore - matchingGridCount(handler.getSlot(sourceSlotId).getItem(), template));
    }

    private int moveGridItems(Minecraft client,
                              CraftingMenu handler,
                              int sourceSlotId,
                              int targetSlotId,
                              int amount,
                              ItemStack template) {
        if (amount <= 0 || !handler.getCarried().isEmpty()) {
            return 0;
        }
        Slot source = handler.getSlot(sourceSlotId);
        Slot target = handler.getSlot(targetSlotId);
        ItemStack sourceStack = source.getItem();
        ItemStack targetStack = target.getItem();
        if (sourceStack.isEmpty() || !ItemStack.isSameItemSameComponents(sourceStack, template)
                || (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(targetStack, template))) {
            return 0;
        }

        int sourceBefore = sourceStack.getCount();
        int targetBefore = matchingGridCount(targetStack, template);
        int capacity = target.getMaxStackSize(template) - targetBefore;
        int requested = Math.min(amount, Math.min(sourceBefore, capacity));
        if (requested <= 0) {
            return 0;
        }

        // 能整堆搬运时只发两个槽位操作；尾数才退化为右键逐个补齐，避免把材料放进背包。
        if (requested == sourceBefore && targetBefore == 0) {
            client.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0,
                    ContainerInput.PICKUP, client.player);
            if (!sameCursorMaterial(handler.getCarried(), template)) {
                returnCursorToGridSlot(client, handler, sourceSlotId);
                return 0;
            }
            client.gameMode.handleContainerInput(handler.containerId, targetSlotId, 0,
                    ContainerInput.PICKUP, client.player);
        } else {
            client.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0,
                    ContainerInput.PICKUP, client.player);
            if (!sameCursorMaterial(handler.getCarried(), template)) {
                returnCursorToGridSlot(client, handler, sourceSlotId);
                return 0;
            }
            int moved = 0;
            while (moved < requested && !handler.getCarried().isEmpty()) {
                int before = matchingGridCount(handler.getSlot(targetSlotId).getItem(), template);
                client.gameMode.handleContainerInput(handler.containerId, targetSlotId, 1,
                        ContainerInput.PICKUP, client.player);
                int after = matchingGridCount(handler.getSlot(targetSlotId).getItem(), template);
                if (after <= before) {
                    break;
                }
                moved += after - before;
            }
            returnCursorToGridSlot(client, handler, sourceSlotId);
        }

        int sourceAfter = matchingGridCount(handler.getSlot(sourceSlotId).getItem(), template);
        int targetAfter = matchingGridCount(handler.getSlot(targetSlotId).getItem(), template);
        return Math.max(0, Math.min(requested, targetAfter - targetBefore))
                + Math.max(0, sourceBefore - sourceAfter - requested);
    }

    private boolean returnCursorToGridSlot(Minecraft client,
                                           CraftingMenu handler,
                                           int slotId) {
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        client.gameMode.handleContainerInput(handler.containerId, slotId, 0,
                ContainerInput.PICKUP, client.player);
        return handler.getCarried().isEmpty();
    }

    private int matchingGridCount(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)
                ? stack.getCount() : 0;
    }

    private boolean sameCursorMaterial(ItemStack cursor, ItemStack template) {
        return !cursor.isEmpty() && ItemStack.isSameItemSameComponents(cursor, template);
    }

    private List<Integer> getMatchingPatternSlots(ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            ItemStack expected = pattern.get(i);
            if (!expected.isEmpty() && ItemStack.isSameItemSameComponents(expected, template)) {
                slots.add(GRID_START + i);
            }
        }
        return slots;
    }

    private int countEmptySlots(CraftingMenu handler, List<Integer> slotIds) {
        int count = 0;
        for (int slotId : slotIds) {
            if (!handler.getSlot(slotId).hasItem()) {
                count++;
            }
        }
        return count;
    }

    private int countMatchingPlayerItems(CraftingMenu handler, ItemStack template) {
        int count = 0;
        for (int inventoryIndex = 0; inventoryIndex < Inventory.INVENTORY_SIZE; inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
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

    private int findSmallestMatchingPlayerStack(Inventory inventory,
                                                CraftingMenu handler,
                                                ItemStack template) {
        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int inventoryIndex = 0; inventoryIndex < Inventory.INVENTORY_SIZE; inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot == -1) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (!stack.isEmpty()
                    && stack.getCount() < bestCount
                    && ItemStack.isSameItemSameComponents(stack, template)) {
                bestSlot = handlerSlot;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
    }

    private boolean distributePlayerStack(Minecraft client,
                                          CraftingMenu handler,
                                          int sourceSlot,
                                          List<Integer> targetSlots,
                                          ItemStack template) {
        int sourceCount = handler.getSlot(sourceSlot).getItem().getCount();
        int targetCount = Math.min(sourceCount, targetSlots.size());
        if (targetCount <= 0) {
            return false;
        }

        List<Integer> selectedTargets = new ArrayList<>(targetSlots.subList(0, targetCount));
        client.gameMode.handleContainerInput(handler.containerId, sourceSlot, 0,
                ContainerInput.PICKUP, client.player);
        if (handler.getCarried().isEmpty()
                || !ItemStack.isSameItemSameComponents(handler.getCarried(), template)) {
            return false;
        }

        if (selectedTargets.size() == 1) {
            client.gameMode.handleContainerInput(handler.containerId, selectedTargets.getFirst(), 0,
                    ContainerInput.PICKUP, client.player);
        } else {
            client.gameMode.handleContainerInput(handler.containerId, -999,
                    AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, client.player);
            for (int targetSlot : selectedTargets) {
                client.gameMode.handleContainerInput(handler.containerId, targetSlot,
                        AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, client.player);
            }
            client.gameMode.handleContainerInput(handler.containerId, -999,
                    AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, client.player);
        }
        return returnCursorStack(client, handler, sourceSlot);
    }

    private boolean returnCursorStack(Minecraft client,
                                      CraftingMenu handler,
                                      int preferredSlot) {
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        if (canAcceptStack(handler.getSlot(preferredSlot).getItem(), handler.getCarried())) {
            client.gameMode.handleContainerInput(handler.containerId, preferredSlot, 0,
                    ContainerInput.PICKUP, client.player);
        }
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        for (int inventoryIndex = 0; inventoryIndex < Inventory.INVENTORY_SIZE; inventoryIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(inventoryIndex);
            if (handlerSlot != -1
                    && canAcceptStack(handler.getSlot(handlerSlot).getItem(), handler.getCarried())) {
                client.gameMode.handleContainerInput(handler.containerId, handlerSlot, 0,
                        ContainerInput.PICKUP, client.player);
                if (handler.getCarried().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countMatchingItems(CraftingMenu handler,
                                   List<Integer> slotIds,
                                   ItemStack template) {
        int count = 0;
        for (int slotId : slotIds) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean canAcceptStack(ItemStack target, ItemStack cursor) {
        return target.isEmpty()
                || (ItemStack.isSameItemSameComponents(target, cursor)
                && target.getCount() + cursor.getCount() <= target.getMaxStackSize());
    }

    private int playerInventoryIndexToHandlerSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 8) {
            return 37 + inventoryIndex;
        }
        if (inventoryIndex >= 9 && inventoryIndex < Inventory.INVENTORY_SIZE) {
            return 10 + inventoryIndex - 9;
        }
        return -1;
    }

    private CraftingInput createInput(CraftingMenu handler) {
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            stacks.add(handler.getSlot(slotId).getItem().copy());
        }
        return CraftingInput.of(3, 3, stacks);
    }

    private List<ItemStack> snapshotPattern(CraftingMenu handler) {
        List<ItemStack> snapshot = new ArrayList<>(9);
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            snapshot.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        return snapshot;
    }

    private void handleHotkey(Minecraft client) {
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

    private boolean isRapidInputHeld(Minecraft client) {
        return startedByButton
                ? isButtonRapidModeHeld(client)
                : QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();
    }

    private boolean isButtonRapidModeHeld(Minecraft client) {
        long window = client.getWindow().handle();
        boolean altDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        return altDown && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private boolean isWorkbenchOpen(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && client.gui.screen() instanceof CraftingScreen
                && client.player.containerMenu instanceof CraftingMenu;
    }

    private void stopHelperSafely(Minecraft client) {
        if (QuickCraftWorkbenchShulker.isShulkerCraftBusy()) {
            QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> QuickCraftWorkbenchShulker.tickShulkerCraft(client));
        }
    }

    private void stop(Minecraft client, Component message) {
        String reason = message == null ? "无" : message.getString();
        logSessionSummary(reason);
        if (QuickCraftWorkbenchShulker.isShulkerCraftBusy()) {
            QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> QuickCraftWorkbenchShulker.tickShulkerCraft(client));
            QuickCraftWorkbenchShulker.consumeShulkerCraftResult();
            QuickCraftWorkbenchShulker.consumeShulkerCraftMessage();
        }
        active = false;
        startedByButton = false;
        queueFinalCraftStats(client, reason);
        consecutiveFailures = 0;
        sendMessage(client, message);
    }

    private void reset(Minecraft client) {
        if (active) {
            String reason = "工作台关闭或世界退出";
            logSessionSummary(reason);
            active = false;
            queueFinalCraftStats(client, reason);
        }
        if (recipe != null) {
            LOGGER.debug("关闭工作台，释放潜影盒配方快照：containerId={}，配方={}", snapshotSyncId,
                    recipe == null ? "UNKNOWN" : recipe.id());
        }
        active = false;
        if (pendingCraftStats == null || !craftStatsBaselinePending) {
            craftStatsBaselinePending = false;
            craftStatsBaselineNetworkHandler = null;
            craftStatsBaselineRequestedAtNanos = 0L;
            sessionCraftStatsBaselineWaitNanos = 0L;
            sessionCraftedStatBaseline = -1;
        }
        startedByButton = false;
        consecutiveFailures = 0;
        recipe = null;
        pattern = List.of();
        remainderPattern = List.of();
        recipeHasRemainder = false;
        resultTemplate = ItemStack.EMPTY;
        snapshotSyncId = -1;
        lastSingleKeyDown = false;
        lastRapidKeyDown = false;
        sessionStartedAtNanos = 0L;
        sessionOutputClicks = 0;
        sessionOutputBursts = 0;
        sessionOutputToShulker = false;
        sessionRequiresOrderedProbe = false;
        sessionOutputBoxTakes = 0;
        sessionOutputBoxReturns = 0;
        sessionOutputBoxSwitches = 0;
        sessionOutputBoxMisses = 0;
        sessionOutputStoreFailures = 0;
        sessionMode = WorkbenchShulkerPipelineMode.RESPONSE_STABLE;
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorWaitEvents = 0;
        sessionCursorWaitTicks = 0;
        sessionCursorRecoveries = 0;
        sessionCorrectionPauseTicks = 0;
        sessionCursorSettleTicks = 0;
        sessionRecoveryPauseTicks = 0;
        sessionCursorTimeoutTicks = 0;
        sessionOutputMismatchLogged = false;
        serverOutputMismatchTicks = 0;
        clearAckSession();
        QuickCraftWorkbenchShulker.clearDebugSession();
        QuickCraftWorkbenchShulker.resetShulkerCraft();
    }

    private void logSessionSummary(String reason) {
        long elapsedMillis = sessionElapsedMillis();
        QuickCraftWorkbenchShulkerTelemetry.finish(reason);
        LOGGER.info("确认驱动流水线汇总：模式={}，发送批次={}，确认批次={}，安全重算={}，点击={}，超时={}，"
                        + "补货ACK={}批/{}盒，平均每批={}盒，组合输入输出={}批，"
                        + "静默探针={}/{}，探针超时={}，探针累计等待={} ms，"
                        + "平均确认={} ms，最小={} ms，最大={} ms，ACK累计等待={} ms/{}%，"
                        + "本地规划与切批空档={} ms，在途={}/{}/{}点击/{}全量/探针{}，停止等待={}",
                sessionMode, sessionAckBatches, sessionAckConfirmedBatches, sessionAckReconciledBatches,
                sessionAckClicks, sessionAckTimeouts,
                sessionAckRefillBatches, sessionAckRefillSourceBatches,
                averageHundredths(sessionAckRefillSourceBatches, sessionAckRefillBatches),
                sessionAckCombinedBatches,
                sessionAckStatsProbesCompleted, sessionAckStatsProbesSent,
                sessionAckStatsProbeTimeouts, nanosToMillis(sessionAckStatsProbeLatencyNanos),
                averageMillis(sessionAckLatencyNanos, sessionAckConfirmedBatches),
                nanosToMillis(sessionAckMinLatencyNanos), nanosToMillis(sessionAckMaxLatencyNanos),
                nanosToMillis(sessionAckLatencyNanos),
                percentageHundredths(nanosToMillis(sessionAckLatencyNanos), elapsedMillis),
                Math.max(0L, elapsedMillis - nanosToMillis(sessionAckLatencyNanos)),
                ackBatchAwaiting ? 1 : 0, ackBatchKind, ackBatchClickCount,
                ackBatchFullInventoryUpdates, ackStatsProbePending ? 1 : 0, ackStopRequested);
        LOGGER.info("潜影盒工作台喷射操作汇总：耗时={} ms，输出点击发送={} 次，输出Burst={}，"
                        + "输出装盒={}，盒点击={} 次（拿盒={}，归还={}，换盒={}），缺盒={}，装盒失败={}，"
                        + "来源任务={}，来源盒批次={}，光标等待={} 次/{} Tick，自动恢复={} 次，恢复静默={} Tick，"
                        + "确认驱动={}/{} 批，流水线模式={}，来源盒上限={}，光标策略={}/{}/{} Tick，"
                        + "配方={}，连续失败={}，补料中={}，结束原因={}",
                elapsedMillis, sessionOutputClicks, sessionOutputBursts,
                sessionOutputToShulker, sessionOutputBoxTakes + sessionOutputBoxReturns,
                sessionOutputBoxTakes, sessionOutputBoxReturns, sessionOutputBoxSwitches,
                sessionOutputBoxMisses, sessionOutputStoreFailures,
                QuickCraftWorkbenchShulker.debugSessionTaskCount(),
                QuickCraftWorkbenchShulker.debugSessionSourceBatches(),
                sessionCursorWaitEvents, sessionCursorWaitTicks, sessionCursorRecoveries,
                sessionCorrectionPauseTicks, sessionAckConfirmedBatches, sessionAckBatches,
                sessionMode, QuickCraftWorkbenchShulker.sourceBatchesPerAck(sessionMode),
                sessionCursorSettleTicks, sessionRecoveryPauseTicks,
                sessionCursorTimeoutTicks,
                recipe == null ? "NONE" : recipe.id(), consecutiveFailures,
                QuickCraftWorkbenchShulker.isShulkerCraftBusy(), reason);
    }

    private boolean waitForServerOutputCorrection(Minecraft client,
                                                   CraftingMenu handler) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
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
            if (!sessionOutputMismatchLogged) {
                logUnexpectedOutput(handler);
            }
            return true;
        }

        serverOutputMismatchTicks++;
        if (serverOutputMismatchTicks <= Math.max(1, sessionCursorSettleTicks)) {
            serverCorrectionPauseTicks = Math.max(serverCorrectionPauseTicks, 1);
            return true;
        }

        logUnexpectedOutput(handler);
        stop(client, Component.translatable("quickcraft.message.crafting.shulker_grid_desync"));
        return true;
    }

    static boolean canContinuePastIntermediateOutput(boolean patternShapeComplete,
                                                     boolean patternGridCompatible) {
        return !patternShapeComplete && patternGridCompatible;
    }

    private boolean isPatternShapeComplete(CraftingMenu handler) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            ItemStack actual = handler.getSlot(GRID_START + patternIndex).getItem();
            if (expected.isEmpty()) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else if (actual.isEmpty()
                    || !ItemStack.isSameItemSameComponents(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private long sessionElapsedMillis() {
        return sessionStartedAtNanos == 0L
                ? 0L
                : (System.nanoTime() - sessionStartedAtNanos) / 1_000_000L;
    }

    private static String describePattern(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                parts.add((i + 1) + "=" + stack.getHoverName().getString());
            }
        }
        return parts.toString();
    }

    private void logUnexpectedOutput(CraftingMenu handler) {
        if (sessionOutputMismatchLogged) {
            return;
        }
        sessionOutputMismatchLogged = true;
        LOGGER.error("服务端工作台输出与锁定配方不一致：会话t+{} ms，配方={}，期望输出={}，实际输出={}，"
                        + "revision={}，光标={}，合成格={}",
                sessionElapsedMillis(), recipe == null ? "NONE" : recipe.id(), describeStack(resultTemplate),
                describeStack(handler.getSlot(OUTPUT_SLOT).getItem()), handler.getStateId(),
                describeStack(handler.getCarried()), describePattern(snapshotPattern(handler)));
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "空" : stack.getHoverName().getString() + "x" + stack.getCount();
    }

    private void sendMessage(Minecraft client, Component message) {
        if (message != null && client != null && client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private record PendingCraftStats(ClientPacketListener networkHandler,
                                     Item resultItem,
                                     int outputItemsPerCraft,
                                     int baselineStat,
                                     int sentOutputClicks,
                                     long startedAtNanos,
                                     long requestedAtNanos,
                                     long baselineWaitMillis,
                                     String recipeId,
                                     String reason,
                                     WorkbenchShulkerPipelineMode pipelineMode) {
        private PendingCraftStats withBaselineStat(int baseline, long waitMillis) {
            return new PendingCraftStats(
                    networkHandler, resultItem, outputItemsPerCraft,
                    baseline, sentOutputClicks, startedAtNanos, requestedAtNanos,
                    waitMillis, recipeId, reason, pipelineMode);
        }
    }

    enum AckBatchKind {
        REFILL,
        REFILL_OUTPUT,
        OUTPUT,
        PREPARATION
    }
}
