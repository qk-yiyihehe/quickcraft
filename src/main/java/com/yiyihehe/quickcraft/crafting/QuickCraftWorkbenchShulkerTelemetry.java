package com.yiyihehe.quickcraft.crafting;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 记录工作台潜影盒合成的槽位包往返时间。
 * 服务端不会为每个点击提供显式 ACK；这里的 revision 只记录后续响应时序，不代表点击成功。
 */
public final class QuickCraftWorkbenchShulkerTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/WorkbenchShulkerCraft");
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_PENDING_ACTIONS = 4_096;
    private static final int MAX_INTEGRATED_PROBES = 16_384;
    private static final int MAX_INTEGRATED_REVISION_ARRIVALS = 2_048;

    private static final EnumMap<ActionKind, TimingStats> sessionStats = new EnumMap<>(ActionKind.class);
    private static final EnumMap<ActionKind, TimingStats> windowStats = new EnumMap<>(ActionKind.class);
    private static final EnumMap<ActionKind, IntegratedTimingStats> integratedSessionStats =
            new EnumMap<>(ActionKind.class);
    private static final Deque<PendingAction> pendingActions = new ArrayDeque<>();
    private static final Object integratedTimingLock = new Object();
    private static final Deque<IntegratedActionProbe> integratedPendingActions = new ArrayDeque<>();
    private static final Map<ClickSlotC2SPacket, IntegratedActionProbe> integratedServerActions =
            new IdentityHashMap<>();
    private static final Deque<RevisionArrival> integratedClientArrivals = new ArrayDeque<>();
    private static final ThreadLocal<Deque<StartedAction>> startedActions =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile boolean active;
    private static volatile int sessionSyncId = -1;
    private static int lastServerRevision = -1;
    private static long sessionStartedAtNanos;
    private static long windowStartedAtNanos;
    private static long lastServerPacketAtNanos;
    private static int sessionServerPackets;
    private static int windowServerPackets;
    private static int sessionRevisionAdvances;
    private static int windowRevisionAdvances;
    private static int sessionMaxPending;
    private static int windowMaxPending;
    private static int sessionPendingTicks;
    private static int sessionIdleTicks;
    private static int windowPendingTicks;
    private static int windowIdleTicks;
    private static int integratedSentActions;
    private static int integratedServerMatchedActions;
    private static int integratedCompletedActions;
    private static int integratedDroppedActions;

    private QuickCraftWorkbenchShulkerTelemetry() {
    }

    public static void begin(ScreenHandler handler) {
        clear();
        if (handler == null) {
            return;
        }
        active = true;
        sessionSyncId = handler.syncId;
        lastServerRevision = handler.getRevision();
        sessionStartedAtNanos = System.nanoTime();
        windowStartedAtNanos = sessionStartedAtNanos;
        LOGGER.info("工作台潜影盒网络时序开始：syncId={}，初始revision={}", sessionSyncId, lastServerRevision);
    }

    public static void onClientClickStart(int syncId,
                                          int slotId,
                                          int button,
                                          SlotActionType actionType,
                                          PlayerEntity player) {
        if (!isCurrentHandler(syncId, player)) {
            return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        long startedAtNanos = System.nanoTime();
        ActionKind kind = classify(slotId, button, actionType, handler);
        IntegratedActionProbe integratedProbe = new IntegratedActionProbe(
                kind, syncId, slotId, button, actionType, startedAtNanos);
        startedActions.get().addLast(new StartedAction(
                kind, syncId, slotId, button, actionType, startedAtNanos, integratedProbe));
    }

    public static void onClientClickSent(int syncId,
                                         int slotId,
                                         int button,
                                         SlotActionType actionType,
                                         PlayerEntity player) {
        Deque<StartedAction> started = startedActions.get();
        StartedAction action = started.peekLast();
        if (action == null || action.packetSent
                || !action.matches(syncId, slotId, button, actionType)
                || !isCurrentHandler(syncId, player)) {
            return;
        }

        long now = System.nanoTime();
        int revision = player.currentScreenHandler.getRevision();
        action.packetSent = true;
        TimingStats session = statsFor(sessionStats, action.kind);
        TimingStats window = statsFor(windowStats, action.kind);
        long clientNanos = now - action.startedAtNanos;
        session.recordClient(clientNanos);
        window.recordClient(clientNanos);
        if (pendingActions.size() >= MAX_PENDING_ACTIONS) {
            PendingAction expired = pendingActions.removeFirst();
            statsFor(sessionStats, expired.kind).recordUnconfirmed();
            statsFor(windowStats, expired.kind).recordUnconfirmed();
        }
        synchronized (integratedTimingLock) {
            IntegratedActionProbe probe = action.integratedProbe;
            probe.clientSentAtNanos = now;
            probe.revisionAtSend = revision;
            if (integratedPendingActions.size() >= MAX_INTEGRATED_PROBES) {
                integratedPendingActions.removeFirst();
                integratedDroppedActions++;
            }
            integratedPendingActions.addLast(probe);
            integratedSentActions++;
        }
        pendingActions.addLast(new PendingAction(action.kind, revision, now));
        sessionMaxPending = Math.max(sessionMaxPending, pendingActions.size());
        windowMaxPending = Math.max(windowMaxPending, pendingActions.size());
    }

    public static void onClientClickEnd(int syncId, PlayerEntity player) {
        Deque<StartedAction> started = startedActions.get();
        StartedAction action = started.pollLast();
        if (action != null && action.packetSent) {
            return;
        }
        if (action != null && isCurrentHandler(syncId, player)) {
            synchronized (integratedTimingLock) {
                integratedDroppedActions++;
            }
        }
    }

    public static void onIntegratedServerClickStart(ClickSlotC2SPacket packet) {
        if (!active || packet == null || packet.getSyncId() != sessionSyncId) {
            return;
        }
        long now = System.nanoTime();
        synchronized (integratedTimingLock) {
            IntegratedActionProbe matched = null;
            for (IntegratedActionProbe probe : integratedPendingActions) {
                if (probe.serverStartedAtNanos == 0L && probe.matches(packet)) {
                    matched = probe;
                    break;
                }
            }
            if (matched == null) {
                return;
            }
            matched.serverStartedAtNanos = now;
            integratedServerActions.put(packet, matched);
            integratedServerMatchedActions++;
        }
    }

    public static void onIntegratedServerClickProcessed(ClickSlotC2SPacket packet) {
        if (packet == null) {
            return;
        }
        long now = System.nanoTime();
        synchronized (integratedTimingLock) {
            IntegratedActionProbe probe = integratedServerActions.get(packet);
            if (probe == null) {
                return;
            }
            probe.serverFinishedAtNanos = now;
        }
    }

    public static void onIntegratedServerClickEnd(ClickSlotC2SPacket packet,
                                                   int responseRevision) {
        if (packet == null) {
            return;
        }
        synchronized (integratedTimingLock) {
            IntegratedActionProbe probe = integratedServerActions.remove(packet);
            if (probe == null || probe.serverFinishedAtNanos == 0L) {
                return;
            }
            probe.serverResponseRevision = responseRevision;
            applyRecordedClientArrival(probe);
            completeIntegratedTimingIfReady(probe);
        }
    }

    public static void onServerSlotUpdate(int syncId, int revision) {
        onServerUpdate(syncId, revision);
    }

    public static void onServerInventoryUpdate(int syncId, int revision) {
        onServerUpdate(syncId, revision);
    }

    public static void onClientTick(ScreenHandler handler) {
        if (!active || handler == null || handler.syncId != sessionSyncId) {
            return;
        }
        if (pendingActions.isEmpty()) {
            sessionIdleTicks++;
            windowIdleTicks++;
        } else {
            sessionPendingTicks++;
            windowPendingTicks++;
        }
        logWindowIfDue(System.nanoTime());
    }

    public static void finish(String reason) {
        if (!active) {
            clear();
            return;
        }
        long now = System.nanoTime();
        PendingAction action;
        while ((action = pendingActions.pollFirst()) != null) {
            statsFor(sessionStats, action.kind).recordUnconfirmed();
        }
        LOGGER.info("工作台潜影盒网络时序汇总：耗时={} ms，服务器回包={}，revision推进={}，"
                        + "待确认Tick={}，无待确认Tick={}，最大在途={}，结束原因={}",
                elapsedMillis(sessionStartedAtNanos, now), sessionServerPackets, sessionRevisionAdvances,
                sessionPendingTicks, sessionIdleTicks, sessionMaxPending, reason);
        logStats("总计", sessionStats);
        logIntegratedTiming();
        clear();
    }

    private static void onServerUpdate(int syncId, int revision) {
        if (!active || !isRelevantSyncId(syncId)) {
            return;
        }
        long now = System.nanoTime();
        recordIntegratedClientArrival(revision, now);
        sessionServerPackets++;
        windowServerPackets++;
        if (revision != lastServerRevision) {
            sessionRevisionAdvances++;
            windowRevisionAdvances++;
            lastServerRevision = revision;
        }
        int confirmed = 0;
        int pendingCount = pendingActions.size();
        for (int i = 0; i < pendingCount; i++) {
            PendingAction action = pendingActions.pollFirst();
            if (action == null) {
                break;
            }
            if (!isRevisionAfter(revision, action.revisionAtSend)) {
                pendingActions.addLast(action);
                continue;
            }
            long responseNanos = now - action.sentAtNanos;
            statsFor(sessionStats, action.kind).recordServer(responseNanos);
            statsFor(windowStats, action.kind).recordServer(responseNanos);
            confirmed++;
        }
        if (confirmed > 0) {
            lastServerPacketAtNanos = now;
        }
        logWindowIfDue(now);
    }

    private static boolean isCurrentHandler(int syncId, PlayerEntity player) {
        return active && player != null && player.currentScreenHandler != null
                && syncId == sessionSyncId && player.currentScreenHandler.syncId == sessionSyncId;
    }

    private static boolean isRelevantSyncId(int syncId) {
        return syncId == sessionSyncId || syncId == -1 || syncId == -2;
    }

    private static ActionKind classify(int slotId,
                                       int button,
                                       SlotActionType actionType,
                                       ScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        if (slotId == 0 && actionType == SlotActionType.THROW) {
            return ActionKind.OUTPUT_THROW;
        }
        if (slotId >= 1 && slotId <= 9) {
            return button == 1 && actionType == SlotActionType.PICKUP && isShulker(cursor)
                    ? ActionKind.DIRECT_FILL
                    : ActionKind.GRID_OPERATION;
        }
        if (slotId >= 10) {
            if (actionType == SlotActionType.PICKUP && cursor.isEmpty()) {
                return ActionKind.PICKUP_SOURCE_BOX;
            }
            if (actionType == SlotActionType.PICKUP && isShulker(cursor)) {
                return ActionKind.RETURN_SOURCE_BOX;
            }
            return ActionKind.PLAYER_INVENTORY_OPERATION;
        }
        return ActionKind.OTHER;
    }

    private static boolean isShulker(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static TimingStats statsFor(EnumMap<ActionKind, TimingStats> stats, ActionKind kind) {
        return stats.computeIfAbsent(kind, ignored -> new TimingStats());
    }

    private static IntegratedTimingStats integratedStatsFor(ActionKind kind) {
        return integratedSessionStats.computeIfAbsent(kind, ignored -> new IntegratedTimingStats());
    }

    private static void recordIntegratedClientArrival(int revision, long arrivedAtNanos) {
        synchronized (integratedTimingLock) {
            if (integratedServerMatchedActions == 0) {
                return;
            }
            if (integratedClientArrivals.size() >= MAX_INTEGRATED_REVISION_ARRIVALS) {
                integratedClientArrivals.removeFirst();
            }
            integratedClientArrivals.addLast(new RevisionArrival(revision, arrivedAtNanos));
            IntegratedActionProbe[] probes = integratedPendingActions.toArray(IntegratedActionProbe[]::new);
            for (IntegratedActionProbe probe : probes) {
                if (probe.clientAppliedAtNanos == 0L && probe.serverResponseRevision >= 0
                        && arrivedAtNanos >= probe.serverFinishedAtNanos
                        && isRevisionAtOrAfter(revision, probe.serverResponseRevision)) {
                    probe.clientAppliedAtNanos = arrivedAtNanos;
                    completeIntegratedTimingIfReady(probe);
                }
            }
        }
    }

    private static void applyRecordedClientArrival(IntegratedActionProbe probe) {
        for (RevisionArrival arrival : integratedClientArrivals) {
            if (arrival.arrivedAtNanos >= probe.serverFinishedAtNanos
                    && isRevisionAtOrAfter(arrival.revision, probe.serverResponseRevision)) {
                probe.clientAppliedAtNanos = arrival.arrivedAtNanos;
                return;
            }
        }
    }

    private static boolean isRevisionAtOrAfter(int candidate, int base) {
        return candidate == base || isRevisionAfter(candidate, base);
    }

    private static void completeIntegratedTimingIfReady(IntegratedActionProbe probe) {
        if (probe == null || probe.recorded
                || probe.clientSentAtNanos == 0L
                || probe.serverStartedAtNanos == 0L
                || probe.serverFinishedAtNanos == 0L
                || probe.clientAppliedAtNanos == 0L) {
            return;
        }
        long clientPredictionNanos = phaseDurationNanos(
                probe.clientStartedAtNanos, probe.clientSentAtNanos);
        long outboundNanos = phaseDurationNanos(
                probe.clientSentAtNanos, probe.serverStartedAtNanos);
        long serverProcessingNanos = phaseDurationNanos(
                probe.serverStartedAtNanos, probe.serverFinishedAtNanos);
        long inboundAndApplyNanos = phaseDurationNanos(
                probe.serverFinishedAtNanos, probe.clientAppliedAtNanos);
        long roundTripNanos = phaseDurationNanos(
                probe.clientSentAtNanos, probe.clientAppliedAtNanos);
        if (clientPredictionNanos < 0L || outboundNanos < 0L || serverProcessingNanos < 0L
                || inboundAndApplyNanos < 0L || roundTripNanos < 0L) {
            return;
        }
        probe.recorded = true;
        integratedPendingActions.remove(probe);
        integratedStatsFor(probe.kind).record(clientPredictionNanos, outboundNanos,
                serverProcessingNanos, inboundAndApplyNanos, roundTripNanos);
        integratedCompletedActions++;
    }

    static long phaseDurationNanos(long startedAtNanos, long finishedAtNanos) {
        return startedAtNanos > 0L && finishedAtNanos >= startedAtNanos
                ? finishedAtNanos - startedAtNanos : -1L;
    }

    private static void logWindowIfDue(long now) {
        if (!active || now - windowStartedAtNanos < WINDOW_NANOS) {
            return;
        }
        LOGGER.info("工作台潜影盒网络时序窗口：t+{} ms，服务器回包={}，revision推进={}，"
                        + "待确认Tick={}，无待确认Tick={}，当前在途={}，窗口最大在途={}",
                elapsedMillis(sessionStartedAtNanos, now), windowServerPackets, windowRevisionAdvances,
                windowPendingTicks, windowIdleTicks, pendingActions.size(), windowMaxPending);
        logStats("窗口", windowStats);
        windowStats.clear();
        windowServerPackets = 0;
        windowRevisionAdvances = 0;
        windowPendingTicks = 0;
        windowIdleTicks = 0;
        windowMaxPending = pendingActions.size();
        windowStartedAtNanos = now;
    }

    private static void logStats(String scope, EnumMap<ActionKind, TimingStats> stats) {
        for (ActionKind kind : ActionKind.values()) {
            TimingStats timing = stats.get(kind);
            if (timing == null || timing.sentCount == 0) {
                continue;
            }
            LOGGER.info("工作台潜影盒网络时序{}：动作={}，发送={}，revision响应={}，未响应={}，"
                            + "客户端预测={} us/次，完整RTT={} ms/次，最小={} ms，最大={} ms",
                    scope, kind.displayName, timing.sentCount, timing.confirmedCount, timing.unconfirmedCount,
                    timing.averageClientMicros(), timing.averageServerMillis(),
                    timing.minServerMillis(), timing.maxServerMillis());
        }
    }

    private static void logIntegratedTiming() {
        synchronized (integratedTimingLock) {
            if (integratedServerMatchedActions == 0) {
                return;
            }
            IntegratedTimingStats total = new IntegratedTimingStats();
            for (ActionKind kind : ActionKind.values()) {
                IntegratedTimingStats timing = integratedSessionStats.get(kind);
                if (timing != null) {
                    total.add(timing);
                }
            }
            LOGGER.info("工作台潜影盒集成服务器阶段汇总：客户端发送={}，服务端匹配={}，完整闭环={}，"
                            + "未闭环={}，探针丢弃={}，平均阶段(us)=预测{}/去程排队{}/服务端处理{}/"
                            + "回程及客户端应用{}/完整RTT{}，最大阶段(us)=预测{}/去程排队{}/"
                            + "服务端处理{}/回程及客户端应用{}/完整RTT{}",
                    integratedSentActions, integratedServerMatchedActions, integratedCompletedActions,
                    integratedServerMatchedActions - integratedCompletedActions, integratedDroppedActions,
                    total.averageClientPredictionMicros(), total.averageOutboundMicros(),
                    total.averageServerProcessingMicros(), total.averageInboundAndApplyMicros(),
                    total.averageRoundTripMicros(), total.maxClientPredictionMicros(),
                    total.maxOutboundMicros(), total.maxServerProcessingMicros(),
                    total.maxInboundAndApplyMicros(), total.maxRoundTripMicros());
            for (ActionKind kind : ActionKind.values()) {
                IntegratedTimingStats timing = integratedSessionStats.get(kind);
                if (timing == null || timing.completedCount == 0L) {
                    continue;
                }
                LOGGER.info("工作台潜影盒集成服务器阶段总计：动作={}，闭环={}，"
                                + "平均(us)=预测{}/去程排队{}/服务端处理{}/回程及客户端应用{}/完整RTT{}",
                        kind.displayName, timing.completedCount,
                        timing.averageClientPredictionMicros(), timing.averageOutboundMicros(),
                        timing.averageServerProcessingMicros(), timing.averageInboundAndApplyMicros(),
                        timing.averageRoundTripMicros());
            }
        }
    }

    private static long elapsedMillis(long startedAtNanos, long now) {
        return Math.max(0L, (now - startedAtNanos) / 1_000_000L);
    }

    static boolean isRevisionAfter(int candidate, int base) {
        int distance = candidate - base & 32767;
        return distance > 0 && distance < 16384;
    }

    private static void clear() {
        active = false;
        sessionSyncId = -1;
        lastServerRevision = -1;
        sessionStartedAtNanos = 0L;
        windowStartedAtNanos = 0L;
        lastServerPacketAtNanos = 0L;
        sessionServerPackets = 0;
        windowServerPackets = 0;
        sessionRevisionAdvances = 0;
        windowRevisionAdvances = 0;
        sessionMaxPending = 0;
        windowMaxPending = 0;
        sessionPendingTicks = 0;
        sessionIdleTicks = 0;
        windowPendingTicks = 0;
        windowIdleTicks = 0;
        sessionStats.clear();
        windowStats.clear();
        pendingActions.clear();
        startedActions.get().clear();
        synchronized (integratedTimingLock) {
            integratedSessionStats.clear();
            integratedPendingActions.clear();
            integratedServerActions.clear();
            integratedClientArrivals.clear();
            integratedSentActions = 0;
            integratedServerMatchedActions = 0;
            integratedCompletedActions = 0;
            integratedDroppedActions = 0;
        }
    }

    private enum ActionKind {
        PICKUP_SOURCE_BOX("拿来源盒"),
        DIRECT_FILL("潜影盒直填"),
        RETURN_SOURCE_BOX("归还潜影盒"),
        OUTPUT_THROW("输出丢弃"),
        GRID_OPERATION("合成格操作"),
        PLAYER_INVENTORY_OPERATION("背包操作"),
        OTHER("其他槽位操作");

        private final String displayName;

        ActionKind(String displayName) {
            this.displayName = displayName;
        }
    }

    private static final class StartedAction {
        private final ActionKind kind;
        private final int syncId;
        private final int slotId;
        private final int button;
        private final SlotActionType actionType;
        private final long startedAtNanos;
        private final IntegratedActionProbe integratedProbe;
        private boolean packetSent;

        private StartedAction(ActionKind kind,
                              int syncId,
                              int slotId,
                              int button,
                              SlotActionType actionType,
                              long startedAtNanos,
                              IntegratedActionProbe integratedProbe) {
            this.kind = kind;
            this.syncId = syncId;
            this.slotId = slotId;
            this.button = button;
            this.actionType = actionType;
            this.startedAtNanos = startedAtNanos;
            this.integratedProbe = integratedProbe;
        }

        private boolean matches(int candidateSyncId,
                                int candidateSlotId,
                                int candidateButton,
                                SlotActionType candidateActionType) {
            return syncId == candidateSyncId && slotId == candidateSlotId
                    && button == candidateButton && actionType == candidateActionType;
        }
    }

    private record PendingAction(ActionKind kind,
                                 int revisionAtSend,
                                 long sentAtNanos) {
    }

    private record RevisionArrival(int revision, long arrivedAtNanos) {
    }

    private static final class IntegratedActionProbe {
        private final ActionKind kind;
        private final int syncId;
        private final int slotId;
        private final int button;
        private final SlotActionType actionType;
        private final long clientStartedAtNanos;
        private int revisionAtSend = -1;
        private long clientSentAtNanos;
        private long serverStartedAtNanos;
        private long serverFinishedAtNanos;
        private int serverResponseRevision = -1;
        private long clientAppliedAtNanos;
        private boolean recorded;

        private IntegratedActionProbe(ActionKind kind,
                                      int syncId,
                                      int slotId,
                                      int button,
                                      SlotActionType actionType,
                                      long clientStartedAtNanos) {
            this.kind = kind;
            this.syncId = syncId;
            this.slotId = slotId;
            this.button = button;
            this.actionType = actionType;
            this.clientStartedAtNanos = clientStartedAtNanos;
        }

        private boolean matches(ClickSlotC2SPacket packet) {
            return syncId == packet.getSyncId() && revisionAtSend == packet.getRevision()
                    && slotId == packet.getSlot() && button == packet.getButton()
                    && actionType == packet.getActionType();
        }
    }

    private static final class TimingStats {
        private long sentCount;
        private long confirmedCount;
        private long unconfirmedCount;
        private long clientNanos;
        private long serverNanos;
        private long minServerNanos = Long.MAX_VALUE;
        private long maxServerNanos;

        private void recordClient(long nanos) {
            sentCount++;
            clientNanos += Math.max(0L, nanos);
        }

        private void recordServer(long nanos) {
            long safeNanos = Math.max(0L, nanos);
            confirmedCount++;
            serverNanos += safeNanos;
            minServerNanos = Math.min(minServerNanos, safeNanos);
            maxServerNanos = Math.max(maxServerNanos, safeNanos);
        }

        private void recordUnconfirmed() {
            unconfirmedCount++;
        }

        private long averageClientMicros() {
            return sentCount == 0 ? 0L : clientNanos / sentCount / 1_000L;
        }

        private long averageServerMillis() {
            return confirmedCount == 0 ? 0L : serverNanos / confirmedCount / 1_000_000L;
        }

        private long minServerMillis() {
            return confirmedCount == 0 ? 0L : minServerNanos / 1_000_000L;
        }

        private long maxServerMillis() {
            return confirmedCount == 0 ? 0L : maxServerNanos / 1_000_000L;
        }
    }

    private static final class IntegratedTimingStats {
        private long completedCount;
        private long clientPredictionNanos;
        private long outboundNanos;
        private long serverProcessingNanos;
        private long inboundAndApplyNanos;
        private long roundTripNanos;
        private long maxClientPredictionNanos;
        private long maxOutboundNanos;
        private long maxServerProcessingNanos;
        private long maxInboundAndApplyNanos;
        private long maxRoundTripNanos;

        private void record(long clientPrediction,
                            long outbound,
                            long serverProcessing,
                            long inboundAndApply,
                            long roundTrip) {
            completedCount++;
            clientPredictionNanos += clientPrediction;
            outboundNanos += outbound;
            serverProcessingNanos += serverProcessing;
            inboundAndApplyNanos += inboundAndApply;
            roundTripNanos += roundTrip;
            maxClientPredictionNanos = Math.max(maxClientPredictionNanos, clientPrediction);
            maxOutboundNanos = Math.max(maxOutboundNanos, outbound);
            maxServerProcessingNanos = Math.max(maxServerProcessingNanos, serverProcessing);
            maxInboundAndApplyNanos = Math.max(maxInboundAndApplyNanos, inboundAndApply);
            maxRoundTripNanos = Math.max(maxRoundTripNanos, roundTrip);
        }

        private void add(IntegratedTimingStats other) {
            completedCount += other.completedCount;
            clientPredictionNanos += other.clientPredictionNanos;
            outboundNanos += other.outboundNanos;
            serverProcessingNanos += other.serverProcessingNanos;
            inboundAndApplyNanos += other.inboundAndApplyNanos;
            roundTripNanos += other.roundTripNanos;
            maxClientPredictionNanos = Math.max(maxClientPredictionNanos, other.maxClientPredictionNanos);
            maxOutboundNanos = Math.max(maxOutboundNanos, other.maxOutboundNanos);
            maxServerProcessingNanos = Math.max(maxServerProcessingNanos, other.maxServerProcessingNanos);
            maxInboundAndApplyNanos = Math.max(maxInboundAndApplyNanos, other.maxInboundAndApplyNanos);
            maxRoundTripNanos = Math.max(maxRoundTripNanos, other.maxRoundTripNanos);
        }

        private long averageClientPredictionMicros() {
            return averageMicros(clientPredictionNanos);
        }

        private long averageOutboundMicros() {
            return averageMicros(outboundNanos);
        }

        private long averageServerProcessingMicros() {
            return averageMicros(serverProcessingNanos);
        }

        private long averageInboundAndApplyMicros() {
            return averageMicros(inboundAndApplyNanos);
        }

        private long averageRoundTripMicros() {
            return averageMicros(roundTripNanos);
        }

        private long averageMicros(long nanos) {
            return completedCount == 0L ? 0L : nanos / completedCount / 1_000L;
        }

        private long maxClientPredictionMicros() {
            return maxClientPredictionNanos / 1_000L;
        }

        private long maxOutboundMicros() {
            return maxOutboundNanos / 1_000L;
        }

        private long maxServerProcessingMicros() {
            return maxServerProcessingNanos / 1_000L;
        }

        private long maxInboundAndApplyMicros() {
            return maxInboundAndApplyNanos / 1_000L;
        }

        private long maxRoundTripMicros() {
            return maxRoundTripNanos / 1_000L;
        }
    }
}
