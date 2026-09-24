package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.HashedStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 普通持续合成的手动补货 ACK 执行器。
 * 不再发送配方书请求；每批用鼠标点击把原料均分进合成格，再取产物，然后等服务端终态。
 */
public final class QuickCraftRecipeBookAckExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/RecipeBookCraft");
    private static final long MIN_ACK_TIMEOUT_MILLIS = 3_000L;
    private static final long MAX_ACK_TIMEOUT_MILLIS = 15_000L;
    private static final int PERIODIC_BATCH_LOG_INTERVAL = 10;
    private static final int FORCED_REVISION_MISMATCH = 0x8000;
    private static final long CANCELED_BATCH_DRAIN_TIMEOUT_NANOS = 15_000_000_000L;
    private static final int MAX_REFILL_RETRIES = 3;
    private static final int MAX_DRAIN_RETRIES = 3;
    private static final int MAX_PICKUP_WAIT_TICKS = 5;
    private static final int MAX_PICKUP_WAIT_BATCHES = 3;
    private static final int MAX_CURSOR_RECOVERY_ATTEMPTS = 4;
    private static QuickCraftRecipeBookAckExecutor activeExecutor;
    private static final List<CanceledBatchDrain> CANCELED_BATCH_DRAINS = new ArrayList<>();

    private final QuickCraftRecipeBookLayout.Layout layout;
    private final LegacyFallbackHandler fallbackHandler;
    private final SnapshotRefillHandler snapshotRefillHandler;
    private final IngredientAvailabilityHandler ingredientAvailabilityHandler;
    private Session session;
    private int dispatchedThisTick;
    private boolean pumping;
    private boolean pumpAgain;
    private boolean dispatchingBatch;
    private long dispatchingBatchLogId;

    public QuickCraftRecipeBookAckExecutor(QuickCraftRecipeBookLayout.Layout layout) {
        this(layout, null, null, null);
    }

    public QuickCraftRecipeBookAckExecutor(QuickCraftRecipeBookLayout.Layout layout,
                                           LegacyFallbackHandler fallbackHandler) {
        this(layout, fallbackHandler, null, null);
    }

    public QuickCraftRecipeBookAckExecutor(QuickCraftRecipeBookLayout.Layout layout,
                                           LegacyFallbackHandler fallbackHandler,
                                           SnapshotRefillHandler snapshotRefillHandler) {
        this(layout, fallbackHandler, snapshotRefillHandler, null);
    }

    public QuickCraftRecipeBookAckExecutor(QuickCraftRecipeBookLayout.Layout layout,
                                           LegacyFallbackHandler fallbackHandler,
                                           SnapshotRefillHandler snapshotRefillHandler,
                                           IngredientAvailabilityHandler ingredientAvailabilityHandler) {
        this.layout = layout;
        this.fallbackHandler = fallbackHandler;
        this.snapshotRefillHandler = snapshotRefillHandler;
        this.ingredientAvailabilityHandler = ingredientAvailabilityHandler;
    }

    public boolean isActive() {
        return activeExecutor == this && session != null;
    }

    public static boolean shouldBlockScreenInput() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && !client.player.containerMenu.getCarried().isEmpty()) {
            return false;
        }
        if (activeExecutor != null && activeExecutor.isActive()) {
            return true;
        }
        return client.player != null
                && isCanceledBatchDraining(client.player.containerMenu);
    }

    public boolean owns(AbstractContainerMenu handler) {
        return isActive() && session.handler == handler && session.containerId == handler.containerId;
    }

    /**
     * Rebuilds the client-side result after a local output/refill click. The vanilla
     * handler deliberately skips updateResult on the client, so without this small
     * prediction the next output is not visible until the server ACK arrives.
     */
    public static boolean refreshClientPrediction(AbstractContainerMenu handler,
                                                  Level world,
                                                  CraftingContainer craftingInventory,
                                                  ResultContainer resultInventory) {
        if (craftingInventory == null) {
            return false;
        }
        return refreshClientPrediction(handler, world, craftingInventory.asCraftInput(), resultInventory);
    }

    private static boolean refreshClientPrediction(AbstractContainerMenu handler,
                                                   Level world,
                                                   CraftingInput input,
                                                   ResultContainer resultInventory) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active == null || !active.isActive()
                || world == null || !world.isClientSide()
                || handler == null || input == null || resultInventory == null) {
            return false;
        }
        Session current = active.session;
        if (current.awaiting || current.handler != handler || current.containerId != handler.containerId) {
            return false;
        }

        ItemStack predicted = QuickCraftRecipeBookInventory.isPatternComplete(
                handler, active.layout, current.pattern)
                ? current.resultTemplate.copy()
                : ItemStack.EMPTY;

        resultInventory.setItem(QuickCraftRecipeBookLayout.OUTPUT_SLOT, predicted);
        if (LOGGER.isDebugEnabled() && (predicted.isEmpty() || current.batchId <= 2L)) {
            LOGGER.debug("普通配方书客户端预测刷新：界面={}，批次=#{}，输出={}，格子={}",
                    active.layout.name(), current.batchId, describeStack(predicted),
                    active.describeGrid(handler));
        }
        return true;
    }

    /** Refreshes the result slot using the handler's crafting inventory when available. */
    private static void refreshClientPrediction(AbstractContainerMenu handler) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || handler == null) {
            return;
        }
        try {
            if (!(handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).container
                    instanceof ResultContainer resultInventory)) {
                return;
            }
            List<ItemStack> stacks = new ArrayList<>(active.layout.gridSize());
            for (int i = 0; i < active.layout.gridSize(); i++) {
                stacks.add(handler.getSlot(active.layout.gridSlotId(i)).getItem().copy());
            }
            CraftingInput input = CraftingInput.of(
                    active.layout.gridWidth(), active.layout.gridHeight(), stacks);
            refreshClientPrediction(handler, Minecraft.getInstance().level, input, resultInventory);
        } catch (Throwable throwable) {
            LOGGER.debug("普通配方书客户端预测刷新跳过：界面={}，批次=#{}",
                    active.layout.name(), active.session.batchId, throwable);
        }
    }

    public boolean canStart(AbstractContainerMenu handler, ItemStack resultTemplate) {
        if (handler == null || resultTemplate == null || resultTemplate.isEmpty()) {
            return false;
        }
        if (isCanceledBatchDraining(handler)) {
            return false;
        }
        if (!handler.getCarried().isEmpty()) {
            return false;
        }
        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
        return output.isEmpty() || isExpectedOutput(output, resultTemplate);
    }

    public boolean start(Minecraft client,
                         AbstractContainerMenu handler,
                         RecipeDisplayId recipeId,
                         ItemStack resultTemplate,
                         java.util.List<ItemStack> pattern,
                         BooleanSupplier inputHeld,
                         FinishHandler finishHandler) {
        if (client == null || client.player == null || client.level == null
                || client.gameMode == null || client.getConnection() == null
                || handler == null || recipeId == null
                || resultTemplate == null || resultTemplate.isEmpty()
                || pattern == null || pattern.isEmpty()
                || inputHeld == null || finishHandler == null
                || (activeExecutor != null && activeExecutor != this)
                || !canStart(handler, resultTemplate)) {
            return false;
        }

        if (isActive()) {
            return owns(handler);
        }

        session = new Session(
                handler,
                handler.containerId,
                recipeId,
                resultTemplate.copy(),
                copyPattern(pattern),
                inputHeld,
                finishHandler,
                client.getConnection(),
                client.player,
                System.nanoTime()
        );
        activeExecutor = this;
        dispatchedThisTick = 0;
        LOGGER.info("手动补货ACK会话开始：界面={}，配方={}，产物={}，syncId={}，revision={}，"
                        + "每Tick批次上限={}，停止尾货丢弃={}，按住={}",
                layout.name(), recipeId, describeStack(resultTemplate), handler.containerId,
                handler.getStateId(), QuickCraftConfigs.getCraftLoopsPerTick(),
                QuickCraftConfigs.isDropCraftResultsOnStopEnabled(),
                session.inputHeld.getAsBoolean());
        return true;
    }

    public void tick(Minecraft client) {
        if (!isActive()) {
            return;
        }
        dispatchedThisTick = 0;
        Session current = session;
        if (!isContextValid(client, current)) {
            cancel("界面关闭或同步ID改变");
            return;
        }
        if (current.awaiting) {
            long now = System.nanoTime();
            long elapsedMillis = nanosToMillis(now - current.batchSentAtNanos);
            long stalledMillis = nanosToMillis(now - current.lastProgressAtNanos);
            long timeoutMillis = ackTimeoutMillis(current.maxAckLatencyNanos);
            if (stalledMillis >= timeoutMillis || elapsedMillis >= MAX_ACK_TIMEOUT_MILLIS) {
                current.timeouts++;
                LOGGER.warn("手动补货ACK超时：界面={}，批次=#{}，配方={}，等待={} ms，阈值={} ms，"
                                + "revision={}->{}, 空边界={}，正确产物={}，配方终态全量={}@{}，"
                                + "统计确认={}/{}, pending={}，当前配方匹配={}，失败包={}，光标={}，输出={}",
                        layout.name(), current.batchId, current.recipeId, elapsedMillis,
                        timeoutMillis, current.batchStartRevision, current.handler.getStateId(),
                        current.outputAck.sawAuthoritativeEmpty(),
                        current.outputAck.sawExpectedAfterEmpty(),
                        current.outputAck.sawRecipeReadyFull(),
                        current.outputAck.recipeReadyFullRevision(),
                        current.statsProbeResponses, current.statsProbesSent,
                        current.statsProbePending,
                        recipeMatches(current),
                        current.craftFailed, describeStack(current.handler.getCarried()),
                        describeStack(current.handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem()));
                finish(Component.translatable("quickcraft.message.crafting.no_ingredients"), "ACK超时");
            }
            return;
        }
        if (current.pickupWaitTicks > 0) {
            if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
                current.pickupWaitTicks = 0;
                finish(current.stopMessage != null
                        ? current.stopMessage
                        : Component.translatable("quickcraft.message.crafting.stopped"),
                        "拾取等待期间输入释放");
                return;
            }
            boolean ingredientsAvailable = ingredientAvailabilityHandler != null
                    && ingredientAvailabilityHandler.hasIngredients(current.handler);
            current.pickupWaitTicks--;
            if (shouldResumePickupWait(ingredientsAvailable, current.pickupWaitTicks)) {
                current.pickupWaitTicks = 0;
                LOGGER.info("手动补货ACK拾取等待结束，重新检查原料：界面={}，批次=#{}，原因={}，"
                                + "格子={}，按住={}",
                        layout.name(), current.batchId,
                        ingredientsAvailable ? "检测到可补原料" : "等待到期",
                        describeGrid(current.handler),
                        current.inputHeld.getAsBoolean());
                pump(client);
            }
            return;
        }
        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finish(current.stopMessage != null
                    ? current.stopMessage
                    : Component.translatable("quickcraft.message.crafting.stopped"), "输入释放");
            return;
        }
        pump(client);
    }

    public void requestStop(Minecraft client, Component message) {
        if (!isActive()) {
            return;
        }
        session.stopRequested = true;
        session.stopMessage = message;
        if (!session.awaiting && isContextValid(client, session)) {
            finish(message, "停止请求");
        }
    }

    public void cancel(String reason) {
        if (!isActive()) {
            return;
        }
        Session canceled = session;
        if (canceled.awaiting) {
            beginCanceledBatchDrain(canceled, layout);
        }
        clearSession();
        logSummary(canceled, reason, 0, canceled.inputHeld.getAsBoolean());
    }

    private void pump(Minecraft client) {
        if (pumping) {
            pumpAgain = true;
            return;
        }
        pumping = true;
        try {
            do {
                pumpAgain = false;
                if (!isActive() || session.pickupWaitTicks > 0) {
                    return;
                }
                while (isActive() && !session.awaiting
                        && canDispatchWithinTick(
                        dispatchedThisTick,
                        QuickCraftConfigs.getCraftLoopsPerTick())) {
                    if (session.stopRequested || !session.inputHeld.getAsBoolean()) {
                        finish(session.stopMessage != null
                                ? session.stopMessage
                                : Component.translatable("quickcraft.message.crafting.stopped"), "输入释放");
                        return;
                    }
                    if (!dispatchBatch(client)) {
                        return;
                    }
                }
            } while (pumpAgain && isActive() && !session.awaiting);
        } finally {
            pumping = false;
            pumpAgain = false;
        }
    }

    private boolean dispatchBatch(Minecraft client) {
        Session current = session;
        AbstractContainerMenu handler = current.handler;
        ClientPacketListener networkHandler = client.getConnection();
        MultiPlayerGameMode interactionManager = client.gameMode;
        if (client.player == null || interactionManager == null || networkHandler == null) {
            cancel("发送批次前客户端上下文失效");
            return false;
        }

        dispatchingBatch = true;
        dispatchingBatchLogId = current.batchId + 1L;
        try {
            if (!handler.getCarried().isEmpty()) {
                LOGGER.warn("手动补货ACK拒绝在光标有物品时继续发包：界面={}，批次=#{}，光标={}",
                        layout.name(), current.batchId, describeStack(handler.getCarried()));
                finish(Component.translatable("quickcraft.message.crafting.stopped"), "光标非空");
                return false;
            }

            ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
            if (!output.isEmpty()
                    && !isExpectedOutput(output, current.resultTemplate)
                    && !isIntermediateOutput(current, handler)) {
                LOGGER.warn("手动补货ACK输出与快照不一致：界面={}，配方={}，期望={}，实际={}",
                        layout.name(), current.recipeId, describeStack(current.resultTemplate),
                        describeStack(output));
                finish(Component.translatable("quickcraft.message.crafting.stopped"), "输出不一致");
                return false;
            }
            current.outputExpectedAtDispatch = isExpectedOutput(output, current.resultTemplate);

            long dispatchStartedAtNanos = System.nanoTime();
            int[] gridBeforeClicks = snapshotGridCounts(handler);
            int[] authoritativeMaterialCountsBefore = current.lastAuthoritativeMaterialCounts == null
                    ? null
                    : current.lastAuthoritativeMaterialCounts.clone();
            String materialsBefore = describeMaterialLedger(current, handler);
            int operationBudget = Math.max(1, QuickCraftConfigs.getCraftLoopsPerTick());
            int outputOperations = 0;
            int outputSlotClicksBefore = current.outputSlotClicks;
            boolean refilled = false;

            if (!isExpectedOutput(output, current.resultTemplate)) {
                refilled = refillSnapshot(client, handler, operationBudget);
                refreshClientPrediction(handler);
                if (!parkCursor(client, handler, "手动补货后")) {
                    finish(Component.translatable("quickcraft.message.crafting.stopped"), "手动补货后光标无法放回");
                    return false;
                }
                output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
            }

            int[] gridBeforeOutput = snapshotGridCounts(handler);
            boolean patternComplete = QuickCraftRecipeBookInventory.isPatternComplete(
                    handler, layout, current.pattern);
            int plannedOutputThrows = plannedOutputThrows(
                    isExpectedOutput(output, current.resultTemplate), patternComplete);
            if (plannedOutputThrows > 0) {
                // 1.21.3 的结果槽 THROW(button=1) 等同原版 Ctrl+丢弃：
                // 服务端会连续合成并丢出全部同类结果，因此每个 ACK 批次只能发送一次。
                interactionManager.handleContainerInput(
                        handler.containerId,
                        QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                        1,
                        ContainerInput.THROW,
                        client.player
                );
                outputOperations = plannedOutputThrows;
                current.outputThrows++;
                current.outputExpectedAtDispatch = true;
                refreshClientPrediction(handler);
            } else if (!refilled && output.isEmpty() && !patternComplete) {
                boolean hasIngredients = ingredientAvailabilityHandler != null
                        && ingredientAvailabilityHandler.hasIngredients(handler);
                LOGGER.info("手动补货ACK本地未补上，发送屏障后重试：界面={}，批次=#{}，"
                                + "背包可补={}，格子={}",
                        layout.name(), current.batchId + 1, hasIngredients,
                        describeGrid(handler));
            }

            if (!parkCursor(client, handler, "批次发送前")) {
                finish(Component.translatable("quickcraft.message.crafting.stopped"), "批次发送前光标无法放回");
                return false;
            }

            BatchPath path;
            if (outputOperations > 0 && refilled) {
                path = BatchPath.MANUAL_COMBINED;
            } else if (outputOperations > 0) {
                path = BatchPath.OUTPUT_DRAIN;
            } else {
                path = BatchPath.MANUAL_REFILL;
            }

            recordOutputActions(current, outputOperations);
            String materialsAfter = describeMaterialLedger(current, handler);
            beginAwaitingBatch(current, handler, path, gridBeforeClicks, gridBeforeOutput);
            current.batchOutputOperations = outputOperations;
            current.batchOutputSlotClicks = Math.max(0,
                    current.outputSlotClicks - outputSlotClicksBefore);
            current.batchAuthoritativeMaterialCountsBefore = authoritativeMaterialCountsBefore;
            if (refilled) {
                current.snapshotRefillBatches++;
                current.snapshotRefillSuccesses++;
            }

            try {
                sendAckBoundary(networkHandler, handler, current);
            } catch (Throwable throwable) {
                LOGGER.warn("手动补货ACK屏障发送失败：界面={}，批次=#{}，配方={}",
                        layout.name(), current.batchId, current.recipeId, throwable);
                finish(Component.translatable("quickcraft.message.crafting.stopped"), "屏障发送异常");
                return false;
            }

            long dispatchMicros = (System.nanoTime() - dispatchStartedAtNanos) / 1_000L;
            current.localDispatchNanos += System.nanoTime() - dispatchStartedAtNanos;
            if (shouldInfoLogBatch(current.batchId)) {
                LOGGER.info("手动补货ACK批次发送：界面={}，批次=#{}，路径={}，配方={}，"
                                + "输出槽点击={}，输出槽丢出={}，补料={}，revision={}，耗时={} us，"
                                + "点击前格子={}，当前格子={}，材料前={}，材料后={}，按住={}",
                        layout.name(), current.batchId, path.description, current.recipeId,
                        current.batchOutputSlotClicks, outputOperations, refilled,
                        current.batchStartRevision, dispatchMicros,
                        describeGridCounts(gridBeforeClicks), describeGrid(handler),
                        materialsBefore, materialsAfter, current.inputHeld.getAsBoolean());
            }
            logBatchDispatch(current, outputOperations, dispatchMicros,
                    path.description + "+全量屏障+统计确认");
            return true;
        } finally {
            dispatchingBatch = false;
        }
    }
    private boolean refillSnapshot(Minecraft client,
                                   AbstractContainerMenu handler,
                                   int operationBudget) {
        return snapshotRefillHandler != null
                && snapshotRefillHandler.refill(client, handler, operationBudget);
    }

    private void beginAwaitingBatch(Session current,
                                    AbstractContainerMenu handler,
                                    BatchPath path,
                                    int[] gridBeforeClicks,
                                    int[] gridBeforeOutput) {
        current.batchId++;
        current.batches++;
        current.batchPath = path;
        current.batchStartRevision = handler.getStateId();
        current.lastProgressRevision = current.batchStartRevision;
        current.batchFullUpdatesStart = current.fullUpdates;
        current.outputAck.reset(current.batchStartRevision);
        current.batchGridCounts = gridBeforeClicks != null
                ? gridBeforeClicks
                : snapshotGridCounts(handler);
        current.batchCraftGridCounts = gridBeforeOutput != null
                ? gridBeforeOutput
                : current.batchGridCounts;
        current.craftFailed = false;
        current.unexpectedOutputLogged = false;
        current.invalidAuthoritativeOutput = false;
        current.batchSawExpectedOutput = false;
        current.intermediateOutputPackets = 0;
        current.authoritativeWrongOutputPackets = 0;
        current.lastAuthoritativeMaterialLedger = "未收到";
        current.statsProbePending = true;
        current.cursorRecoveryActive = false;
        current.cursorRecoveryAttempts = 0;
        current.firstFullUpdateAtNanos = 0L;
        current.statsResponseAtNanos = 0L;
        current.awaiting = true;
        current.batchSentAtNanos = System.nanoTime();
        current.lastProgressAtNanos = current.batchSentAtNanos;
        dispatchedThisTick++;
    }

    private void sendAckBoundary(ClientPacketListener networkHandler,
                                 AbstractContainerMenu handler,
                                 Session current) {
        sendFullSyncBarrier(networkHandler, handler);
        current.barriersSent++;
        networkHandler.send(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.REQUEST_STATS));
        current.statsProbesSent++;
    }

    private void sendFullSyncBarrier(ClientPacketListener networkHandler,
                                     AbstractContainerMenu handler) {
        // 1.21 的 revision 始终限制在 0..32767。slot=-1 对 QUICK_MOVE 是合法空操作；
        // 故意使用范围外 revision 可在不改物品的前提下强制服务端回传一次全量库存。
        networkHandler.send(new ServerboundContainerClickPacket(
                handler.containerId,
                FORCED_REVISION_MISMATCH,
                (short) -1,
                (byte) 0,
                ContainerInput.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(),
                HashedStack.create(handler.getCarried(), networkHandler.decoratedHashOpsGenenerator())
        ));
    }

    private void handleServerSlotUpdate(int syncId, int revision, int slotId, ItemStack stack) {
        if (!isActive() || !session.awaiting || syncId != session.containerId) {
            return;
        }
        Session current = session;
        current.slotUpdates++;
        recordPacketProgress(current, revision,
                slotId == QuickCraftRecipeBookLayout.OUTPUT_SLOT || layout.isGridSlot(slotId));
        if (slotId == QuickCraftRecipeBookLayout.OUTPUT_SLOT) {
            observeOutput(current, revision, stack, true);
            if (stack != null && !stack.isEmpty()
                    && !isExpectedOutput(stack, current.resultTemplate)
                    && isIntermediateOutput(current, current.handler)
                    && current.handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).container
                    instanceof ResultContainer resultInventory) {
                resultInventory.setItem(QuickCraftRecipeBookLayout.OUTPUT_SLOT, ItemStack.EMPTY);
                current.intermediateOutputPackets++;
                current.intermediateOutputPacketsTotal++;
                LOGGER.info("普通配方书ACK清除客户端中间产物：界面={}，批次=#{}，"
                                + "实际={}，revision={}，格子={}；等待完整配方回包",
                        layout.name(), current.batchId, describeStack(stack), revision,
                        describeGrid(current.handler));
            }
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("手动补货ACK槽位回包：界面={}，批次=#{}，slot={}，stack={}，revision={}，t+{} ms",
                    layout.name(), current.batchId, slotId, describeStack(stack), revision,
                    nanosToMillis(System.nanoTime() - current.batchSentAtNanos));
        }
    }

    private void handleServerInventoryUpdate(int syncId,
                                             int revision,
                                             List<ItemStack> contents,
                                             ItemStack cursorStack) {
        if (!isActive() || !session.awaiting || syncId != session.containerId) {
            return;
        }
        Session current = session;
        long now = System.nanoTime();
        current.fullUpdates++;
        if (current.firstFullUpdateAtNanos == 0L) {
            current.firstFullUpdateAtNanos = now;
        }
        recordPacketProgress(current, revision, true);
        ItemStack packetOutput = contents.isEmpty()
                ? ItemStack.EMPTY
                : contents.get(QuickCraftRecipeBookLayout.OUTPUT_SLOT);
        boolean packetRecipeMatches = recipeMatches(current, contents);
        boolean cursorEmpty = cursorStack.isEmpty();
        if (current.lastAuthoritativeMaterialRevision == Integer.MIN_VALUE
                || revision == current.lastAuthoritativeMaterialRevision
                || isRevisionAfter(
                revision, current.lastAuthoritativeMaterialRevision)) {
            current.lastAuthoritativeMaterialRevision = revision;
            current.lastAuthoritativeMaterialLedger =
                    describeMaterialLedgerContents(current, contents);
            current.lastAuthoritativeMaterialCounts =
                    materialCountsFromContents(current, contents);
        } else {
            LOGGER.debug("普通配方书ACK忽略旧材料全量包：界面={}，批次=#{}，revision={}，"
                            + "最新材料revision={}",
                    layout.name(), current.batchId, revision,
                    current.lastAuthoritativeMaterialRevision);
        }
        boolean packetExpectedOutput = isExpectedOutput(packetOutput, current.resultTemplate);
        boolean packetUnexpectedOutput = !packetOutput.isEmpty() && !packetExpectedOutput;
        if (packetUnexpectedOutput) {
            if (packetRecipeMatches) {
                current.invalidAuthoritativeOutput = true;
                current.authoritativeWrongOutputPackets++;
                current.authoritativeWrongOutputPacketsTotal++;
                LOGGER.warn("普通配方书ACK收到权威错误服务器产物：界面={}，批次=#{}，配方={}，"
                                + "期望={}，实际={}，revision={}，完整配方=true，光标={}，格子={}；"
                                + "隔离本批次，不再发送后续点击",
                        layout.name(), current.batchId, current.recipeId,
                        describeStack(current.resultTemplate), describeStack(packetOutput), revision,
                        describeStack(cursorStack), describeGridContents(contents));
            } else {
                current.intermediateOutputPackets++;
                current.intermediateOutputPacketsTotal++;
                LOGGER.info("普通配方书ACK保留中间服务器产物：界面={}，批次=#{}，配方={}，"
                                + "实际={}，revision={}，完整配方=false，光标={}，格子={}；"
                                + "只等待后续回包，不取不丢",
                        layout.name(), current.batchId, current.recipeId,
                        describeStack(packetOutput), revision, describeStack(cursorStack),
                        describeGridContents(contents));
            }
        }
        observeOutput(current, revision, packetOutput, true);
        current.outputAck.observeRecipeReadyFull(
                revision,
                packetRecipeMatches && cursorEmpty
                        && packetExpectedOutput
        );

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("手动补货ACK全量回包：界面={}，批次=#{}，revision={}->{}, 等待={} us，"
                            + "输出={}，光标={}，空边界={}，正确产物={}，配方匹配={}，"
                            + "配方终态全量={}@{}，失败包={}，输出分类={}，格子={}，材料全量={}",
                    layout.name(), current.batchId, current.batchStartRevision, revision,
                    (now - current.batchSentAtNanos) / 1_000L, describeStack(packetOutput),
                    describeStack(cursorStack), current.outputAck.sawAuthoritativeEmpty(),
                    current.outputAck.sawExpectedAfterEmpty(), packetRecipeMatches,
                    current.outputAck.sawRecipeReadyFull(),
                    current.outputAck.recipeReadyFullRevision(), current.craftFailed,
                    packetUnexpectedOutput
                            ? (packetRecipeMatches ? "权威错误" : "中间产物")
                            : (packetExpectedOutput ? "正确产物" : "空"),
                    describeGridContents(contents), current.lastAuthoritativeMaterialLedger);
        }
        if (LOGGER.isDebugEnabled()
                && (packetRecipeMatches || isExpectedOutput(packetOutput, current.resultTemplate))) {
            LOGGER.debug("手动补货ACK终态候选：界面={}，批次=#{}，revision={}，输出={}，"
                            + "空边界={}，正确产物={}，配方匹配={}，光标空={}，终态全量={}",
                    layout.name(), current.batchId, revision, describeStack(packetOutput),
                    current.outputAck.sawAuthoritativeEmpty(),
                    current.outputAck.sawExpectedAfterEmpty(), packetRecipeMatches,
                    cursorEmpty, current.outputAck.sawRecipeReadyFull());
        }

        if (!isContextValid(Minecraft.getInstance(), current)) {
            cancel("全量回包到达时界面失效");
            return;
        }
        if (current.cursorRecoveryActive) {
            handleCursorRecoveryFullUpdate(current, revision, now);
            return;
        }
        tryConfirmCurrentBatch(revision, now);
    }

    private void handleServerStatistics(ClientPacketListener source) {
        if (!isActive() || !session.awaiting || !session.statsProbePending
                || source != session.connection) {
            return;
        }
        Session current = session;
        int fullUpdatesAfterDispatch = current.fullUpdates - current.batchFullUpdatesStart;
        long now = System.nanoTime();
        current.statsProbePending = false;
        if (current.statsResponseAtNanos == 0L) {
            current.statsResponseAtNanos = now;
        }
        current.statsProbeResponses++;
        current.lastProgressAtNanos = now;
        if (fullUpdatesAfterDispatch <= 0) {
            // Statistics and Inventory packets are independent server responses.
            // Keep an early statistics ACK instead of waiting for a second one;
            // the later full packet will run the normal confirmation path.
            LOGGER.debug("手动补货ACK记录早到统计回包：界面={}，批次=#{}，尚未收到本批全量包；"
                            + "等待后续全量包确认",
                    layout.name(), current.batchId);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!isContextValid(client, current)) {
            cancel("统计屏障到达时界面失效");
            return;
        }

        ItemStack output = current.handler
                .getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT)
                .getItem();
        boolean cursorEmpty = current.handler.getCarried().isEmpty();
        boolean currentRecipeMatches = recipeMatches(current);
        boolean decreased = ingredientsDecreased(
                current.batchGridCounts, snapshotGridCounts(current.handler));
        boolean terminalGridCompatible = isPatternOrRemainderGridCompatible(
                current, current.handler);
        if (shouldInfoLogBatch(current.batchId) || current.batchPath == BatchPath.OUTPUT_DRAIN) {
            LOGGER.info("手动补货ACK统计屏障：界面={}，批次=#{}，等待={} us，全量包={}，"
                            + "revision={}->{}, 输出={}，空边界={}，正确产物={}，终态全量={}，"
                            + "配方匹配={}，返还物终态={}，光标空={}，失败包={}，原料减少={}，"
                            + "点击前格子={}，当前格子={}，批次开始正确产物={}，"
                            + "权威错误={}，中间错误包={}，权威材料={}，按住={}",
                    layout.name(), current.batchId,
                    (now - current.batchSentAtNanos) / 1_000L,
                    fullUpdatesAfterDispatch, current.batchStartRevision,
                    current.handler.getStateId(), describeStack(output),
                    current.outputAck.sawAuthoritativeEmpty(),
                    current.outputAck.sawExpectedAfterEmpty(),
                    current.outputAck.sawRecipeReadyFull(),
                    currentRecipeMatches, terminalGridCompatible,
                    cursorEmpty, current.craftFailed, decreased,
                    describeGridCounts(current.batchGridCounts), describeGrid(current.handler),
                    current.outputExpectedAtDispatch, current.invalidAuthoritativeOutput,
                    current.intermediateOutputPackets, current.lastAuthoritativeMaterialLedger,
                    current.inputHeld.getAsBoolean());
        }

        if (current.invalidAuthoritativeOutput) {
            LOGGER.warn("普通配方书ACK因权威错误产物停止：界面={}，批次=#{}，配方={}，"
                            + "错误回包数={}，当前输出={}，格子={}，原料减少={}；"
                            + "不再确认或发送下一批",
                    layout.name(), current.batchId, current.recipeId,
                    current.authoritativeWrongOutputPackets, describeStack(output),
                    describeGrid(current.handler), decreased);
            finish(Component.translatable("quickcraft.message.crafting.stopped"),
                    "权威错误产物");
            return;
        }

        String materialViolation = findMaterialLedgerViolation(current);
        if (materialViolation != null) {
            LOGGER.warn("普通配方书ACK材料守恒异常，停止本批次：界面={}，批次=#{}，配方={}，"
                            + "输出搬运={}，输出槽点击={}，前后权威材料={} -> {}，详情={}，格子={}；"
                            + "不会继续发送下一批",
                    layout.name(), current.batchId, current.recipeId,
                    current.batchOutputOperations,
                    current.batchOutputSlotClicks,
                    describeMaterialCounts(current.batchAuthoritativeMaterialCountsBefore),
                    describeMaterialCounts(current.lastAuthoritativeMaterialCounts),
                    materialViolation, describeGrid(current.handler));
            finish(Component.translatable("quickcraft.message.crafting.stopped"),
                    "材料守恒异常");
            return;
        }

        if (tryConfirmCurrentBatch(current.handler.getStateId(), now)) {
            return;
        }

        if (!cursorEmpty) {
            LOGGER.warn("手动补货ACK统计屏障时光标非空：界面={}，批次=#{}，光标={}",
                    layout.name(), current.batchId, describeStack(current.handler.getCarried()));
            beginCursorRecovery(client, current, now);
            return;
        }

        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finish(current.stopMessage != null
                            ? current.stopMessage
                            : Component.translatable("quickcraft.message.crafting.stopped"),
                    "统计屏障后输入释放");
            return;
        }

        boolean intermediateOutput = !output.isEmpty()
                && !isExpectedOutput(output, current.resultTemplate)
                && isIntermediateOutput(current, current.handler);
        if (current.batchPath == BatchPath.OUTPUT_DRAIN
                && canContinueOutputDrainWithIntermediate(
                cursorEmpty, current.outputExpectedAtDispatch, decreased, intermediateOutput)) {
            current.drainRetries = 0;
            acknowledgeBatch(current, current.handler.getStateId(), now,
                    "批量取产物后出现兼容中间产物，继续补货");
            pump(client);
            return;
        }

        if (current.batchPath == BatchPath.MANUAL_COMBINED
                && canConfirmCombinedBatch(
                fullUpdatesAfterDispatch > 0,
                cursorEmpty,
                output.isEmpty()
                        || isExpectedOutput(output, current.resultTemplate)
                        || intermediateOutput,
                (current.batchSawExpectedOutput || current.outputExpectedAtDispatch)
                        && !current.invalidAuthoritativeOutput,
                terminalGridCompatible)) {
            current.refillRetries = 0;
            current.drainRetries = 0;
            acknowledgeBatch(current, current.handler.getStateId(), now,
                    "同批补料并整批取产物已确认");
            pump(client);
            return;
        }

        if (current.batchPath == BatchPath.OUTPUT_DRAIN
                && shouldContinueOutputDrain(
                cursorEmpty,
                output.isEmpty(),
                isExpectedOutput(output, current.resultTemplate),
                hasAuthoritativeExpectedOutput(current) || current.outputExpectedAtDispatch,
                decreased,
                current.drainRetries,
                MAX_DRAIN_RETRIES)) {
            String drainOutcome;
            if (output.isEmpty()) {
                current.drainRetries = 0;
                drainOutcome = "分段输出已排空，继续手动补货";
            } else if (decreased) {
                current.drainRetries = 0;
                drainOutcome = "统计屏障后产物仍在，继续取产物";
            } else {
                current.drainRetries++;
                current.drainRetryBatches++;
                drainOutcome = "统计屏障后产物仍在但原料未减少，重试取产物";
                LOGGER.info("手动补货ACK重试取产物：界面={}，批次=#{}，重试={}/{}，"
                                + "点击前格子={}，当前格子={}，按住={}",
                        layout.name(), current.batchId, current.drainRetries, MAX_DRAIN_RETRIES,
                        describeGridCounts(current.batchGridCounts), describeGrid(current.handler),
                        current.inputHeld.getAsBoolean());
            }
            acknowledgeBatch(current, current.handler.getStateId(), now, drainOutcome);
            pump(client);
            return;
        }

        if (current.batchPath == BatchPath.OUTPUT_DRAIN
                && output.isEmpty()
                && !hasAuthoritativeExpectedOutput(current)
                && !current.outputExpectedAtDispatch) {
            LOGGER.warn("普通配方书ACK拒绝空输出确认：界面={}，批次=#{}，配方={}，"
                            + "原料减少={}，全量包={}，批次开始正确产物=false，正确产物回包=false，格子={}；"
                            + "这类状态只能停止，不能继续补货或发下一批",
                    layout.name(), current.batchId, current.recipeId, decreased,
                    fullUpdatesAfterDispatch, describeGrid(current.handler));
        }

        if (includesManualRefill(current.batchPath) && cursorEmpty) {
            boolean authoritativeExpectedOutput = hasAuthoritativeExpectedOutput(current);
            if (currentRecipeMatches && isExpectedOutput(output, current.resultTemplate)
                    && authoritativeExpectedOutput) {
                current.refillRetries = 0;
                acknowledgeBatch(current, current.handler.getStateId(), now,
                        "手动补货后配方仍可继续");
                pump(client);
                return;
            }
            if (!isPatternGridCompatible(current.handler, current.pattern)) {
                LOGGER.warn("手动补货ACK权威配方错位：界面={}，批次=#{}，格子={}；不是缺料，不进入拾取等待",
                        layout.name(), current.batchId, describeGrid(current.handler));
                finish(Component.translatable("quickcraft.message.crafting.stopped"), "权威配方错位");
                return;
            }
            if (output.isEmpty() && tryRetryManualRefill(current, now, "统计屏障后补货未齐")) {
                return;
            }
            if (output.isEmpty()) {
                boolean patternComplete = QuickCraftRecipeBookInventory.isPatternComplete(
                        current.handler, layout, current.pattern);
                boolean hasIngredients = ingredientAvailabilityHandler != null
                        && ingredientAvailabilityHandler.hasIngredients(current.handler);
                if (!patternComplete && !hasIngredients
                        && current.pickupWaitBatches < MAX_PICKUP_WAIT_BATCHES) {
                    current.pickupWaitBatches++;
                    current.pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
                    LOGGER.info("手动补货ACK等待脚下原料拾取：界面={}，批次=#{}，等待={} Tick，"
                                    + "批次={}/{}, 空格={}，按住={}",
                            layout.name(), current.batchId, MAX_PICKUP_WAIT_TICKS,
                            current.pickupWaitBatches, MAX_PICKUP_WAIT_BATCHES,
                            QuickCraftRecipeBookInventory.unlockedEmptySlots(
                                    current.handler, layout),
                            current.inputHeld.getAsBoolean());
                    acknowledgeBatch(current, current.handler.getStateId(), now,
                            "补货无原料，等待脚下拾取");
                    pump(client);
                    return;
                }
                if (!patternComplete && !hasIngredients) {
                    finish(Component.translatable("quickcraft.message.crafting.no_ingredients"),
                            "等待拾取后仍无可补原料");
                    return;
                }
                acknowledgeBatch(current, current.handler.getStateId(), now,
                        "补货后仍无产物，检查脚下拾取");
                pump(client);
                return;
            }
        }

        LOGGER.warn("手动补货ACK统计屏障终态不可续用：界面={}，批次=#{}，路径={}，输出={}，"
                        + "权威正确产物={}，原料减少={}，点击前格子={}，当前格子={}，按住={}",
                layout.name(), current.batchId, current.batchPath.description, describeStack(output),
                hasAuthoritativeExpectedOutput(current), decreased,
                describeGridCounts(current.batchGridCounts), describeGrid(current.handler),
                current.inputHeld.getAsBoolean());
        finish(output.isEmpty()
                        ? Component.translatable("quickcraft.message.crafting.no_ingredients")
                        : Component.translatable("quickcraft.message.crafting.stopped"),
                "统计屏障终态不可续用：" + current.batchPath.description);
    }

    private boolean tryConfirmCurrentBatch(int acknowledgementRevision, long now) {
        if (!isActive() || !session.awaiting) {
            return false;
        }
        Session current = session;
        if (!shouldCheckBatchTerminal(current.statsProbePending)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (!isContextValid(client, current)) {
            cancel("ACK终态到达时界面失效");
            return false;
        }
        ItemStack currentOutput = current.handler
                .getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT)
                .getItem();
        boolean patternComplete = QuickCraftRecipeBookInventory.isPatternComplete(
                current.handler, layout, current.pattern);
        boolean fullReceived = current.fullUpdates > current.batchFullUpdatesStart;
        boolean cursorEmpty = current.handler.getCarried().isEmpty();
        boolean expectedOutput = isExpectedOutput(currentOutput, current.resultTemplate);
        boolean authoritativeExpectedOutput = hasAuthoritativeExpectedOutput(current);
        boolean decreased = ingredientsDecreased(
                current.batchGridCounts, snapshotGridCounts(current.handler));
        boolean terminalGridCompatible = isPatternOrRemainderGridCompatible(
                current, current.handler);
        boolean statsReceived = !current.statsProbePending;
        boolean pathReady;
        if (current.batchPath == BatchPath.OUTPUT_DRAIN) {
            pathReady = canConfirmOutputDrain(
                    fullReceived, cursorEmpty, currentOutput.isEmpty(), decreased, expectedOutput,
                    authoritativeExpectedOutput || current.outputExpectedAtDispatch);
        } else if (current.batchPath == BatchPath.MANUAL_COMBINED) {
            boolean compatibleTerminalOutput = currentOutput.isEmpty()
                    || expectedOutput
                    || isIntermediateOutput(current, current.handler);
            pathReady = canConfirmCombinedBatch(
                    fullReceived, cursorEmpty, compatibleTerminalOutput,
                    (current.batchSawExpectedOutput || current.outputExpectedAtDispatch)
                            && !current.invalidAuthoritativeOutput,
                    terminalGridCompatible);
        } else {
            pathReady = canConfirmManualBatch(
                    fullReceived, cursorEmpty, recipeMatches(current) || patternComplete,
                    expectedOutput && authoritativeExpectedOutput);
        }
        boolean terminalPrefix = canFinishBatch(statsReceived, pathReady);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("手动补货ACK确认检查：界面={}，批次=#{}，路径={}，全量包={}，光标空={}，"
                            + "配方匹配={}，格子齐={}，原料减少={}，产物={}，结果={}，"
                            + "权威正确产物={}，批次开始正确产物={}，统计已到={}，权威错误={}，"
                            + "点击前格子={}，当前格子={}，按住={}",
                    layout.name(), current.batchId, current.batchPath.description,
                    current.fullUpdates - current.batchFullUpdatesStart,
                    cursorEmpty, recipeMatches(current), patternComplete, decreased,
                    describeStack(currentOutput), terminalPrefix, authoritativeExpectedOutput,
                    current.outputExpectedAtDispatch, statsReceived, current.invalidAuthoritativeOutput,
                    describeGridCounts(current.batchGridCounts), describeGrid(current.handler),
                    current.inputHeld.getAsBoolean());
        }
        if (!terminalPrefix) {
            return false;
        }

        if (expectedOutput) {
            current.refillRetries = 0;
        }
        if (current.batchPath == BatchPath.OUTPUT_DRAIN) {
            current.drainRetries = 0;
        }
        acknowledgeBatch(current, acknowledgementRevision, now,
                current.batchPath == BatchPath.OUTPUT_DRAIN && currentOutput.isEmpty()
                        ? "全量包确认输出已排空，继续手动补货"
                        : "终态确认");

        if (!isContextValid(client, current)) {
            cancel("ACK后界面失效");
        } else if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finish(current.stopMessage != null
                    ? current.stopMessage
                    : Component.translatable("quickcraft.message.crafting.stopped"), "ACK后停止");
        } else {
            pump(client);
        }
        return true;
    }

    private void acknowledgeBatch(Session current,
                                  int acknowledgementRevision,
                                  long now,
                                  String outcome) {
        long latencyNanos = Math.max(0L, now - current.batchSentAtNanos);
        current.awaiting = false;
        current.confirmedBatches++;
        current.ackLatencyNanos += latencyNanos;
        current.minAckLatencyNanos = minNonZero(current.minAckLatencyNanos, latencyNanos);
        current.maxAckLatencyNanos = Math.max(current.maxAckLatencyNanos, latencyNanos);
        long fullUpdateMicros = current.firstFullUpdateAtNanos == 0L
                ? -1L
                : Math.max(0L, current.firstFullUpdateAtNanos - current.batchSentAtNanos) / 1_000L;
        long statsResponseMicros = current.statsResponseAtNanos == 0L
                ? -1L
                : Math.max(0L, current.statsResponseAtNanos - current.batchSentAtNanos) / 1_000L;
        long validationMicros = Math.max(0L, latencyNanos / 1_000L
                - Math.max(fullUpdateMicros, statsResponseMicros));
        if (fullUpdateMicros >= 0L) {
            current.fullUpdateLatencyNanos += fullUpdateMicros * 1_000L;
            current.fullUpdateSamples++;
        }
        if (statsResponseMicros >= 0L) {
            current.statsResponseLatencyNanos += statsResponseMicros * 1_000L;
            current.statsResponseSamples++;
        }
        current.ackPhaseSamples++;
        if (shouldInfoLogBatch(current.batchId)) {
            LOGGER.info("手动补货ACK批次确认：界面={}，批次=#{}，路径={}，配方={}，"
                            + "耗时={} us，客户端收到全量={} us，客户端收到统计={} us，"
                            + "终态校验={} us，revision={}->{}, Tick预算={}/{}, 结果={}",
                    layout.name(), current.batchId, current.batchPath.description,
                    current.recipeId, latencyNanos / 1_000L, fullUpdateMicros,
                    statsResponseMicros, validationMicros, current.batchStartRevision,
                    acknowledgementRevision, dispatchedThisTick,
                    QuickCraftConfigs.getCraftLoopsPerTick(), outcome);
        }
    }

    private void observeOutput(Session current,
                               int revision,
                               ItemStack output,
                               boolean allowExpectedOutput) {
        boolean unexpectedOutput = !output.isEmpty()
                && !isExpectedOutput(output, current.resultTemplate);
        if (unexpectedOutput && !current.unexpectedOutputLogged) {
            current.unexpectedOutputLogged = true;
            LOGGER.info("普通配方书ACK收到非目标输出回包，等待全量判定：界面={}，批次=#{}，配方={}，"
                            + "期望={}，实际={}，revision={}，当前客户端格子={}",
                    layout.name(), current.batchId, current.recipeId,
                    describeStack(current.resultTemplate), describeStack(output), revision,
                    describeGrid(current.handler));
        }
        boolean expectedOutput = isExpectedOutput(output, current.resultTemplate);
        current.batchSawExpectedOutput |= allowExpectedOutput && expectedOutput;
        current.outputAck.observe(
                revision,
                output.isEmpty(),
                expectedOutput,
                allowExpectedOutput
        );
    }

    private void recordPacketProgress(Session current, int revision, boolean relevantPacket) {
        if (isRevisionAfter(
                revision, current.lastProgressRevision)) {
            current.lastProgressRevision = revision;
            current.lastProgressAtNanos = System.nanoTime();
        } else if (relevantPacket && isRevisionAfter(
                revision, current.batchStartRevision)) {
            current.lastProgressAtNanos = System.nanoTime();
        }
    }

    private void recordOutputActions(Session current, int outputMoveOperations) {
        current.quickMoves += outputMoveOperations;
    }

    void recordRemainderThrow() {
        if (isActive() && dispatchingBatch) {
            session.remainderThrows++;
        }
    }

    private boolean recipeMatches(Session current) {
        return QuickCraftRecipeBookInventory.isPatternComplete(
                current.handler, layout, current.pattern);
    }

    private boolean isIntermediateOutput(Session current, AbstractContainerMenu handler) {
        if (current == null || handler == null) {
            return false;
        }
        return !QuickCraftRecipeBookInventory.isPatternComplete(handler, layout, current.pattern)
                && isPatternGridCompatible(handler, current.pattern);
    }

    private boolean isPatternGridCompatible(AbstractContainerMenu handler, List<ItemStack> pattern) {
        if (handler == null || pattern == null || pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack expected = pattern.get(i);
            ItemStack actual = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (actual.isEmpty()) {
                continue;
            }
            if (expected.isEmpty() || !ItemStack.isSameItemSameComponents(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPatternOrRemainderGridCompatible(Session current, AbstractContainerMenu handler) {
        if (current == null || handler == null || current.pattern == null
                || current.pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack expected = current.pattern.get(i);
            ItemStack actual = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (actual.isEmpty()
                    || (!expected.isEmpty()
                    && ItemStack.isSameItemSameComponents(expected, actual))) {
                continue;
            }
            if (!isAllowedCraftRemainder(
                    current.batchOutputOperations > 0,
                    !expected.isEmpty(),
                    isPatternIngredient(current, actual))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAllowedCraftRemainder(boolean outputActionSent,
                                           boolean occupiedPatternSlot,
                                           boolean matchesPatternIngredient) {
        return outputActionSent && occupiedPatternSlot && !matchesPatternIngredient;
    }

    private boolean hasAuthoritativeExpectedOutput(Session current) {
        return current != null
                && (current.outputAck.sawExpectedAfterEmpty()
                || current.outputAck.sawRecipeReadyFull());
    }

    private boolean recipeMatches(Session current, List<ItemStack> contents) {
        return isPatternComplete(contents, layout, current.pattern);
    }

    static boolean isPatternComplete(List<ItemStack> contents,
                                     QuickCraftRecipeBookLayout.Layout layout,
                                     List<ItemStack> pattern) {
        if (contents == null || layout == null || pattern == null
                || pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int logicalIndex = 0; logicalIndex < layout.gridSize(); logicalIndex++) {
            int slotId = layout.gridSlotId(logicalIndex);
            if (slotId < 0 || slotId >= contents.size()) {
                return false;
            }
            ItemStack expected = pattern.get(logicalIndex);
            ItemStack actual = contents.get(slotId);
            if (expected == null || expected.isEmpty()) {
                if (actual != null && !actual.isEmpty()) {
                    return false;
                }
            } else if (actual == null || actual.isEmpty()
                    || !ItemStack.isSameItemSameComponents(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private void finish(Component message, String reason) {
        if (!isActive()) {
            return;
        }
        Session completed = session;
        boolean held = completed.inputHeld.getAsBoolean();
        Minecraft client = Minecraft.getInstance();
        if (isContextValid(client, completed)) {
            parkCursor(client, completed.handler, "结束");
        }
        boolean cursorEmpty = completed.handler.getCarried().isEmpty();
        boolean needsDrain = needsCanceledBatchDrain(
                completed.awaiting, cursorEmpty, completed.statsProbePending);
        boolean allowTailDrop = canDropTail(completed.awaiting, cursorEmpty);
        if (needsDrain) {
            beginCanceledBatchDrain(completed, layout);
        }
        clearSession();
        int stopTailSlots = completed.finishHandler.finish(message, allowTailDrop);
        logSummary(completed, reason, stopTailSlots, held);
    }

    private void handoffToLegacy(Minecraft client, String reason) {
        if (!isActive()) {
            return;
        }
        Session handedOff = session;
        handedOff.legacyHandoffs++;
        clearSession();
        logSummary(handedOff, "回退旧Tick流程：" + reason, 0, handedOff.inputHeld.getAsBoolean());
        if (fallbackHandler != null && client != null && client.player != null) {
            fallbackHandler.fallback(client, handedOff.handler, handedOff.recipeId);
        }
    }

    private void logBatchDispatch(Session current,
                                  int outputMoveOperations,
                                  long dispatchMicros,
                                  String action) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("手动补货ACK批次发送：界面={}，批次=#{}，配方={}，动作={}，输出搬运={}，"
                            + "输出槽点击={}，revision={}，发送耗时={} us",
                    layout.name(), current.batchId, current.recipeId, action, outputMoveOperations,
                    current.batchOutputSlotClicks, current.batchStartRevision, dispatchMicros);
        }
        if (shouldInfoLogBatch(current.batchId)) {
            LOGGER.info("手动补货ACK批次发送：界面={}，批次=#{}，动作={}，输出搬运={}，"
                            + "revision={}，耗时={} us",
                    layout.name(), current.batchId, action, outputMoveOperations,
                    current.batchStartRevision, dispatchMicros);
        }
    }

    private void logSummary(Session completed, String reason, int stopTailSlots, boolean held) {
        long elapsedMillis = nanosToMillis(System.nanoTime() - completed.startedAtNanos);
        long averageAckMicros = completed.confirmedBatches == 0
                ? 0L : completed.ackLatencyNanos / completed.confirmedBatches / 1_000L;
        long ackWaitMillis = nanosToMillis(completed.ackLatencyNanos);
        long ackWaitPercent = elapsedMillis == 0L
                ? 0L : Math.min(100L, ackWaitMillis * 100L / elapsedMillis);
        long averageFullUpdateMicros = completed.fullUpdateSamples == 0
                ? -1L : completed.fullUpdateLatencyNanos / completed.fullUpdateSamples / 1_000L;
        long averageStatsResponseMicros = completed.statsResponseSamples == 0
                ? -1L : completed.statsResponseLatencyNanos / completed.statsResponseSamples / 1_000L;
        long confirmedPerSecond = elapsedMillis <= 0L
                ? 0L : completed.confirmedBatches * 1_000L / elapsedMillis;
        LOGGER.info("手动补货ACK会话汇总：界面={}，配方={}，耗时={} ms，批次={}/{}确认，"
                        + "请求={}，全量屏障={}，统计确认={}/{}，输出搬运={}，输出槽点击={}，"
                        + "输出槽丢出={}，返还物丢出={}，丢原料={}，快照补料={}/{}，"
                        + "回退旧流程={}，槽位/全量/失败包={}/{}/{}，超时={}，"
                        + "ACK平均/最小/最大={}/{}/{} us，ACK等待={} ms({}%)，本地发送={} us，"
                        + "确认批次/秒={}，首个全量平均={} us，统计平均={} us，"
                        + "补货重试={}，取产物重试={}，等待拾取={}，停止尾货槽={}，按住={}，结束原因={}",
                layout.name(), completed.recipeId, elapsedMillis, completed.confirmedBatches,
                completed.batches, completed.recipeRequests, completed.barriersSent,
                completed.statsProbeResponses, completed.statsProbesSent, completed.quickMoves,
                completed.outputSlotClicks,
                completed.outputThrows, completed.remainderThrows, completed.ingredientDrops,
                completed.snapshotRefillSuccesses, completed.snapshotRefillBatches,
                completed.legacyHandoffs, completed.slotUpdates, completed.fullUpdates,
                completed.failurePackets, completed.timeouts, averageAckMicros,
                completed.minAckLatencyNanos / 1_000L, completed.maxAckLatencyNanos / 1_000L,
                ackWaitMillis, ackWaitPercent, completed.localDispatchNanos / 1_000L,
                confirmedPerSecond, averageFullUpdateMicros, averageStatsResponseMicros,
                 completed.refillRetryBatches, completed.drainRetryBatches,
                 completed.pickupWaitBatches, stopTailSlots, held, reason);
        LOGGER.info("手动补货ACK产物异常统计：界面={}，配方={}，中间错误产物包={}，"
                        + "权威错误产物包={}，客户端原料THROW={}，返还物THROW={}，输出槽THROW={}",
                layout.name(), completed.recipeId, completed.intermediateOutputPacketsTotal,
                completed.authoritativeWrongOutputPacketsTotal, completed.ingredientDrops,
                completed.remainderThrows,
                completed.outputThrows);
    }

    private void clearSession() {
        if (activeExecutor == this) {
            activeExecutor = null;
        }
        session = null;
        dispatchedThisTick = 0;
    }

    private boolean isContextValid(Minecraft client, Session current) {
        return client != null && client.player != null && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.player.containerMenu == current.handler
                && current.handler.containerId == current.containerId
                && QuickCraftRecipeBookLayout.fromScreen(client.gui.screen()) == layout;
    }

    private boolean parkCursor(Minecraft client, AbstractContainerMenu handler, String phase) {
        if (handler == null || handler.getCarried().isEmpty()) {
            return true;
        }
        ItemStack before = handler.getCarried().copy();
        boolean parked = QuickCraftRecipeBookInventory.returnCursorToUnlockedInventory(
                client, handler, layout);
        if (!parked && session != null) {
            int patternSlot = QuickCraftRecipeBookInventory.firstPatternSlotWithRoom(
                    handler, layout, session.pattern, handler.getCarried());
            if (patternSlot >= 0) {
                client.gameMode.handleContainerInput(
                        handler.containerId, patternSlot, 0, ContainerInput.PICKUP, client.player);
            }
            parked = handler.getCarried().isEmpty();
        }
        if (!parked && session != null
                && isSameItem(handler.getCarried(), session.resultTemplate)) {
            client.gameMode.handleContainerInput(
                    handler.containerId, -999, 0, ContainerInput.PICKUP, client.player);
            parked = handler.getCarried().isEmpty();
        }
        if (!parked && session != null && !session.resultTemplate.isEmpty()) {
            int productSlot = QuickCraftRecipeBookInventory.findMatchingUnlockedSlot(
                    handler, layout, session.resultTemplate);
            if (productSlot >= 0) {
                client.gameMode.handleContainerInput(
                        handler.containerId, productSlot, 0, ContainerInput.PICKUP, client.player);
                if (isSameItem(handler.getCarried(), session.resultTemplate)) {
                    client.gameMode.handleContainerInput(
                            handler.containerId, -999, 0, ContainerInput.PICKUP, client.player);
                }
            }
            parked = handler.getCarried().isEmpty();
        }
        LOGGER.info("手动补货ACK放回光标：界面={}，阶段={}，原光标={}，结果={}，剩余={}",
                layout.name(), phase, describeStack(before), parked,
                describeStack(handler.getCarried()));
        return parked;
    }

    private void beginCursorRecovery(Minecraft client, Session current, long now) {
        if (client == null || client.player == null || client.gameMode == null) {
            cancel("开始光标恢复时客户端上下文失效");
            return;
        }
        current.cursorRecoveryActive = true;
        current.cursorRecoveryAttempts = 0;
        if (isSameItem(current.handler.getCarried(), current.resultTemplate)) {
            ItemStack product = current.handler.getCarried().copy();
            client.gameMode.handleContainerInput(
                    current.handler.containerId, -999, 0, ContainerInput.PICKUP, client.player);
            current.cursorRecoveryAttempts++;
            LOGGER.info("手动补货ACK直接丢出光标产物：界面={}，批次=#{}，产物={}；等待全量确认",
                    layout.name(), current.batchId, describeStack(product));
        }
        sendCursorRecoveryFullSync(current, now, "发现权威光标非空");
    }

    private void handleCursorRecoveryFullUpdate(Session current,
                                                int revision,
                                                long now) {
        Minecraft client = Minecraft.getInstance();
        if (!isContextValid(client, current)) {
            cancel("光标恢复全量回包到达时界面失效");
            return;
        }
        if (current.handler.getCarried().isEmpty()) {
            current.cursorRecoveryActive = false;
            current.cursorRecoveryAttempts = 0;
            current.statsProbePending = true;
            current.lastProgressAtNanos = now;
            current.connection.send(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.REQUEST_STATS));
            current.statsProbesSent++;
            LOGGER.info("手动补货ACK光标恢复已获权威确认：界面={}，批次=#{}，revision={}；"
                            + "重新发送统计屏障后继续",
                    layout.name(), current.batchId, revision);
            return;
        }
        if (!canRetryCursorRecovery(
                current.cursorRecoveryAttempts, MAX_CURSOR_RECOVERY_ATTEMPTS)) {
            LOGGER.warn("手动补货ACK光标恢复超过上限：界面={}，批次=#{}，尝试={}/{}，光标={}；"
                            + "保留物品并停止，避免继续点击错位槽",
                    layout.name(), current.batchId, current.cursorRecoveryAttempts,
                    MAX_CURSOR_RECOVERY_ATTEMPTS,
                    describeStack(current.handler.getCarried()));
            cancel("权威光标恢复超过上限");
            return;
        }

        ItemStack before = current.handler.getCarried().copy();
        current.cursorRecoveryAttempts++;
        boolean parked = parkCursor(client, current.handler, "权威光标恢复");
        LOGGER.info("手动补货ACK按权威快照恢复光标：界面={}，批次=#{}，尝试={}/{}, "
                        + "原光标={}，本地结果={}，当前光标={}；等待全量确认",
                layout.name(), current.batchId, current.cursorRecoveryAttempts,
                MAX_CURSOR_RECOVERY_ATTEMPTS, describeStack(before), parked,
                describeStack(current.handler.getCarried()));
        sendCursorRecoveryFullSync(current, now, "确认光标放回结果");
    }

    private void sendCursorRecoveryFullSync(Session current, long now, String reason) {
        try {
            sendFullSyncBarrier(current.connection, current.handler);
            current.barriersSent++;
            current.lastProgressAtNanos = now;
            LOGGER.info("手动补货ACK请求光标权威同步：界面={}，批次=#{}，原因={}，尝试={}/{}, 光标={}",
                    layout.name(), current.batchId, reason, current.cursorRecoveryAttempts,
                    MAX_CURSOR_RECOVERY_ATTEMPTS,
                    describeStack(current.handler.getCarried()));
        } catch (Throwable throwable) {
            LOGGER.warn("手动补货ACK光标同步请求失败：界面={}，批次=#{}，原因={}",
                    layout.name(), current.batchId, reason, throwable);
            cancel("光标同步请求异常");
        }
    }

    private boolean tryRetryManualRefill(Session current, long now, String reason) {
        if (current == null || current.handler == null) {
            return false;
        }
        if (current.refillRetries >= MAX_REFILL_RETRIES) {
            LOGGER.info("手动补货ACK放弃重试：界面={}，批次=#{}，已重试={}/{}",
                    layout.name(), current.batchId, current.refillRetries, MAX_REFILL_RETRIES);
            return false;
        }
        boolean hasIngredients = ingredientAvailabilityHandler != null
                && ingredientAvailabilityHandler.hasIngredients(current.handler);
        if (!hasIngredients) {
            LOGGER.info("手动补货ACK无原料可重试：界面={}，批次=#{}，格子={}",
                    layout.name(), current.batchId, describeGrid(current.handler));
            return false;
        }
        current.refillRetries++;
        current.refillRetryBatches++;
        LOGGER.info("手动补货ACK重试补货：界面={}，批次=#{}，重试={}/{}，格子={}",
                layout.name(), current.batchId, current.refillRetries, MAX_REFILL_RETRIES,
                describeGrid(current.handler));
        acknowledgeBatch(current, current.handler.getStateId(), now, reason);
        pump(Minecraft.getInstance());
        return true;
    }

    private int[] snapshotGridCounts(AbstractContainerMenu handler) {
        int[] counts = new int[layout.gridSize()];
        if (handler == null) {
            return counts;
        }
        for (int i = 0; i < counts.length; i++) {
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getItem();
            counts[i] = stack == null || stack.isEmpty() ? 0 : stack.getCount();
        }
        return counts;
    }

    private static String describeGridCounts(int[] counts) {
        if (counts == null || counts.length == 0) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(counts[i]);
        }
        return builder.toString();
    }

    private String describeGrid(AbstractContainerMenu handler) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < layout.gridSize(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            if (handler == null) {
                builder.append('-');
                continue;
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

    private String describeGridContents(List<ItemStack> contents) {
        if (contents == null || contents.size() < QuickCraftRecipeBookLayout.OUTPUT_SLOT
                + 1 + layout.gridSize()) {
            return "不可用";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < layout.gridSize(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            ItemStack stack = contents.get(layout.gridSlotId(i));
            builder.append(describeStack(stack));
        }
        return builder.toString();
    }

    private String describeMaterialLedgerContents(Session current,
                                                  List<ItemStack> contents) {
        if (current == null || contents == null) {
            return "不可用";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < current.pattern.size(); i++) {
            ItemStack template = current.pattern.get(i);
            if (template.isEmpty() || hasEarlierPattern(current.pattern, i, template)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            int gridCount = 0;
            for (int gridIndex = 0; gridIndex < layout.gridSize(); gridIndex++) {
                int slotId = layout.gridSlotId(gridIndex);
                if (slotId >= contents.size()) {
                    return "不可用";
                }
                ItemStack stack = contents.get(slotId);
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    gridCount += stack.getCount();
                }
            }
            int inventoryCount = 0;
            for (int inventoryIndex = 0;
                 inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
                 inventoryIndex++) {
                int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
                if (slotId < 0 || slotId >= contents.size()) {
                    return "不可用";
                }
                ItemStack stack = contents.get(slotId);
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    inventoryCount += stack.getCount();
                }
            }
            builder.append(template.getHoverName().getString())
                    .append(" grid=").append(gridCount)
                    .append(" inventory=").append(inventoryCount);
        }
        return builder.length() == 0 ? "无配方原料" : builder.toString();
    }

    private int[] materialCountsFromContents(Session current,
                                             List<ItemStack> contents) {
        if (current == null || contents == null) {
            return null;
        }
        List<ItemStack> templates = uniquePatternIngredients(current);
        int[] counts = new int[templates.size()];
        for (int gridIndex = 0; gridIndex < layout.gridSize(); gridIndex++) {
            int slotId = layout.gridSlotId(gridIndex);
            if (slotId < 0 || slotId >= contents.size()) {
                return null;
            }
            addMaterialCount(counts, templates, contents.get(slotId));
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (slotId < 0 || slotId >= contents.size()) {
                return null;
            }
            addMaterialCount(counts, templates, contents.get(slotId));
        }
        return counts;
    }

    private void addMaterialCount(int[] counts,
                                  List<ItemStack> templates,
                                  ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < templates.size(); i++) {
            if (ItemStack.isSameItemSameComponents(stack, templates.get(i))) {
                counts[i] += stack.getCount();
                return;
            }
        }
    }

    private List<ItemStack> uniquePatternIngredients(Session current) {
        List<ItemStack> templates = new ArrayList<>();
        if (current == null) {
            return templates;
        }
        for (ItemStack patternStack : current.pattern) {
            if (patternStack.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (ItemStack template : templates) {
                if (ItemStack.isSameItemSameComponents(template, patternStack)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                templates.add(patternStack);
            }
        }
        return templates;
    }

    private String findMaterialLedgerViolation(Session current) {
        if (current == null
                || current.batchAuthoritativeMaterialCountsBefore == null
                || current.lastAuthoritativeMaterialCounts == null
                || current.batchAuthoritativeMaterialCountsBefore.length
                != current.lastAuthoritativeMaterialCounts.length) {
            return null;
        }
        List<ItemStack> templates = uniquePatternIngredients(current);
        int maximumCrafts = maximumCraftsFromBatchGrid(current);
        if (maximumCrafts <= 0) {
            // No complete input was present before this batch (typically a
            // refill-only batch), so there is no craft consumption to check.
            return null;
        }
        for (int i = 0; i < templates.size(); i++) {
            int before = current.batchAuthoritativeMaterialCountsBefore[i];
            int after = current.lastAuthoritativeMaterialCounts[i];
            int consumed = before - after;
            if (consumed <= 0) {
                continue;
            }
            int occurrences = 0;
            for (ItemStack patternStack : current.pattern) {
                if (!patternStack.isEmpty()
                        && ItemStack.isSameItemSameComponents(patternStack, templates.get(i))) {
                    occurrences++;
                }
            }
            int safeCrafts = maximumCrafts;
            long expectedMaximum = (long) safeCrafts * occurrences;
            if (consumed > expectedMaximum) {
                return templates.get(i).getHoverName().getString()
                        + " 消耗=" + consumed
                        + "，本批最多应消耗=" + expectedMaximum
                        + "（合成格最多轮数=" + safeCrafts
                        + "，输出搬运=" + current.batchOutputOperations
                        + "，输出槽点击=" + current.batchOutputSlotClicks + "）";
            }
        }
        return null;
    }

    /**
     * 1.21.3 的结果槽 Ctrl+丢弃会持续合成到格子耗尽。合并批次可能先清理返还物并补料，
     * 所以守恒上限必须取补料完成、结果槽 THROW 之前的格子，不能取批次开始时的旧格子。
     */
    private int maximumCraftsFromBatchGrid(Session current) {
        if (current == null || current.batchCraftGridCounts == null
                || current.batchCraftGridCounts.length < layout.gridSize()
                || current.pattern == null || current.pattern.size() != layout.gridSize()) {
            return 0;
        }
        boolean[] requiredSlots = new boolean[layout.gridSize()];
        for (int i = 0; i < layout.gridSize(); i++) {
            requiredSlots[i] = !current.pattern.get(i).isEmpty();
        }
        return maximumCraftsFromGridCounts(current.batchCraftGridCounts, requiredSlots);
    }

    static int maximumCraftsFromGridCounts(int[] gridCounts, boolean[] requiredSlots) {
        if (gridCounts == null || requiredSlots == null
                || gridCounts.length < requiredSlots.length) {
            return 0;
        }
        int maximum = Integer.MAX_VALUE;
        boolean hasIngredient = false;
        for (int i = 0; i < requiredSlots.length; i++) {
            if (!requiredSlots[i]) {
                continue;
            }
            hasIngredient = true;
            maximum = Math.min(maximum, Math.max(0, gridCounts[i]));
        }
        return hasIngredient && maximum != Integer.MAX_VALUE ? maximum : 0;
    }

    private String describeMaterialCounts(int[] counts) {
        if (counts == null) {
            return "不可用";
        }
        List<ItemStack> templates = uniquePatternIngredients(session);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < counts.length && i < templates.size(); i++) {
            if (i > 0) {
                builder.append(';');
            }
            builder.append(templates.get(i).getHoverName().getString())
                    .append('=').append(counts[i]);
        }
        return builder.length() == 0 ? "无配方原料" : builder.toString();
    }

    static boolean canFinishBatch(boolean statsProbeReceived, boolean pathConfirmable) {
        // 早到全量包可能还是上一批的库存，必须等统计屏障后才能发下一批。
        return statsProbeReceived && pathConfirmable;
    }

    static boolean shouldCheckBatchTerminal(boolean statsProbePending) {
        return !statsProbePending;
    }

    static boolean shouldResumePickupWait(boolean ingredientsAvailable,
                                          int remainingTicks) {
        return ingredientsAvailable || remainingTicks <= 0;
    }

    static boolean canRetryCursorRecovery(int attempts, int maxAttempts) {
        return attempts >= 0 && attempts < Math.max(0, maxAttempts);
    }

    /** Revision 回卷感知的比较：candidate 是否在 base 之后的半圈窗口内（revision 限制在 0..32767）。 */
    static boolean isRevisionAfter(int candidate, int base) {
        int distance = candidate - base & 32767;
        return distance > 0 && distance < 16384;
    }

    static boolean canConfirmBarrier(boolean statsProbeReceived,
                                     boolean recipeReadyFullObserved,
                                     boolean fullContainsExpectedOutput,
                                     boolean cursorEmpty,
                                     boolean recipeMatches) {
        return statsProbeReceived && recipeReadyFullObserved
                && fullContainsExpectedOutput && cursorEmpty && recipeMatches;
    }

    static boolean canConfirmManualBatch(boolean authoritativeStateReceived,
                                         boolean cursorEmpty,
                                         boolean patternReady,
                                         boolean expectedOutputPresent) {
        return authoritativeStateReceived && cursorEmpty && patternReady && expectedOutputPresent;
    }

    static boolean canConfirmOutputDrain(boolean authoritativeStateReceived,
                                         boolean cursorEmpty,
                                         boolean outputEmpty,
                                         boolean ingredientsDecreased,
                                         boolean expectedOutputPresent,
                                         boolean authoritativeExpectedOutput) {
        if (!authoritativeStateReceived || !cursorEmpty) {
            return false;
        }
        if (outputEmpty) {
            return authoritativeExpectedOutput;
        }
        return ingredientsDecreased && expectedOutputPresent && authoritativeExpectedOutput;
    }

    static boolean shouldContinueOutputDrain(boolean cursorEmpty,
                                             boolean outputEmpty,
                                             boolean expectedOutputPresent,
                                             boolean authoritativeExpectedOutput,
                                             boolean ingredientsDecreased,
                                             int drainRetries,
                                             int maxDrainRetries) {
        if (!cursorEmpty) {
            return false;
        }
        if (outputEmpty) {
            return authoritativeExpectedOutput;
        }
        if (!expectedOutputPresent || !authoritativeExpectedOutput) {
            return false;
        }
        if (ingredientsDecreased) {
            return true;
        }
        return drainRetries < Math.max(0, maxDrainRetries);
    }

    static boolean canContinueOutputDrainWithIntermediate(boolean cursorEmpty,
                                                          boolean expectedOutputAtDispatch,
                                                          boolean ingredientsDecreased,
                                                          boolean compatibleIntermediateOutput) {
        return cursorEmpty && expectedOutputAtDispatch
                && ingredientsDecreased && compatibleIntermediateOutput;
    }

    static boolean ingredientsDecreased(int[] before, int[] after) {
        if (before == null || after == null || before.length == 0 || before.length != after.length) {
            return false;
        }
        boolean decreased = false;
        for (int i = 0; i < before.length; i++) {
            if (after[i] > before[i]) {
                return false;
            }
            if (after[i] < before[i]) {
                decreased = true;
            }
        }
        return decreased;
    }

    static boolean includesManualRefill(BatchPath path) {
        return path == BatchPath.MANUAL_REFILL || path == BatchPath.MANUAL_COMBINED;
    }

    static boolean canConfirmCombinedBatch(boolean fullReceived,
                                           boolean cursorEmpty,
                                           boolean terminalOutputCompatible,
                                           boolean authoritativeExpectedOutput,
                                           boolean patternCompatible) {
        return fullReceived && cursorEmpty && terminalOutputCompatible
                && authoritativeExpectedOutput && patternCompatible;
    }

    static int plannedOutputThrows(boolean expectedOutput, boolean patternComplete) {
        return expectedOutput && patternComplete ? 1 : 0;
    }

    static long ackTimeoutMillis(long observedMaxAckNanos) {
        long adaptive = Math.max(
                MIN_ACK_TIMEOUT_MILLIS,
                nanosToMillis(observedMaxAckNanos) * 8L
        );
        return Math.min(MAX_ACK_TIMEOUT_MILLIS, adaptive);
    }

    static boolean canDispatchWithinTick(int dispatched, int configuredLimit) {
        return dispatched < Math.max(1, configuredLimit);
    }

    static boolean canReleaseCanceledDrain(OutputAckSequence sequence,
                                           boolean failed,
                                           int revision) {
        return failed
                ? sequence.canFinishFailure(revision)
                : sequence.sawRecipeReadyFull() || sequence.sawAuthoritativeEmpty();
    }

    static boolean canDropTail(boolean awaiting, boolean cursorEmpty) {
        return !awaiting && cursorEmpty;
    }

    static boolean needsCanceledBatchDrain(boolean awaiting,
                                           boolean cursorEmpty,
                                           boolean statsProbePending) {
        return awaiting && cursorEmpty && statsProbePending;
    }

    private static java.util.List<ItemStack> copyPattern(java.util.List<ItemStack> pattern) {
        java.util.ArrayList<ItemStack> copy = new java.util.ArrayList<>(pattern.size());
        for (ItemStack stack : pattern) {
            copy.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return java.util.List.copyOf(copy);
    }

    private static boolean isExpectedOutput(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && !template.isEmpty()
                && stack.getCount() == template.getCount()
                && ItemStack.isSameItemSameComponents(stack, template);
    }

    private static boolean isSameItem(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && !template.isEmpty()
                && ItemStack.isSameItemSameComponents(stack, template);
    }

    private static boolean shouldInfoLogBatch(long batchId) {
        return batchId <= 8L || batchId % PERIODIC_BATCH_LOG_INTERVAL == 0L;
    }

    private static long minNonZero(long current, long candidate) {
        return current == 0L ? candidate : Math.min(current, candidate);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static String describeStack(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? "空"
                : stack.getHoverName().getString() + "x" + stack.getCount();
    }

    @FunctionalInterface
    public interface LegacyFallbackHandler {
        void fallback(Minecraft client,
                      AbstractContainerMenu handler,
                      RecipeDisplayId recipeId);
    }

    @FunctionalInterface
    public interface SnapshotRefillHandler {
        boolean refill(Minecraft client,
                       AbstractContainerMenu handler,
                       int maxSourceStacksPerIngredient);
    }

    @FunctionalInterface
    public interface IngredientAvailabilityHandler {
        boolean hasIngredients(AbstractContainerMenu handler);
    }

    @FunctionalInterface
    public interface FinishHandler {
        int finish(Component message, boolean allowTailDrop);
    }

    enum BatchPath {
        MANUAL_REFILL("手动补货"),
        MANUAL_COMBINED("取产物+手动补货"),
        OUTPUT_DRAIN("分段取产物");

        private final String description;

        BatchPath(String description) {
            this.description = description;
        }
    }

    /**
     * Captures the exact client-side click before and after vanilla builds the
     * ClickSlot packet. This is the only reliable way to distinguish a client
     * THROW of an ingredient from a server-side correction that merely makes a
     * material disappear from the local prediction.
     */
    public static void onClientClickStart(int syncId,
                                          int slotId,
                                          int button,
                                          ContainerInput actionType,
                                          Player player) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || player == null
                || player.containerMenu == null
                || player.containerMenu.containerId != syncId
                || active.session == null) {
            return;
        }
        Session current = active.session;
        AbstractContainerMenu handler = player.containerMenu;
        if (active.dispatchingBatch
                && slotId == QuickCraftRecipeBookLayout.OUTPUT_SLOT
                && (actionType == ContainerInput.QUICK_MOVE
                || actionType == ContainerInput.PICKUP
                || actionType == ContainerInput.THROW)) {
            current.outputSlotClicks++;
        }
        ItemStack target = slotId >= 0 && slotId < handler.slots.size()
                ? handler.getSlot(slotId).getItem().copy()
                : ItemStack.EMPTY;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("普通配方书客户端槽位操作开始：界面={}，批次=#{}，awaiting={}，slot={}，"
                            + "action={}，button={}，目标={}，光标={}，revision={}，格子={}，材料={}",
                    active.layout.name(), active.dispatchingBatch
                            ? active.dispatchingBatchLogId : current.batchId,
                    current.awaiting, slotId, actionType,
                    button, describeStack(target), describeStack(handler.getCarried()),
                    handler.getStateId(), active.describeGrid(handler),
                    active.describeMaterialLedger(current, handler));
        }
    }

    public static boolean shouldBlockIngredientThrow(int syncId,
                                                      int slotId,
                                                      int button,
                                                      ContainerInput actionType,
                                                      Player player) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || actionType != ContainerInput.THROW
                || player == null || player.containerMenu == null
                || player.containerMenu.containerId != syncId || active.session == null
                || slotId <= QuickCraftRecipeBookLayout.OUTPUT_SLOT
                || slotId >= player.containerMenu.slots.size()) {
            return false;
        }
        Session current = active.session;
        AbstractContainerMenu handler = player.containerMenu;
        ItemStack target = handler.getSlot(slotId).getItem().copy();
        if (!active.isPatternIngredient(current, target)) {
            return false;
        }
        current.ingredientDrops++;
        LOGGER.error("普通配方书已拦截客户端原料THROW：界面={}，批次=#{}，slot={}，物品={}，"
                        + "button={}，revision={}，光标={}，格子={}，材料={}",
                active.layout.name(), current.batchId, slotId, describeStack(target), button,
                handler.getStateId(), describeStack(handler.getCarried()),
                active.describeGrid(handler), active.describeMaterialLedger(current, handler));
        return true;
    }

    public static void onClientClickEnd(int syncId,
                                        int slotId,
                                        int button,
                                        ContainerInput actionType,
                                        Player player) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || player == null
                || player.containerMenu == null
                || player.containerMenu.containerId != syncId
                || active.session == null || !LOGGER.isDebugEnabled()) {
            return;
        }
        Session current = active.session;
        AbstractContainerMenu handler = player.containerMenu;
        ItemStack target = slotId >= 0 && slotId < handler.slots.size()
                ? handler.getSlot(slotId).getItem().copy()
                : ItemStack.EMPTY;
        LOGGER.debug("普通配方书客户端槽位操作结束：界面={}，批次=#{}，awaiting={}，slot={}，"
                        + "action={}，button={}，结果槽={}，光标={}，revision={}，格子={}，材料={}",
                active.layout.name(), active.dispatchingBatch
                        ? active.dispatchingBatchLogId : current.batchId,
                current.awaiting, slotId, actionType,
                button, describeStack(target), describeStack(handler.getCarried()),
                handler.getStateId(), active.describeGrid(handler),
                active.describeMaterialLedger(current, handler));
    }

    private boolean isPatternIngredient(Session current, ItemStack stack) {
        if (current == null || stack == null || stack.isEmpty()) {
            return false;
        }
        for (ItemStack template : current.pattern) {
            if (!template.isEmpty() && ItemStack.isSameItemSameComponents(template, stack)) {
                return true;
            }
        }
        return false;
    }

    private String describeMaterialLedger(Session current, AbstractContainerMenu handler) {
        if (current == null || handler == null) {
            return "不可用";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < current.pattern.size(); i++) {
            ItemStack template = current.pattern.get(i);
            if (template.isEmpty() || hasEarlierPattern(current.pattern, i, template)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            int gridCount = 0;
            for (int gridIndex = 0; gridIndex < layout.gridSize(); gridIndex++) {
                ItemStack stack = handler.getSlot(layout.gridSlotId(gridIndex)).getItem();
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    gridCount += stack.getCount();
                }
            }
            int inventoryCount = 0;
            for (int inventoryIndex = 0;
                 inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
                 inventoryIndex++) {
                int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
                if (slotId < 0) {
                    continue;
                }
                ItemStack stack = handler.getSlot(slotId).getItem();
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    inventoryCount += stack.getCount();
                }
            }
            builder.append(template.getHoverName().getString())
                    .append(" grid=").append(gridCount)
                    .append(" inventory=").append(inventoryCount);
        }
        return builder.length() == 0 ? "无配方原料" : builder.toString();
    }

    private static boolean hasEarlierPattern(List<ItemStack> pattern,
                                             int index,
                                             ItemStack template) {
        for (int i = 0; i < index; i++) {
            ItemStack earlier = pattern.get(i);
            if (!earlier.isEmpty() && ItemStack.isSameItemSameComponents(earlier, template)) {
                return true;
            }
        }
        return false;
    }

    public static void onServerSlotUpdate(int syncId,
                                          int revision,
                                          int slotId,
                                          ItemStack stack) {
        if (hasCanceledBatchDrain(syncId)) {
            updateCanceledBatchDrains(syncId, revision, slotId, stack);
            return;
        }
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerSlotUpdate(syncId, revision, slotId, stack);
        }
        updateCanceledBatchDrains(syncId, revision, slotId, stack);
    }

    public static void onServerInventoryUpdate(int syncId,
                                               int revision,
                                               List<ItemStack> contents,
                                               ItemStack cursorStack) {
        if (hasCanceledBatchDrain(syncId)) {
            updateCanceledBatchDrainsFull(
                    syncId, revision, contents, cursorStack);
            return;
        }
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerInventoryUpdate(syncId, revision, contents, cursorStack);
        }
        updateCanceledBatchDrainsFull(
                syncId, revision, contents, cursorStack);
    }

    public static void onServerStatistics(ClientPacketListener source) {
        QuickCraftRecipeBookAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerStatistics(source);
        }
    }

    static boolean isCanceledBatchDraining(AbstractContainerMenu handler) {
        return handler != null && hasCanceledBatchDrain(handler.containerId);
    }

    private static boolean hasCanceledBatchDrain(int syncId) {
        pruneExpiredCanceledBatchDrains();
        return CANCELED_BATCH_DRAINS.stream()
                .anyMatch(drain -> drain.containerId == syncId);
    }

    private static void beginCanceledBatchDrain(
            Session canceled,
            QuickCraftRecipeBookLayout.Layout layout) {
        pruneExpiredCanceledBatchDrains();
        CANCELED_BATCH_DRAINS.removeIf(drain -> drain.containerId == canceled.containerId);
        CANCELED_BATCH_DRAINS.add(new CanceledBatchDrain(
                canceled.containerId,
                canceled.recipeId,
                layout,
                canceled.pattern,
                canceled.resultTemplate.copy(),
                canceled.outputAck,
                canceled.craftFailed,
                canceled.connection,
                canceled.player,
                System.nanoTime() + CANCELED_BATCH_DRAIN_TIMEOUT_NANOS
        ));
        LOGGER.debug("手动补货未决批次进入隔离：syncId={}，配方={}，批次=#{}，失败包={}",
                canceled.containerId, canceled.recipeId, canceled.batchId, canceled.craftFailed);
    }

    private static boolean updateCanceledBatchDrains(int syncId,
                                                     int revision,
                                                     int slotId,
                                                     ItemStack stack) {
        pruneExpiredCanceledBatchDrains();
        boolean matched = false;
        Iterator<CanceledBatchDrain> iterator = CANCELED_BATCH_DRAINS.iterator();
        while (iterator.hasNext()) {
            CanceledBatchDrain drain = iterator.next();
            if (drain.containerId != syncId) {
                continue;
            }
            matched = true;
            if (slotId >= 0) {
                drain.observeSlot(revision, slotId, stack);
            }
        }
        return matched;
    }

    private static void updateCanceledBatchDrainsFull(int syncId,
                                                      int revision,
                                                      List<ItemStack> contents,
                                                      ItemStack cursorStack) {
        pruneExpiredCanceledBatchDrains();
        Iterator<CanceledBatchDrain> iterator = CANCELED_BATCH_DRAINS.iterator();
        while (iterator.hasNext()) {
            CanceledBatchDrain drain = iterator.next();
            if (drain.containerId != syncId) {
                continue;
            }
            drain.observeFull(revision, contents, cursorStack);
            if (drain.isTerminalFull(revision)) {
                LOGGER.debug("手动补货取消批次终态已排空：syncId={}，配方={}，revision={}",
                        drain.containerId, drain.recipeId, revision);
                iterator.remove();
            }
        }
    }

    private static void pruneExpiredCanceledBatchDrains() {
        long now = System.nanoTime();
        Minecraft client = Minecraft.getInstance();
        Iterator<CanceledBatchDrain> iterator = CANCELED_BATCH_DRAINS.iterator();
        while (iterator.hasNext()) {
            CanceledBatchDrain drain = iterator.next();
            if (client.getConnection() != drain.connection
                    || client.player != drain.player) {
                LOGGER.debug("手动补货取消批次因连接变化清理：syncId={}，配方={}",
                        drain.containerId, drain.recipeId);
                iterator.remove();
                continue;
            }
            if (now < drain.expiresAtNanos) {
                continue;
            }
            LOGGER.warn("手动补货取消批次隔离超时放行：syncId={}，配方={}",
                    drain.containerId, drain.recipeId);
            iterator.remove();
        }
    }

    static final class OutputAckSequence {
        private int batchStartRevision;
        private int authoritativeEmptyRevision;
        private int expectedRevision;
        private int recipeReadyFullRevision;
        private boolean sawAuthoritativeEmpty;
        private boolean sawExpectedAfterEmpty;
        private boolean sawRecipeReadyFull;
        private boolean failed;

        void reset(int batchStartRevision) {
            this.batchStartRevision = batchStartRevision;
            this.authoritativeEmptyRevision = Integer.MIN_VALUE;
            this.expectedRevision = Integer.MIN_VALUE;
            this.recipeReadyFullRevision = Integer.MIN_VALUE;
            this.sawAuthoritativeEmpty = false;
            this.sawExpectedAfterEmpty = false;
            this.sawRecipeReadyFull = false;
            this.failed = false;
        }

        void observe(int revision,
                     boolean empty,
                     boolean expectedOutput,
                     boolean allowExpectedOutput) {
            if (!isRevisionAfter(
                    revision, batchStartRevision)) {
                return;
            }
            if (empty) {
                if (sawAuthoritativeEmpty
                        && revision != authoritativeEmptyRevision
                        && !isRevisionAfter(
                        revision, authoritativeEmptyRevision)) {
                    return;
                }
                if (sawRecipeReadyFull && isRevisionAfter(
                        revision, recipeReadyFullRevision)) {
                    sawRecipeReadyFull = false;
                    recipeReadyFullRevision = Integer.MIN_VALUE;
                }
                authoritativeEmptyRevision = revision;
                sawAuthoritativeEmpty = true;
                sawExpectedAfterEmpty = false;
                expectedRevision = Integer.MIN_VALUE;
                return;
            }
            if (!expectedOutput
                    && sawRecipeReadyFull
                    && isRevisionAfter(
                    revision, recipeReadyFullRevision)) {
                sawRecipeReadyFull = false;
                recipeReadyFullRevision = Integer.MIN_VALUE;
            }
            if (!expectedOutput
                    && sawExpectedAfterEmpty
                    && isRevisionAfter(
                    revision, expectedRevision)) {
                // A later wrong result invalidates the whole empty -> expected
                // sequence; do not allow a client prediction to confirm it.
                sawAuthoritativeEmpty = false;
                authoritativeEmptyRevision = Integer.MIN_VALUE;
                sawExpectedAfterEmpty = false;
                expectedRevision = Integer.MIN_VALUE;
            }
            if (sawAuthoritativeEmpty
                    && allowExpectedOutput
                    && expectedOutput
                    && isRevisionAfter(
                    revision, authoritativeEmptyRevision)
                    && (!sawExpectedAfterEmpty
                    || revision == expectedRevision
                    || isRevisionAfter(
                    revision, expectedRevision))) {
                sawExpectedAfterEmpty = true;
                expectedRevision = revision;
            }
        }

        void observeRecipeReadyFull(int revision, boolean recipeReady) {
            if (!isRevisionAfter(
                    revision, batchStartRevision)) {
                return;
            }
            if (recipeReady) {
                if (sawAuthoritativeEmpty
                        && revision != authoritativeEmptyRevision
                        && !isRevisionAfter(
                        revision, authoritativeEmptyRevision)) {
                    return;
                }
                if (sawRecipeReadyFull
                        && revision != recipeReadyFullRevision
                        && !isRevisionAfter(
                        revision, recipeReadyFullRevision)) {
                    return;
                }
                sawRecipeReadyFull = true;
                recipeReadyFullRevision = revision;
            } else if (sawRecipeReadyFull
                    && isRevisionAfter(
                    revision, recipeReadyFullRevision)) {
                sawRecipeReadyFull = false;
                recipeReadyFullRevision = Integer.MIN_VALUE;
            }
        }

        void markFailed() {
            failed = true;
        }

        boolean canFinishFailure(int revision) {
            return failed && isRevisionAfter(
                    revision, batchStartRevision);
        }

        boolean sawAuthoritativeEmpty() {
            return sawAuthoritativeEmpty;
        }

        boolean sawExpectedAfterEmpty() {
            return sawExpectedAfterEmpty;
        }

        boolean sawRecipeReadyFull() {
            return sawRecipeReadyFull;
        }

        int recipeReadyFullRevision() {
            return recipeReadyFullRevision;
        }
    }

    private static final class CanceledBatchDrain {
        private final int containerId;
        private final RecipeDisplayId recipeId;
        private final QuickCraftRecipeBookLayout.Layout layout;
        private final List<ItemStack> pattern;
        private final ItemStack resultTemplate;
        private final OutputAckSequence outputAck;
        private final ClientPacketListener connection;
        private final LocalPlayer player;
        private final long expiresAtNanos;
        private boolean failed;

        private CanceledBatchDrain(int syncId,
                                   RecipeDisplayId recipeId,
                                   QuickCraftRecipeBookLayout.Layout layout,
                                   List<ItemStack> pattern,
                                   ItemStack resultTemplate,
                                   OutputAckSequence outputAck,
                                   boolean failed,
                                   ClientPacketListener networkHandler,
                                   LocalPlayer player,
                                   long expiresAtNanos) {
            this.containerId = syncId;
            this.recipeId = recipeId;
            this.layout = layout;
            this.pattern = copyPattern(pattern);
            this.resultTemplate = resultTemplate;
            this.outputAck = outputAck;
            this.failed = failed;
            this.connection = networkHandler;
            this.player = player;
            this.expiresAtNanos = expiresAtNanos;
            if (failed) {
                this.outputAck.markFailed();
            }
        }

        private void observeSlot(int revision, int slotId, ItemStack stack) {
            if (slotId == QuickCraftRecipeBookLayout.OUTPUT_SLOT) {
                outputAck.observe(
                        revision,
                        stack == null || stack.isEmpty(),
                        stack != null && isExpectedOutput(stack, resultTemplate),
                        true
                );
            }
        }

        private void observeFull(int revision,
                                 List<ItemStack> contents,
                                 ItemStack cursorStack) {
            ItemStack output = contents == null || contents.isEmpty()
                    ? ItemStack.EMPTY
                    : contents.get(QuickCraftRecipeBookLayout.OUTPUT_SLOT);
            boolean expectedOutput = isExpectedOutput(output, resultTemplate);
            outputAck.observe(
                    revision,
                    output.isEmpty(),
                    expectedOutput,
                    true
            );
            outputAck.observeRecipeReadyFull(
                    revision,
                    expectedOutput
                            && cursorStack != null
                            && cursorStack.isEmpty()
                            && isPatternComplete(contents, layout, pattern)
            );
        }

        private boolean isTerminalFull(int revision) {
            return canReleaseCanceledDrain(outputAck, failed, revision);
        }
    }

    private static final class Session {
        private final AbstractContainerMenu handler;
        private final int containerId;
        private final RecipeDisplayId recipeId;
        private final ItemStack resultTemplate;
        private final java.util.List<ItemStack> pattern;
        private final BooleanSupplier inputHeld;
        private final FinishHandler finishHandler;
        private final ClientPacketListener connection;
        private final LocalPlayer player;
        private final long startedAtNanos;

        private boolean awaiting;
        private int outputThrows;
        private boolean stopRequested;
        private Component stopMessage;
        private final OutputAckSequence outputAck = new OutputAckSequence();
        private boolean craftFailed;
        private boolean unexpectedOutputLogged;
        private boolean invalidAuthoritativeOutput;
        private boolean batchSawExpectedOutput;
        private boolean outputExpectedAtDispatch;
        private int intermediateOutputPackets;
        private int authoritativeWrongOutputPackets;
        private String lastAuthoritativeMaterialLedger = "未收到";
        private int[] lastAuthoritativeMaterialCounts;
        private int lastAuthoritativeMaterialRevision = Integer.MIN_VALUE;
        private int[] batchAuthoritativeMaterialCountsBefore;
        private int batchOutputOperations;
        private int batchOutputSlotClicks;
        private int outputSlotClicks;
        private int remainderThrows;
        private long batchId;
        private int batchStartRevision;
        private int batchFullUpdatesStart;
        private int lastProgressRevision;
        private long batchSentAtNanos;
        private long lastProgressAtNanos;
        private boolean statsProbePending;
        private boolean cursorRecoveryActive;
        private int cursorRecoveryAttempts;
        private BatchPath batchPath = BatchPath.OUTPUT_DRAIN;
        private int[] batchGridCounts = new int[0];
        private int[] batchCraftGridCounts = new int[0];
        private int refillRetries;
        private int refillRetryBatches;
        private int drainRetries;
        private int drainRetryBatches;
        private int pickupWaitTicks;
        private int pickupWaitBatches;
        private int ingredientDrops;

        private int batches;
        private int confirmedBatches;
        private int recipeRequests;
        private int barriersSent;
        private int statsProbesSent;
        private int statsProbeResponses;
        private int quickMoves;
        private int snapshotRefillBatches;
        private int snapshotRefillSuccesses;
        private int legacyHandoffs;
        private int slotUpdates;
        private int fullUpdates;
        private int failurePackets;
        private int intermediateOutputPacketsTotal;
        private int authoritativeWrongOutputPacketsTotal;
        private int timeouts;
        private long ackLatencyNanos;
        private long minAckLatencyNanos;
        private long maxAckLatencyNanos;
        private long localDispatchNanos;
        private long firstFullUpdateAtNanos;
        private long statsResponseAtNanos;
        private long fullUpdateLatencyNanos;
        private long statsResponseLatencyNanos;
        private int fullUpdateSamples;
        private int statsResponseSamples;
        private int ackPhaseSamples;

        private Session(AbstractContainerMenu handler,
                        int syncId,
                        RecipeDisplayId recipeId,
                        ItemStack resultTemplate,
                        java.util.List<ItemStack> pattern,
                        BooleanSupplier inputHeld,
                        FinishHandler finishHandler,
                        ClientPacketListener networkHandler,
                        LocalPlayer player,
                        long startedAtNanos) {
            this.handler = handler;
            this.containerId = syncId;
            this.recipeId = recipeId;
            this.resultTemplate = resultTemplate;
            this.pattern = pattern;
            this.inputHeld = inputHeld;
            this.finishHandler = finishHandler;
            this.connection = networkHandler;
            this.player = player;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
