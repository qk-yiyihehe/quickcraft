package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckRules.*;

/**
 * 普通持续合成的手动补货 ACK 执行器。
 * 每批用鼠标点击把原料均分进合成格，再取产物，然后等服务端终态。
 */
public final class QuickCraftMouseCraftAckExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/RecipeBookCraft");
    private static final int PERIODIC_BATCH_LOG_INTERVAL = 10;
    private static final long CANCELED_BATCH_DRAIN_TIMEOUT_NANOS = 15_000_000_000L;
    private static final int MAX_REFILL_RETRIES = 3;
    private static final int MAX_DRAIN_RETRIES = 3;
    private static final int MAX_PICKUP_WAIT_TICKS = 10;
    private static final int FULL_GRID_CRAFTS = 64;
    private static QuickCraftMouseCraftAckExecutor activeExecutor;
    private static final List<CanceledBatchDrain> CANCELED_BATCH_DRAINS = new ArrayList<>();

    private final QuickCraftMouseCraftLayout.Layout layout;
    private final LegacyFallbackHandler fallbackHandler;
    private final SnapshotRefillHandler snapshotRefillHandler;
    private final IngredientAvailabilityHandler ingredientAvailabilityHandler;
    private Session session;
    private int dispatchedThisTick;
    private boolean pumping;
    private boolean pumpAgain;
    private boolean dispatchingBatch;
    private long dispatchingBatchLogId;

    public QuickCraftMouseCraftAckExecutor(QuickCraftMouseCraftLayout.Layout layout) {
        this(layout, null, null, null);
    }

    public QuickCraftMouseCraftAckExecutor(QuickCraftMouseCraftLayout.Layout layout,
                                           LegacyFallbackHandler fallbackHandler) {
        this(layout, fallbackHandler, null, null);
    }

    public QuickCraftMouseCraftAckExecutor(QuickCraftMouseCraftLayout.Layout layout,
                                           LegacyFallbackHandler fallbackHandler,
                                           SnapshotRefillHandler snapshotRefillHandler) {
        this(layout, fallbackHandler, snapshotRefillHandler, null);
    }

    public QuickCraftMouseCraftAckExecutor(QuickCraftMouseCraftLayout.Layout layout,
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return false;
        }
        if (activeExecutor != null && activeExecutor.isActive()) {
            return true;
        }
        return client.player != null
                && isCanceledBatchDraining(client.player.currentScreenHandler);
    }

    public boolean owns(ScreenHandler handler) {
        return isActive() && session.handler == handler && session.syncId == handler.syncId;
    }

    /**
     * Rebuilds the client-side result after a local output/refill click. The vanilla
     * handler deliberately skips updateResult on the client, so without this small
     * prediction the next output is not visible until the server ACK arrives.
     */
    public static boolean refreshClientPrediction(ScreenHandler handler,
                                                  World world,
                                                  RecipeInputInventory craftingInventory,
                                                  CraftingResultInventory resultInventory) {
        if (craftingInventory == null) {
            return false;
        }
        return refreshClientPrediction(handler, world, craftingInventory.createRecipeInput(), resultInventory);
    }

    private static boolean refreshClientPrediction(ScreenHandler handler,
                                                   World world,
                                                   CraftingRecipeInput input,
                                                   CraftingResultInventory resultInventory) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active == null || !active.isActive()
                || world == null || !world.isClient
                || handler == null || input == null || resultInventory == null) {
            return false;
        }
        Session current = active.session;
        if (current.awaiting || current.handler != handler || current.syncId != handler.syncId) {
            return false;
        }

        ItemStack predicted = QuickCraftMouseCraftInventory.isPatternComplete(
                handler, active.layout, current.pattern)
                ? current.resultTemplate.copy()
                : ItemStack.EMPTY;

        resultInventory.setStack(QuickCraftMouseCraftLayout.OUTPUT_SLOT, predicted);
        if (LOGGER.isDebugEnabled() && (predicted.isEmpty() || current.batchId <= 2L)) {
            LOGGER.debug("普通配方书客户端预测刷新：界面={}，批次=#{}，输出={}，格子={}",
                    active.layout.name(), current.batchId, describeStack(predicted),
                    active.describeGrid(handler));
        }
        return true;
    }

    /** Refreshes the result slot using the handler's crafting inventory when available. */
    private static void refreshClientPrediction(ScreenHandler handler) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || handler == null) {
            return;
        }
        try {
            if (!(handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).inventory
                    instanceof CraftingResultInventory resultInventory)) {
                return;
            }
            List<ItemStack> stacks = new ArrayList<>(active.layout.gridSize());
            for (int i = 0; i < active.layout.gridSize(); i++) {
                stacks.add(handler.getSlot(active.layout.gridSlotId(i)).getStack().copy());
            }
            CraftingRecipeInput input = CraftingRecipeInput.create(
                    active.layout.gridWidth(), active.layout.gridHeight(), stacks);
            refreshClientPrediction(handler, MinecraftClient.getInstance().world, input, resultInventory);
        } catch (Throwable throwable) {
            LOGGER.debug("普通配方书客户端预测刷新跳过：界面={}，批次=#{}",
                    active.layout.name(), active.session.batchId, throwable);
        }
    }

    public boolean canStart(ScreenHandler handler, ItemStack resultTemplate) {
        if (handler == null || resultTemplate == null || resultTemplate.isEmpty()) {
            return false;
        }
        if (isCanceledBatchDraining(handler)) {
            return false;
        }
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }
        ItemStack output = handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).getStack();
        return output.isEmpty() || isExpectedOutput(output, resultTemplate);
    }

    public boolean start(MinecraftClient client,
                         ScreenHandler handler,
                         NetworkRecipeId recipeId,
                         ItemStack resultTemplate,
                         java.util.List<ItemStack> pattern,
                         BooleanSupplier inputHeld,
                         FinishHandler finishHandler) {
        if (client == null || client.player == null || client.world == null
                || client.interactionManager == null || client.getNetworkHandler() == null
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
                handler.syncId,
                recipeId,
                resultTemplate.copy(),
                copyPattern(pattern),
                inputHeld,
                finishHandler,
                client.getNetworkHandler(),
                client.player,
                System.nanoTime()
        );
        session.outputFillSlotsRemaining = QuickCraftMouseCraftInventory.unlockedEmptySlots(
                handler, layout);
        session.outputSprayMode = session.outputFillSlotsRemaining == 0;
        MaterialSnapshot initialMaterials = materialSnapshotFromHandler(session, handler);
        session.lastAuthoritativeMaterialLedger = initialMaterials.ledger();
        session.lastAuthoritativeMaterialCounts = initialMaterials.counts();
        session.lastAuthoritativeMaterialRevision = handler.getRevision();
        activeExecutor = this;
        dispatchedThisTick = 0;
        LOGGER.info("手动补货ACK会话开始：界面={}，配方={}，产物={}，syncId={}，revision={}，"
                        + "每Tick批次上限={}，初始空格={}，喷射模式={}，停止尾货丢弃={}，按住={}",
                layout.name(), recipeId, describeStack(resultTemplate), handler.syncId,
                handler.getRevision(), QuickCraftConfigs.getCraftLoopsPerTick(),
                session.outputFillSlotsRemaining, session.outputSprayMode,
                QuickCraftConfigs.isDropCraftResultsOnStopEnabled(),
                session.inputHeld.getAsBoolean());
        return true;
    }

    public void tick(MinecraftClient client) {
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
            tickAwaitingBatch(current);
            return;
        }
        if (current.pickupWaitTicks > 0) {
            tickPickupWait(client, current);
            return;
        }
        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finishAfterInputRelease(current, "输入释放");
            return;
        }
        pump(client);
    }

    private void tickAwaitingBatch(Session current) {
        long now = System.nanoTime();
        if (shouldSendDeferredStatsProbe(
                current.awaiting, current.statsProbeDeferred, current.statsProbePending)) {
            requestStatsProbe(current, current.networkHandler, "一个客户端 tick 内未取得可确认全量终态");
        }
        long elapsedMillis = nanosToMillis(now - current.batchSentAtNanos);
        long stalledMillis = nanosToMillis(now - current.lastProgressAtNanos);
        long timeoutMillis = ackTimeoutMillis(current.maxAckLatencyNanos);
        if (stalledMillis < timeoutMillis && elapsedMillis < MAX_ACK_TIMEOUT_MILLIS) {
            return;
        }
        current.timeouts++;
        LOGGER.warn("手动补货ACK超时：界面={}，批次=#{}，配方={}，等待={} ms，阈值={} ms，"
                        + "revision={}->{}, 空边界={}，正确产物={}，配方终态全量={}@{}，"
                        + "统计确认={}/{}, pending={}，当前配方匹配={}，失败包={}，光标={}，输出={}",
                layout.name(), current.batchId, current.recipeId, elapsedMillis,
                timeoutMillis, current.batchStartRevision, current.handler.getRevision(),
                current.outputAck.sawAuthoritativeEmpty(),
                current.outputAck.sawExpectedAfterEmpty(),
                current.outputAck.sawRecipeReadyFull(),
                current.outputAck.recipeReadyFullRevision(),
                current.statsProbeResponses, current.statsProbesSent,
                current.statsProbePending, recipeMatches(current), current.craftFailed,
                describeStack(current.handler.getCursorStack()),
                describeStack(current.handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).getStack()));
        finishNoIngredients("ACK超时");
    }

    private void tickPickupWait(MinecraftClient client, Session current) {
        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            current.pickupWaitTicks = 0;
            finishAfterInputRelease(current, "拾取等待期间输入释放");
            return;
        }
        boolean ingredientsAvailable = hasIngredients(current.handler);
        current.pickupWaitTicks--;
        if (!shouldResumePickupWait(ingredientsAvailable, current.pickupWaitTicks)) {
            return;
        }
        current.pickupWaitTicks = 0;
        LOGGER.info("手动补货ACK拾取等待结束，重新检查原料：界面={}，批次=#{}，原因={}，"
                        + "格子={}，按住={}",
                layout.name(), current.batchId,
                ingredientsAvailable ? "检测到可补原料" : "等待到期",
                describeGrid(current.handler), current.inputHeld.getAsBoolean());
        if (ingredientsAvailable) {
            pump(client);
        } else {
            finishNoIngredients(
                    current.pickupWaitForFullInventory
                            ? "释放满包产物后等待拾取到期"
                            : "非满包自然拾取等待到期后仍无可补原料");
        }
    }

    public void requestStop(MinecraftClient client, Text message) {
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

    private void pump(MinecraftClient client) {
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
                        finishAfterInputRelease(session, "输入释放");
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

    private boolean dispatchBatch(MinecraftClient client) {
        Session current = session;
        ScreenHandler handler = current.handler;
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        if (client.player == null || interactionManager == null || networkHandler == null) {
            cancel("发送批次前客户端上下文失效");
            return false;
        }

        dispatchingBatch = true;
        dispatchingBatchLogId = current.batchId + 1L;
        try {
            if (!handler.getCursorStack().isEmpty()) {
                LOGGER.warn("手动补货ACK拒绝在光标有物品时继续发包：界面={}，批次=#{}，光标={}",
                        layout.name(), current.batchId, describeStack(handler.getCursorStack()));
                finishStopped("光标非空");
                return false;
            }

            ItemStack output = handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).getStack();
            boolean expectedOutput = isExpectedOutput(output, current.resultTemplate);
            if (!output.isEmpty()
                    && !expectedOutput
                    && !isIntermediateOutput(current, handler)) {
                LOGGER.warn("手动补货ACK输出与快照不一致：界面={}，配方={}，期望={}，实际={}",
                        layout.name(), current.recipeId, describeStack(current.resultTemplate),
                        describeStack(output));
                finishStopped("输出不一致");
                return false;
            }
            current.outputExpectedAtDispatch = expectedOutput;

            long dispatchStartedAtNanos = System.nanoTime();
            int[] gridBeforeClicks = snapshotGridCounts(handler);
            int[] previousAuthoritativeMaterialCounts = current.lastAuthoritativeMaterialCounts == null
                    ? null
                    : current.lastAuthoritativeMaterialCounts.clone();
            MaterialSnapshot materialsBeforeClicks = materialSnapshotFromHandler(current, handler);
            int clientSlotClicksBefore = current.clientSlotClicks;
            int outputSlotClicksBefore = current.outputSlotClicks;
            boolean refilled = false;
            boolean outputThrown = false;

            if (!expectedOutput) {
                refilled = snapshotRefillHandler != null && snapshotRefillHandler.refill(
                        client, handler, QuickCraftConfigs.getCraftLoopsPerTick());
                refreshClientPrediction(handler);
                if (!parkCursor(client, handler, "手动补货后")) {
                    finishStopped("手动补货后光标无法放回");
                    return false;
                }
                output = handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).getStack();
                expectedOutput = isExpectedOutput(output, current.resultTemplate);
            }

            int[] gridBeforeOutput = snapshotGridCounts(handler);
            boolean patternComplete = recipeMatches(current);
            int outputOperations = plannedOutputThrows(expectedOutput, patternComplete);
            if (outputOperations > 0) {
                outputThrown = moveOrThrowOutput(
                        client, current, handler, interactionManager, output);
                current.outputExpectedAtDispatch = true;
                refreshClientPrediction(handler);
            } else if (!refilled && output.isEmpty() && !patternComplete) {
                boolean hasIngredients = hasIngredients(handler);
                LOGGER.info("手动补货ACK本地未补上，发送屏障后重试：界面={}，批次=#{}，"
                                + "背包可补={}，格子={}",
                        layout.name(), current.batchId + 1, hasIngredients,
                        describeGrid(handler));
            }

            if (!parkCursor(client, handler, "批次发送前")) {
                finishStopped("批次发送前光标无法放回");
                return false;
            }

            BatchPath path = outputOperations == 0
                    ? BatchPath.MANUAL_REFILL
                    : refilled ? BatchPath.MANUAL_COMBINED : BatchPath.OUTPUT_DRAIN;

            current.quickMoves += outputOperations;
            MaterialSnapshot materialsAfterClicks = materialSnapshotFromHandler(current, handler);
            beginAwaitingBatch(current, handler, path, gridBeforeClicks, gridBeforeOutput);
            current.batchPlannedCrafts = maximumCraftsFromBatchGrid(current);
            if (outputOperations > 0) {
                current.plannedCrafts += current.batchPlannedCrafts;
                if (current.batchPlannedCrafts >= FULL_GRID_CRAFTS) {
                    current.fullCraftBatches++;
                } else if (current.batchPlannedCrafts > 0) {
                    current.tailCraftBatches++;
                }
            }
            current.batchOutputOperations = outputOperations;
            current.batchClientSlotClicks = Math.max(0,
                    current.clientSlotClicks - clientSlotClicksBefore);
            current.maxClientSlotClicksPerBatch = Math.max(
                    current.maxClientSlotClicksPerBatch, current.batchClientSlotClicks);
            current.batchOutputSlotClicks = Math.max(0,
                    current.outputSlotClicks - outputSlotClicksBefore);
            current.batchMaterialCountsBeforeClicks = cloneMaterialCounts(materialsBeforeClicks.counts());
            if (refilled) {
                current.snapshotRefillBatches++;
                current.snapshotRefillSuccesses++;
            }
            if (outputOperations > 0) {
                current.pickupWaitAttempted = false;
            }

            try {
                sendAckBoundary(networkHandler, current);
            } catch (Throwable throwable) {
                LOGGER.warn("手动补货ACK屏障发送失败：界面={}，批次=#{}，配方={}",
                        layout.name(), current.batchId, current.recipeId, throwable);
                finishStopped("屏障发送异常");
                return false;
            }

            long dispatchMicros = (System.nanoTime() - dispatchStartedAtNanos) / 1_000L;
            current.localDispatchNanos += System.nanoTime() - dispatchStartedAtNanos;
            if (shouldInfoLogBatch(current.batchId)) {
                LOGGER.info("手动补货ACK批次发送：界面={}，批次=#{}，路径={}，配方={}，"
                                + "槽位点击={}，输出槽点击={}，输出槽丢出={}，模式={}，填包剩余={}，补料={}，"
                                + "revision={}，耗时={} us，"
                                + "计划轮数={}，点击前格子={}，当前格子={}，上一权威材料={}，点击前实际材料={}，"
                                + "跨批变化={}，本地点击后材料={}，本地点击变化={}，按住={}",
                        layout.name(), current.batchId, path.description, current.recipeId,
                        current.batchClientSlotClicks, current.batchOutputSlotClicks, outputThrown,
                        current.outputSprayMode ? "喷射" : "填包",
                        current.outputFillSlotsRemaining, refilled,
                        current.batchStartRevision, dispatchMicros,
                        current.batchPlannedCrafts,
                        describeGridCounts(gridBeforeClicks), describeGrid(handler),
                        describeMaterialCounts(previousAuthoritativeMaterialCounts),
                        materialsBeforeClicks.ledger(),
                        describeMaterialCountDelta(current, previousAuthoritativeMaterialCounts,
                                materialsBeforeClicks.counts()),
                        materialsAfterClicks.ledger(),
                        describeMaterialCountDelta(current, materialsBeforeClicks.counts(),
                                materialsAfterClicks.counts()),
                        current.inputHeld.getAsBoolean());
            }
            logBatchDispatch(current, outputOperations, dispatchMicros,
                    path.description + "+全量优先ACK");
            return true;
        } finally {
            dispatchingBatch = false;
        }
    }

    private boolean moveOrThrowOutput(MinecraftClient client,
                                      Session current,
                                      ScreenHandler handler,
                                      ClientPlayerInteractionManager interactionManager,
                                      ItemStack output) {
        if (shouldEnterOutputSprayMode(
                current.outputSprayMode,
                current.outputFillSlotsRemaining,
                QuickCraftMouseCraftInventory.unlockedEmptySlots(handler, layout),
                QuickCraftMouseCraftInventory.canAcceptUnlocked(handler, layout, output))) {
            activateOutputSprayMode(client, current, "背包首次占满");
        }

        boolean movedToInventory = false;
        if (!current.outputSprayMode) {
            int matchingSlotsBefore = QuickCraftMouseCraftInventory.countMatchingUnlockedSlots(
                    handler, layout, current.resultTemplate);
            movedToInventory = QuickCraftMouseCraftInventory.moveOutputToUnlockedInventory(
                    client, handler, layout);
            if (movedToInventory) {
                int matchingSlotsAfter = QuickCraftMouseCraftInventory.countMatchingUnlockedSlots(
                        handler, layout, current.resultTemplate);
                current.outputFillSlotsRemaining = remainingOutputFillSlots(
                        current.outputFillSlotsRemaining,
                        Math.max(0, matchingSlotsAfter - matchingSlotsBefore));
                if (shouldEnterOutputSprayMode(
                        false,
                        current.outputFillSlotsRemaining,
                        QuickCraftMouseCraftInventory.unlockedEmptySlots(handler, layout),
                        true)) {
                    activateOutputSprayMode(client, current, "填包阶段完成");
                }
            }
        }
        if (movedToInventory) {
            return false;
        }

        activateOutputSprayMode(client, current, "产物无法放入背包");
        // 1.21.3 的结果槽 THROW(button=1) 等同原版 Ctrl+丢弃：
        // 服务端会连续合成并丢出全部同类结果，因此每个 ACK 批次只能发送一次。
        interactionManager.clickSlot(
                handler.syncId,
                QuickCraftMouseCraftLayout.OUTPUT_SLOT,
                1,
                SlotActionType.THROW,
                client.player
        );
        current.outputThrows++;
        return true;
    }

    private void activateOutputSprayMode(MinecraftClient client,
                                         Session current,
                                         String reason) {
        if (!current.outputSprayMode) {
            current.outputSprayMode = true;
            current.outputFillSlotsRemaining = 0;
            LOGGER.info("手动补货ACK切换永久喷射模式：界面={}，批次=#{}，原因={}",
                    layout.name(), current.batchId + 1, reason);
        }
        if (current.sprayInventoryCleared) {
            return;
        }
        current.sprayInventoryCleared = true;
        dropInventoryCraftResults(client, current, "进入喷射模式丢出背包产物");
    }

    private int dropInventoryCraftResults(MinecraftClient client,
                                          Session current,
                                          String reason) {
        int dropped = QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                client, current.handler, layout, current.resultTemplate, reason);
        current.inventoryResultDrops += dropped;
        return dropped;
    }

    private void beginAwaitingBatch(Session current,
                                    ScreenHandler handler,
                                    BatchPath path,
                                    int[] gridBeforeClicks,
                                    int[] gridBeforeOutput) {
        current.batchId++;
        current.batches++;
        current.batchPath = path;
        current.batchStartRevision = handler.getRevision();
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
        current.materialLedgerDriftLogged = false;
        // pending 表示“请求已经发出、等待回包”，不能在发送前预置为 true。
        current.statsProbePending = false;
        current.statsProbeDeferred = false;
        current.statsProbeSentForBatch = false;
        current.firstFullUpdateAtNanos = 0L;
        current.statsResponseAtNanos = 0L;
        current.awaiting = true;
        current.batchSentAtNanos = System.nanoTime();
        current.lastProgressAtNanos = current.batchSentAtNanos;
        dispatchedThisTick++;
    }

    private void sendAckBoundary(ClientPlayNetworkHandler networkHandler,
                                 Session current) {
        if (current == null || networkHandler == null) {
            return;
        }
        if (current.batchPath == BatchPath.OUTPUT_DRAIN) {
            // 纯取产物路径的输出槽 THROW 是最后一个请求；严格排空终态可直接证明本批完成。
            current.statsProbeDeferred = true;
            LOGGER.debug("普通配方书ACK延迟统计顺序探针：界面={}，批次=#{}，等待输出操作全量终态",
                    layout.name(), current.batchId);
        } else {
            requestStatsProbe(current, networkHandler, "纯补货批次边界");
        }
    }

    private void requestStatsProbe(Session current,
                                   ClientPlayNetworkHandler networkHandler,
                                   String reason) {
        if (current == null || networkHandler == null || current.statsProbePending) {
            return;
        }
        current.statsProbeDeferred = false;
        current.statsProbeSentForBatch = true;
        current.statsProbePending = true;
        networkHandler.sendPacket(new ClientStatusC2SPacket(
                ClientStatusC2SPacket.Mode.REQUEST_STATS));
        current.statsProbesSent++;
        LOGGER.debug("普通配方书ACK发送统计顺序探针：界面={}，批次=#{}，原因={}",
                layout.name(), current.batchId, reason);
    }

    private void handleServerSlotUpdate(int syncId, int revision, int slotId, ItemStack stack) {
        if (!isActive() || !session.awaiting || syncId != session.syncId) {
            return;
        }
        Session current = session;
        current.slotUpdates++;
        recordPacketProgress(current, revision,
                slotId == QuickCraftMouseCraftLayout.OUTPUT_SLOT || layout.isGridSlot(slotId));
        if (slotId == QuickCraftMouseCraftLayout.OUTPUT_SLOT) {
            observeOutput(current, revision, stack, true);
            if (stack != null && !stack.isEmpty()
                    && !isExpectedOutput(stack, current.resultTemplate)
                    && isIntermediateOutput(current, current.handler)
                    && current.handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).inventory
                    instanceof CraftingResultInventory resultInventory) {
                resultInventory.setStack(QuickCraftMouseCraftLayout.OUTPUT_SLOT, ItemStack.EMPTY);
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
        if (!isActive() || !session.awaiting || syncId != session.syncId) {
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
                : contents.get(QuickCraftMouseCraftLayout.OUTPUT_SLOT);
        boolean packetRecipeMatches = recipeMatches(current, contents);
        boolean cursorEmpty = cursorStack.isEmpty();
        if (current.lastAuthoritativeMaterialRevision == Integer.MIN_VALUE
                || revision == current.lastAuthoritativeMaterialRevision
                || isRevisionAfter(
                revision, current.lastAuthoritativeMaterialRevision)) {
            current.lastAuthoritativeMaterialRevision = revision;
            MaterialSnapshot materials = materialSnapshotFromContents(current, contents, cursorStack);
            current.lastAuthoritativeMaterialLedger = materials.ledger();
            current.lastAuthoritativeMaterialCounts = materials.counts();
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

        if (!isContextValid(MinecraftClient.getInstance(), current)) {
            cancel("全量回包到达时界面失效");
            return;
        }
        boolean currentBatchAuthoritativeFull = isRevisionAfter(
                revision, current.batchStartRevision)
                && current.lastAuthoritativeMaterialRevision == revision;
        tryConfirmCurrentBatch(revision, now, currentBatchAuthoritativeFull);
    }

    private void handleServerStatistics(ClientPlayNetworkHandler source) {
        if (!isActive() || !session.awaiting || !session.statsProbePending
                || source != session.networkHandler) {
            return;
        }
        Session current = session;
        ScreenHandler handler = current.handler;
        int fullUpdatesAfterDispatch = current.fullUpdates - current.batchFullUpdatesStart;
        long now = System.nanoTime();
        current.statsProbePending = false;
        if (current.statsResponseAtNanos == 0L) {
            current.statsResponseAtNanos = now;
        }
        current.statsProbeResponses++;
        current.lastProgressAtNanos = now;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isContextValid(client, current)) {
            cancel("统计屏障到达时界面失效");
            return;
        }

        ItemStack output = handler
                .getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT)
                .getStack();
        int revision = handler.getRevision();
        boolean outputEmpty = output.isEmpty();
        boolean expectedOutput = isExpectedOutput(output, current.resultTemplate);
        boolean cursorEmpty = handler.getCursorStack().isEmpty();
        boolean currentRecipeMatches = recipeMatches(current);
        // 统计响应已证明服务器处理完本批更早的点击；同连接上更早的槽位纠正也已应用。
        current.lastAuthoritativeMaterialRevision = revision;
        MaterialSnapshot materials = materialSnapshotFromHandler(current, handler);
        current.lastAuthoritativeMaterialLedger = materials.ledger();
        current.lastAuthoritativeMaterialCounts = materials.counts();
        if (!outputEmpty && !expectedOutput
                && currentRecipeMatches && !current.invalidAuthoritativeOutput) {
            current.invalidAuthoritativeOutput = true;
            current.authoritativeWrongOutputPackets++;
            current.authoritativeWrongOutputPacketsTotal++;
            LOGGER.warn("普通配方书ACK统计顺序屏障确认错误终态产物：界面={}，批次=#{}，"
                            + "配方={}，期望={}，实际={}，revision={}，格子={}",
                    layout.name(), current.batchId, current.recipeId,
                    describeStack(current.resultTemplate), describeStack(output),
                    revision, describeGrid(handler));
        }
        boolean decreased = ingredientsDecreased(
                current.batchGridCounts, snapshotGridCounts(handler));
        boolean terminalGridCompatible = isPatternOrRemainderGridCompatible(
                current, handler);
        if (shouldInfoLogBatch(current.batchId) || current.batchPath == BatchPath.OUTPUT_DRAIN) {
            LOGGER.info("手动补货ACK统计屏障：界面={}，批次=#{}，等待={} us，全量包={}，"
                            + "revision={}->{}, 输出={}，空边界={}，正确产物={}，终态全量={}，"
                            + "配方匹配={}，返还物终态={}，光标空={}，失败包={}，原料减少={}，"
                            + "点击前格子={}，当前格子={}，批次开始正确产物={}，"
                            + "权威错误={}，中间错误包={}，权威材料={}，按住={}",
                    layout.name(), current.batchId,
                    (now - current.batchSentAtNanos) / 1_000L,
                    fullUpdatesAfterDispatch, current.batchStartRevision,
                    revision, describeStack(output),
                    current.outputAck.sawAuthoritativeEmpty(),
                    current.outputAck.sawExpectedAfterEmpty(),
                    current.outputAck.sawRecipeReadyFull(),
                    currentRecipeMatches, terminalGridCompatible,
                    cursorEmpty, current.craftFailed, decreased,
                    describeGridCounts(current.batchGridCounts), describeGrid(handler),
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
                    describeGrid(handler), decreased);
            finishStopped("权威错误产物");
            return;
        }

        String materialViolation = findMaterialLedgerViolation(current);
        if (materialViolation != null) {
            LOGGER.warn("普通配方书ACK权威材料变化超出客户端计划，记录后继续：界面={}，批次=#{}，配方={}，"
                            + "输出搬运={}，输出槽点击={}，计划轮数={}，点击前实际材料={}，最终权威材料={}，"
                            + "本批净变化={}，详情={}，格子={}；"
                            + "地面拾取可能在点击到达服务端前改变原料数量，不再据此终止",
                    layout.name(), current.batchId, current.recipeId,
                    current.batchOutputOperations,
                    current.batchOutputSlotClicks,
                    current.batchPlannedCrafts,
                    describeMaterialCounts(current.batchMaterialCountsBeforeClicks),
                    describeMaterialCounts(current.lastAuthoritativeMaterialCounts),
                    describeMaterialCountDelta(current, current.batchMaterialCountsBeforeClicks,
                            current.lastAuthoritativeMaterialCounts),
                    materialViolation, describeGrid(handler));
            current.materialLedgerDriftLogged = true;
        }

        if (tryConfirmCurrentBatch(revision, now, false)) {
            return;
        }

        if (!cursorEmpty) {
            LOGGER.warn("手动补货ACK统计屏障时光标非空：界面={}，批次=#{}，光标={}",
                    layout.name(), current.batchId, describeStack(handler.getCursorStack()));
            finishStopped("权威终态光标非空");
            return;
        }

        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finishAfterInputRelease(current, "统计屏障后输入释放");
            return;
        }

        boolean intermediateOutput = !outputEmpty && !expectedOutput
                && isIntermediateOutput(current, handler);
        BatchPath path = current.batchPath;
        boolean authoritativeExpectedOutput = hasAuthoritativeExpectedOutput(current);
        if (path == BatchPath.OUTPUT_DRAIN) {
            if (canContinueOutputDrainWithIntermediate(
                    cursorEmpty, current.outputExpectedAtDispatch, decreased, intermediateOutput)) {
                current.drainRetries = 0;
                acknowledgeAndPump(client, current, now,
                        "批量取产物后出现兼容中间产物，继续补货");
                return;
            }
            if (shouldContinueOutputDrain(
                    cursorEmpty, outputEmpty, expectedOutput,
                    authoritativeExpectedOutput || current.outputExpectedAtDispatch,
                    decreased, current.drainRetries, MAX_DRAIN_RETRIES)) {
                String drainOutcome;
                if (outputEmpty) {
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
                            describeGridCounts(current.batchGridCounts), describeGrid(handler),
                            current.inputHeld.getAsBoolean());
                }
                acknowledgeAndPump(client, current, now, drainOutcome);
                return;
            }
            if (outputEmpty && !authoritativeExpectedOutput
                    && !current.outputExpectedAtDispatch) {
                LOGGER.warn("普通配方书ACK拒绝空输出确认：界面={}，批次=#{}，配方={}，"
                                + "原料减少={}，全量包={}，批次开始正确产物=false，正确产物回包=false，格子={}；"
                                + "这类状态只能停止，不能继续补货或发下一批",
                        layout.name(), current.batchId, current.recipeId, decreased,
                        fullUpdatesAfterDispatch, describeGrid(handler));
            }
        }

        if (includesManualRefill(path)) {
            if (currentRecipeMatches && expectedOutput
                    && authoritativeExpectedOutput) {
                current.refillRetries = 0;
                acknowledgeAndPump(client, current, now,
                        "手动补货后配方仍可继续");
                return;
            }
            if (!isPatternGridCompatible(handler, current.pattern)) {
                LOGGER.warn("手动补货ACK权威配方错位：界面={}，批次=#{}，格子={}；不是缺料，不进入拾取等待",
                        layout.name(), current.batchId, describeGrid(handler));
                finishStopped("权威配方错位");
                return;
            }
            if (outputEmpty && tryRetryManualRefill(current, now, "统计屏障后补货未齐")) {
                return;
            }
            boolean patternComplete = recipeMatches(current);
            boolean hasIngredients = hasIngredients(handler);
            boolean missingTerminal = (outputEmpty && !patternComplete && !hasIngredients)
                    || isMissingLockedRecipeTerminal(
                    patternComplete, hasIngredients, intermediateOutput);
            if (missingTerminal) {
                handleMissingIngredients(client, current, now, outputEmpty);
                return;
            }
            if (outputEmpty) {
                acknowledgeAndPump(client, current, now,
                        "补货后仍无产物，检查脚下拾取");
                return;
            }
        } else if (path == BatchPath.MANUAL_COMBINED
                && canConfirmCombinedBatch(
                true,
                cursorEmpty,
                outputEmpty || expectedOutput || intermediateOutput,
                (current.batchSawExpectedOutput || current.outputExpectedAtDispatch)
                        && !current.invalidAuthoritativeOutput,
                terminalGridCompatible)) {
            current.refillRetries = 0;
            current.drainRetries = 0;
            acknowledgeAndPump(client, current, now,
                    "同批补料并整批取产物已确认");
            return;
        }

        LOGGER.warn("手动补货ACK统计屏障终态不可续用：界面={}，批次=#{}，路径={}，输出={}，"
                        + "权威正确产物={}，原料减少={}，点击前格子={}，当前格子={}，按住={}",
                layout.name(), current.batchId, path.description, describeStack(output),
                authoritativeExpectedOutput, decreased,
                describeGridCounts(current.batchGridCounts), describeGrid(handler),
                current.inputHeld.getAsBoolean());
        finish(outputEmpty
                        ? Text.translatable("quickcraft.message.crafting.no_ingredients")
                        : Text.translatable("quickcraft.message.crafting.stopped"),
                "统计屏障终态不可续用：" + path.description);
    }

    private void handleMissingIngredients(MinecraftClient client,
                                          Session current,
                                          long now,
                                          boolean outputEmpty) {
        if (tryStartNoIngredientRescue(client, current, now)
                || tryStartPickupGrace(current, now)) {
            return;
        }
        boolean pickupAttempted = current.noIngredientRescueAttempted
                || current.pickupWaitAttempted;
        finishNoIngredients(outputEmpty
                ? (pickupAttempted ? "拾取等待后仍无可补原料" : "首次检查无可补原料")
                : (pickupAttempted
                ? "中间产物终态拾取等待后仍无可补原料"
                : "中间产物终态无可补原料"));
    }

    private boolean tryStartNoIngredientRescue(MinecraftClient client,
                                               Session current,
                                               long now) {
        if (current.noIngredientRescueAttempted
                || QuickCraftMouseCraftInventory.unlockedEmptySlots(
                current.handler, layout) > 0) {
            return false;
        }
        current.noIngredientRescueAttempted = true;
        current.outputSprayMode = true;
        current.outputFillSlotsRemaining = 0;
        int dropped = dropInventoryCraftResults(
                client, current, "首次缺料时释放满包产物");
        current.sprayInventoryCleared = true;
        if (dropped <= 0) {
            return false;
        }

        current.pickupWaitBatches++;
        current.pickupWaitForFullInventory = true;
        current.pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        LOGGER.info("手动补货ACK首次缺料释放背包后等待拾取：界面={}，批次=#{}，"
                        + "丢出产物槽={}，等待={} Tick，按住={}",
                layout.name(), current.batchId, dropped, MAX_PICKUP_WAIT_TICKS,
                current.inputHeld.getAsBoolean());
        acknowledgeBatch(current, current.handler.getRevision(), now,
                "首次缺料已释放满包产物，等待拾取");
        return true;
    }

    private boolean tryStartPickupGrace(Session current, long now) {
        if (current.pickupWaitAttempted
                || QuickCraftMouseCraftInventory.unlockedEmptySlots(current.handler, layout) <= 0) {
            return false;
        }
        current.pickupWaitAttempted = true;
        current.pickupWaitForFullInventory = false;
        current.pickupWaitBatches++;
        current.pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        LOGGER.info("手动补货ACK非满包缺料，等待自然拾取后重扫：界面={}，批次=#{}，空格={}，"
                        + "最多等待={} Tick，按住={}",
                layout.name(), current.batchId, QuickCraftMouseCraftInventory.unlockedEmptySlots(
                        current.handler, layout), MAX_PICKUP_WAIT_TICKS,
                current.inputHeld.getAsBoolean());
        acknowledgeBatch(current, current.handler.getRevision(), now,
                "非满包缺料等待自然拾取");
        return true;
    }

    private boolean tryConfirmCurrentBatch(int acknowledgementRevision,
                                           long now,
                                           boolean authoritativeFullState) {
        if (!isActive() || !session.awaiting) {
            return false;
        }
        Session current = session;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isContextValid(client, current)) {
            cancel("ACK终态到达时界面失效");
            return false;
        }
        ItemStack currentOutput = current.handler
                .getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT)
                .getStack();
        boolean patternComplete = QuickCraftMouseCraftInventory.isPatternComplete(
                current.handler, layout, current.pattern);
        boolean currentRecipeMatches = recipeMatches(current);
        boolean cursorEmpty = current.handler.getCursorStack().isEmpty();
        boolean expectedOutput = isExpectedOutput(currentOutput, current.resultTemplate);
        boolean authoritativeExpectedOutput = hasAuthoritativeExpectedOutput(current);
        int[] currentGridCounts = snapshotGridCounts(current.handler);
        boolean decreased = ingredientsDecreased(
                current.batchGridCounts, currentGridCounts);
        boolean outputIngredientsDecreased = ingredientsDecreased(
                current.batchCraftGridCounts, currentGridCounts);
        boolean terminalGridCompatible = isPatternOrRemainderGridCompatible(
                current, current.handler);
        boolean statsReceived = current.statsProbeSentForBatch && !current.statsProbePending;
        boolean fastFullState = authoritativeFullState && canUseAuthoritativeFullAck(
                current.batchPath,
                current.batchOutputOperations > 0,
                current.outputExpectedAtDispatch,
                cursorEmpty,
                currentOutput.isEmpty(),
                outputIngredientsDecreased,
                current.outputAck.sawAuthoritativeEmpty(),
                terminalGridCompatible);
        boolean authoritativeStateReceived = fastFullState || statsReceived;
        boolean pathReady;
        if (current.batchPath == BatchPath.OUTPUT_DRAIN) {
            pathReady = canConfirmOutputDrain(
                    authoritativeStateReceived, cursorEmpty, currentOutput.isEmpty(), decreased, expectedOutput,
                    authoritativeExpectedOutput || current.outputExpectedAtDispatch);
        } else if (current.batchPath == BatchPath.MANUAL_COMBINED) {
            boolean compatibleTerminalOutput = currentOutput.isEmpty()
                    || expectedOutput
                    || isIntermediateOutput(current, current.handler);
            pathReady = canConfirmCombinedBatch(
                    authoritativeStateReceived, cursorEmpty, compatibleTerminalOutput,
                    (current.batchSawExpectedOutput || current.outputExpectedAtDispatch)
                            && !current.invalidAuthoritativeOutput,
                    terminalGridCompatible);
        } else {
            pathReady = canConfirmManualBatch(
                    authoritativeStateReceived, cursorEmpty, currentRecipeMatches || patternComplete,
                    expectedOutput && (authoritativeExpectedOutput || statsReceived));
        }
        boolean terminalPrefix = canFinishBatch(
                fastFullState,
                current.statsProbeSentForBatch,
                current.statsProbePending,
                pathReady && !current.invalidAuthoritativeOutput);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("手动补货ACK确认检查：界面={}，批次=#{}，路径={}，全量包={}，光标空={}，"
                            + "配方匹配={}，格子齐={}，原料减少={}，产物={}，结果={}，"
                            + "权威正确产物={}，批次开始正确产物={}，全量终态={}，统计已发/待回={}/{}, 权威错误={}，"
                            + "点击前格子={}，当前格子={}，按住={}",
                    layout.name(), current.batchId, current.batchPath.description,
                    current.fullUpdates - current.batchFullUpdatesStart,
                    cursorEmpty, currentRecipeMatches, patternComplete, decreased,
                    describeStack(currentOutput), terminalPrefix, authoritativeExpectedOutput,
                    current.outputExpectedAtDispatch, fastFullState,
                    current.statsProbeSentForBatch, current.statsProbePending,
                    current.invalidAuthoritativeOutput,
                    describeGridCounts(current.batchGridCounts), describeGrid(current.handler),
                    current.inputHeld.getAsBoolean());
        }
        if (!terminalPrefix) {
            return false;
        }

        String materialViolation = findMaterialLedgerViolation(current);
        if (materialViolation != null && !current.materialLedgerDriftLogged) {
            LOGGER.warn("普通配方书ACK快速全量确认材料变化超出客户端计划：界面={}，批次={}，"
                            + "详情={}；记录后继续，等待权威光标、产物和配方终态保证安全",
                    layout.name(), current.batchId, materialViolation);
            current.materialLedgerDriftLogged = true;
        }
        if (expectedOutput) {
            current.refillRetries = 0;
        }
        if (current.batchPath == BatchPath.OUTPUT_DRAIN) {
            current.drainRetries = 0;
        }
        acknowledgeBatch(current, acknowledgementRevision, now,
                (fastFullState ? "权威全量终态：" : "统计顺序探针：")
                        + (current.batchPath == BatchPath.OUTPUT_DRAIN && currentOutput.isEmpty()
                        ? "输出已排空，继续手动补货"
                        : "终态确认"));

        if (!isContextValid(client, current)) {
            cancel("ACK后界面失效");
        } else if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            finishAfterInputRelease(current, "ACK后停止");
        } else {
            pump(client);
        }
        return true;
    }

    private void acknowledgeAndPump(MinecraftClient client,
                                    Session current,
                                    long now,
                                    String outcome) {
        acknowledgeBatch(current, current.handler.getRevision(), now, outcome);
        pump(client);
    }

    private void acknowledgeBatch(Session current,
                                  int acknowledgementRevision,
                                  long now,
                                  String outcome) {
        long latencyNanos = Math.max(0L, now - current.batchSentAtNanos);
        boolean probeUsed = current.statsProbeSentForBatch;
        current.awaiting = false;
        current.statsProbeDeferred = false;
        current.confirmedBatches++;
        if (probeUsed) {
            current.statsProbeFallbackAcks++;
        } else {
            current.authoritativeFullAcks++;
            current.statsProbesOmitted++;
        }
        int batchFullUpdates = Math.max(0,
                current.fullUpdates - current.batchFullUpdatesStart);
        current.maxFullUpdatesPerBatch = Math.max(
                current.maxFullUpdatesPerBatch, batchFullUpdates);
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
                            + "终态校验={} us，槽位点击={}，全量回包={}，revision={}->{}, Tick预算={}/{}, 结果={}",
                    layout.name(), current.batchId, current.batchPath.description,
                    current.recipeId, latencyNanos / 1_000L, fullUpdateMicros,
                    statsResponseMicros, validationMicros, current.batchClientSlotClicks,
                    batchFullUpdates, current.batchStartRevision,
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

    void recordRemainderThrow() {
        if (isActive() && dispatchingBatch) {
            session.remainderThrows++;
        }
    }

    private boolean recipeMatches(Session current) {
        return QuickCraftMouseCraftInventory.isPatternComplete(
                current.handler, layout, current.pattern);
    }

    private boolean hasIngredients(ScreenHandler handler) {
        return ingredientAvailabilityHandler != null
                && ingredientAvailabilityHandler.hasIngredients(handler);
    }

    private boolean isIntermediateOutput(Session current, ScreenHandler handler) {
        if (current == null || handler == null) {
            return false;
        }
        return !QuickCraftMouseCraftInventory.isPatternComplete(handler, layout, current.pattern)
                && isPatternGridCompatible(handler, current.pattern);
    }

    private boolean isPatternGridCompatible(ScreenHandler handler, List<ItemStack> pattern) {
        if (handler == null || pattern == null || pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack expected = pattern.get(i);
            ItemStack actual = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (actual.isEmpty()) {
                continue;
            }
            if (expected.isEmpty() || !ItemStack.areItemsAndComponentsEqual(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPatternOrRemainderGridCompatible(Session current, ScreenHandler handler) {
        if (current == null || handler == null || current.pattern == null
                || current.pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack expected = current.pattern.get(i);
            ItemStack actual = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (actual.isEmpty()
                    || (!expected.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(expected, actual))) {
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

    private boolean hasAuthoritativeExpectedOutput(Session current) {
        return current != null
                && (current.outputAck.sawExpectedAfterEmpty()
                || current.outputAck.sawRecipeReadyFull());
    }

    private boolean recipeMatches(Session current, List<ItemStack> contents) {
        return isPatternComplete(contents, layout, current.pattern);
    }

    static boolean isPatternComplete(List<ItemStack> contents,
                                     QuickCraftMouseCraftLayout.Layout layout,
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
                    || !ItemStack.areItemsAndComponentsEqual(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private void finish(Text message, String reason) {
        if (!isActive()) {
            return;
        }
        Session completed = session;
        boolean held = completed.inputHeld.getAsBoolean();
        MinecraftClient client = MinecraftClient.getInstance();
        if (isContextValid(client, completed)) {
            parkCursor(client, completed.handler, "结束");
        }
        boolean cursorEmpty = completed.handler.getCursorStack().isEmpty();
        boolean needsDrain = needsCanceledBatchDrain(
                completed.awaiting, cursorEmpty,
                completed.statsProbePending);
        boolean allowTailDrop = canDropTail(completed.awaiting, cursorEmpty);
        if (needsDrain) {
            beginCanceledBatchDrain(completed, layout);
        }
        clearSession();
        int stopTailSlots = completed.finishHandler.finish(message, allowTailDrop);
        logSummary(completed, reason, stopTailSlots, held);
    }

    private void finishAfterInputRelease(Session current, String reason) {
        finish(current.stopMessage != null
                ? current.stopMessage
                : Text.translatable("quickcraft.message.crafting.stopped"), reason);
    }

    private void finishNoIngredients(String reason) {
        finish(Text.translatable("quickcraft.message.crafting.no_ingredients"), reason);
    }

    private void finishStopped(String reason) {
        finish(Text.translatable("quickcraft.message.crafting.stopped"), reason);
    }

    private void handoffToLegacy(MinecraftClient client, String reason) {
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
                            + "槽位点击={}，输出槽点击={}，revision={}，发送耗时={} us",
                    layout.name(), current.batchId, current.recipeId, action, outputMoveOperations,
                    current.batchClientSlotClicks, current.batchOutputSlotClicks,
                    current.batchStartRevision, dispatchMicros);
        }
        if (shouldInfoLogBatch(current.batchId)) {
            LOGGER.info("手动补货ACK批次发送：界面={}，批次=#{}，动作={}，输出搬运={}，"
                            + "槽位点击={}，revision={}，耗时={} us",
                    layout.name(), current.batchId, action, outputMoveOperations,
                    current.batchClientSlotClicks, current.batchStartRevision, dispatchMicros);
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
                        + "请求={}，强制全量请求={}，全量快速确认={}，探针兜底确认={}，省略探针={}，"
                        + "统计ACK={}/{}，计划合成轮数={}，满64批={}，尾料批={}，"
                        + "槽位点击={}，输出搬运={}，输出槽点击={}，"
                        + "输出槽丢出={}，背包产物丢出={}，返还物丢出={}，丢原料={}，快照补料={}/{}，"
                        + "回退旧流程={}，槽位/全量/失败包={}/{}/{}，超时={}，"
                        + "单批最大点击/全量={}/{}, "
                        + "ACK平均/最小/最大={}/{}/{} us，ACK等待={} ms({}%)，本地发送={} us，"
                        + "确认批次/秒={}，首个全量平均={} us，统计平均={} us，"
                        + "补货重试={}，取产物重试={}，等待拾取={}，停止尾货槽={}，按住={}，结束原因={}",
                layout.name(), completed.recipeId, elapsedMillis, completed.confirmedBatches,
                completed.batches, completed.recipeRequests, completed.barriersSent,
                completed.authoritativeFullAcks, completed.statsProbeFallbackAcks,
                completed.statsProbesOmitted,
                completed.statsProbeResponses, completed.statsProbesSent,
                completed.plannedCrafts, completed.fullCraftBatches, completed.tailCraftBatches,
                completed.clientSlotClicks,
                completed.quickMoves, completed.outputSlotClicks,
                completed.outputThrows, completed.inventoryResultDrops,
                completed.remainderThrows, completed.ingredientDrops,
                completed.snapshotRefillSuccesses, completed.snapshotRefillBatches,
                completed.legacyHandoffs, completed.slotUpdates, completed.fullUpdates,
                completed.failurePackets, completed.timeouts,
                completed.maxClientSlotClicksPerBatch, completed.maxFullUpdatesPerBatch,
                averageAckMicros,
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

    private boolean isContextValid(MinecraftClient client, Session current) {
        return client != null && client.player != null && client.world != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null
                && client.player.currentScreenHandler == current.handler
                && current.handler.syncId == current.syncId
                && QuickCraftMouseCraftLayout.fromScreen(client.currentScreen) == layout;
    }

    private boolean parkCursor(MinecraftClient client, ScreenHandler handler, String phase) {
        if (handler == null || handler.getCursorStack().isEmpty()) {
            return true;
        }
        if (client == null || client.player == null || client.interactionManager == null) {
            return false;
        }
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        ItemStack before = handler.getCursorStack().copy();
        boolean parked = QuickCraftMouseCraftInventory.returnCursorToUnlockedInventory(
                client, handler, layout);
        if (!parked && session != null) {
            int patternSlot = QuickCraftMouseCraftInventory.firstPatternSlotWithRoom(
                    handler, layout, session.pattern, handler.getCursorStack());
            if (patternSlot >= 0) {
                interactionManager.clickSlot(
                        handler.syncId, patternSlot, 0, SlotActionType.PICKUP, client.player);
            }
            parked = handler.getCursorStack().isEmpty();
        }
        if (!parked && session != null
                && isSameItem(handler.getCursorStack(), session.resultTemplate)) {
            interactionManager.clickSlot(
                    handler.syncId, -999, 0, SlotActionType.PICKUP, client.player);
            parked = handler.getCursorStack().isEmpty();
        }
        if (!parked && session != null && !session.resultTemplate.isEmpty()) {
            int productSlot = QuickCraftMouseCraftInventory.findMatchingUnlockedSlot(
                    handler, layout, session.resultTemplate);
            if (productSlot >= 0) {
                interactionManager.clickSlot(
                        handler.syncId, productSlot, 0, SlotActionType.PICKUP, client.player);
                if (isSameItem(handler.getCursorStack(), session.resultTemplate)) {
                    interactionManager.clickSlot(
                            handler.syncId, -999, 0, SlotActionType.PICKUP, client.player);
                }
            }
            parked = handler.getCursorStack().isEmpty();
        }
        LOGGER.info("手动补货ACK放回光标：界面={}，阶段={}，原光标={}，结果={}，剩余={}",
                layout.name(), phase, describeStack(before), parked,
                describeStack(handler.getCursorStack()));
        return parked;
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
        if (!hasIngredients(current.handler)) {
            LOGGER.info("手动补货ACK无原料可重试：界面={}，批次=#{}，格子={}",
                    layout.name(), current.batchId, describeGrid(current.handler));
            return false;
        }
        current.refillRetries++;
        current.refillRetryBatches++;
        LOGGER.info("手动补货ACK重试补货：界面={}，批次=#{}，重试={}/{}，格子={}",
                layout.name(), current.batchId, current.refillRetries, MAX_REFILL_RETRIES,
                describeGrid(current.handler));
        acknowledgeBatch(current, current.handler.getRevision(), now, reason);
        pump(MinecraftClient.getInstance());
        return true;
    }

    private int[] snapshotGridCounts(ScreenHandler handler) {
        int[] counts = new int[layout.gridSize()];
        if (handler == null) {
            return counts;
        }
        for (int i = 0; i < counts.length; i++) {
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getStack();
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

    private String describeGrid(ScreenHandler handler) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < layout.gridSize(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            if (handler == null) {
                builder.append('-');
                continue;
            }
            ItemStack stack = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (stack == null || stack.isEmpty()) {
                builder.append('-');
            } else {
                builder.append(stack.getName().getString()).append('x').append(stack.getCount());
            }
        }
        return builder.toString();
    }

    private String describeGridContents(List<ItemStack> contents) {
        if (contents == null || contents.size() < QuickCraftMouseCraftLayout.OUTPUT_SLOT
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

    private MaterialSnapshot materialSnapshotFromContents(Session current,
                                                          List<ItemStack> contents,
                                                          ItemStack cursorStack) {
        if (current == null || contents == null) {
            return MaterialSnapshot.UNAVAILABLE;
        }
        List<ItemStack> templates = current.materialTemplates;
        int[] gridCounts = new int[templates.size()];
        int[] inventoryCounts = new int[templates.size()];
        int[] cursorCounts = new int[templates.size()];
        for (int gridIndex = 0; gridIndex < layout.gridSize(); gridIndex++) {
            int slotId = layout.gridSlotId(gridIndex);
            if (slotId < 0 || slotId >= contents.size()) {
                return MaterialSnapshot.UNAVAILABLE;
            }
            addMaterialCount(gridCounts, templates, contents.get(slotId));
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (slotId < 0 || slotId >= contents.size()) {
                return MaterialSnapshot.UNAVAILABLE;
            }
            addMaterialCount(inventoryCounts, templates, contents.get(slotId));
        }
        addMaterialCount(cursorCounts, templates, cursorStack);
        return materialSnapshot(templates, gridCounts, inventoryCounts, cursorCounts);
    }

    private MaterialSnapshot materialSnapshotFromHandler(Session current, ScreenHandler handler) {
        if (current == null || handler == null) {
            return MaterialSnapshot.UNAVAILABLE;
        }
        List<ItemStack> templates = current.materialTemplates;
        int[] gridCounts = new int[templates.size()];
        int[] inventoryCounts = new int[templates.size()];
        int[] cursorCounts = new int[templates.size()];
        for (int gridIndex = 0; gridIndex < layout.gridSize(); gridIndex++) {
            addMaterialCount(gridCounts, templates,
                    handler.getSlot(layout.gridSlotId(gridIndex)).getStack());
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int slotId = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (slotId >= 0) {
                addMaterialCount(inventoryCounts, templates, handler.getSlot(slotId).getStack());
            }
        }
        addMaterialCount(cursorCounts, templates, handler.getCursorStack());
        return materialSnapshot(templates, gridCounts, inventoryCounts, cursorCounts);
    }

    private static MaterialSnapshot materialSnapshot(List<ItemStack> templates,
                                                      int[] gridCounts,
                                                      int[] inventoryCounts,
                                                      int[] cursorCounts) {
        int[] totals = new int[templates.size()];
        StringBuilder ledger = new StringBuilder();
        for (int i = 0; i < templates.size(); i++) {
            totals[i] = gridCounts[i] + inventoryCounts[i] + cursorCounts[i];
            if (i > 0) {
                ledger.append(';');
            }
            ledger.append(templates.get(i).getName().getString())
                    .append(" grid=").append(gridCounts[i])
                    .append(" inventory=").append(inventoryCounts[i])
                    .append(" cursor=").append(cursorCounts[i]);
        }
        return new MaterialSnapshot(
                totals,
                ledger.length() == 0 ? "无配方原料" : ledger.toString());
    }

    private void addMaterialCount(int[] counts,
                                  List<ItemStack> templates,
                                  ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < templates.size(); i++) {
            if (ItemStack.areItemsAndComponentsEqual(stack, templates.get(i))) {
                counts[i] += stack.getCount();
                return;
            }
        }
    }

    private List<ItemStack> uniquePatternIngredients(Session current) {
        return current == null ? List.of() : current.materialTemplates;
    }

    private static List<ItemStack> uniquePatternIngredients(List<ItemStack> pattern) {
        List<ItemStack> templates = new ArrayList<>();
        for (ItemStack patternStack : pattern) {
            if (patternStack.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (ItemStack template : templates) {
                if (ItemStack.areItemsAndComponentsEqual(template, patternStack)) {
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
                || current.batchMaterialCountsBeforeClicks == null
                || current.lastAuthoritativeMaterialCounts == null
                || current.batchMaterialCountsBeforeClicks.length
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
            int before = current.batchMaterialCountsBeforeClicks[i];
            int after = current.lastAuthoritativeMaterialCounts[i];
            int consumed = before - after;
            if (consumed <= 0) {
                continue;
            }
            int occurrences = 0;
            for (ItemStack patternStack : current.pattern) {
                if (!patternStack.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(patternStack, templates.get(i))) {
                    occurrences++;
                }
            }
            int safeCrafts = maximumCrafts;
            long expectedMaximum = (long) safeCrafts * occurrences;
            if (materialConsumptionExceedsBatchLimit(
                    before, after, occurrences, safeCrafts)) {
                return templates.get(i).getName().getString()
                        + " 消耗=" + consumed
                        + "，本批最多应消耗=" + expectedMaximum
                        + "（合成格最多轮数=" + safeCrafts
                        + "，输出搬运=" + current.batchOutputOperations
                        + "，输出槽点击=" + current.batchOutputSlotClicks + "）";
            }
        }
        return null;
    }

    private static int[] cloneMaterialCounts(int[] counts) {
        return counts == null ? null : counts.clone();
    }

    private String describeMaterialCountDelta(Session current, int[] before, int[] after) {
        if (current == null || before == null || after == null
                || before.length != after.length
                || before.length != current.materialTemplates.size()) {
            return "不可用";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < before.length; i++) {
            if (i > 0) {
                builder.append(';');
            }
            int delta = after[i] - before[i];
            builder.append(current.materialTemplates.get(i).getName().getString())
                    .append(delta >= 0 ? "+" : "")
                    .append(delta);
        }
        return builder.length() == 0 ? "无配方原料" : builder.toString();
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
            builder.append(templates.get(i).getName().getString())
                    .append('=').append(counts[i]);
        }
        return builder.length() == 0 ? "无配方原料" : builder.toString();
    }

    static boolean includesManualRefill(BatchPath path) {
        return path == BatchPath.MANUAL_REFILL || path == BatchPath.MANUAL_COMBINED;
    }

    static boolean canUseAuthoritativeFullAck(BatchPath path,
                                              boolean outputActionSent,
                                              boolean expectedOutputAtDispatch,
                                              boolean cursorEmpty,
                                              boolean outputEmpty,
                                              boolean outputIngredientsDecreased,
                                              boolean authoritativeEmptyObserved,
                                              boolean patternOrRemainderCompatible) {
        return path == BatchPath.OUTPUT_DRAIN
                && outputActionSent
                && expectedOutputAtDispatch
                && cursorEmpty
                && outputEmpty
                && outputIngredientsDecreased
                && authoritativeEmptyObserved
                && patternOrRemainderCompatible;
    }

    static boolean canReleaseCanceledDrain(OutputAckSequence sequence,
                                           boolean failed,
                                           int revision) {
        return failed
                ? sequence.canFinishFailure(revision)
                : sequence.sawRecipeReadyFull() || sequence.sawAuthoritativeEmpty();
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
                && ItemStack.areItemsAndComponentsEqual(stack, template);
    }

    private static boolean isSameItem(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && !template.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(stack, template);
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
                : stack.getName().getString() + "x" + stack.getCount();
    }

    @FunctionalInterface
    public interface LegacyFallbackHandler {
        void fallback(MinecraftClient client,
                      ScreenHandler handler,
                      NetworkRecipeId recipeId);
    }

    @FunctionalInterface
    public interface SnapshotRefillHandler {
        boolean refill(MinecraftClient client,
                       ScreenHandler handler,
                       int maxSourceStacksPerIngredient);
    }

    @FunctionalInterface
    public interface IngredientAvailabilityHandler {
        boolean hasIngredients(ScreenHandler handler);
    }

    @FunctionalInterface
    public interface FinishHandler {
        int finish(Text message, boolean allowTailDrop);
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

    private record MaterialSnapshot(int[] counts, String ledger) {
        private static final MaterialSnapshot UNAVAILABLE =
                new MaterialSnapshot(null, "不可用");
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
                                          SlotActionType actionType,
                                          PlayerEntity player) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || player == null
                || player.currentScreenHandler == null
                || player.currentScreenHandler.syncId != syncId
                || active.session == null) {
            return;
        }
        Session current = active.session;
        ScreenHandler handler = player.currentScreenHandler;
        if (active.dispatchingBatch
                && slotId == QuickCraftMouseCraftLayout.OUTPUT_SLOT
                && (actionType == SlotActionType.QUICK_MOVE
                || actionType == SlotActionType.PICKUP
                || actionType == SlotActionType.THROW)) {
            current.outputSlotClicks++;
        }
        if (active.dispatchingBatch) {
            current.clientSlotClicks++;
        }
        ItemStack target = slotId >= 0 && slotId < handler.slots.size()
                ? handler.getSlot(slotId).getStack().copy()
                : ItemStack.EMPTY;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("普通配方书客户端槽位操作开始：界面={}，批次=#{}，awaiting={}，slot={}，"
                            + "action={}，button={}，目标={}，光标={}，revision={}，格子={}，材料={}",
                    active.layout.name(), active.dispatchingBatch
                            ? active.dispatchingBatchLogId : current.batchId,
                    current.awaiting, slotId, actionType,
                    button, describeStack(target), describeStack(handler.getCursorStack()),
                    handler.getRevision(), active.describeGrid(handler),
                    active.describeMaterialLedger(current, handler));
        }
    }

    public static boolean shouldBlockIngredientThrow(int syncId,
                                                      int slotId,
                                                      int button,
                                                      SlotActionType actionType,
                                                      PlayerEntity player) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || actionType != SlotActionType.THROW
                || player == null || player.currentScreenHandler == null
                || player.currentScreenHandler.syncId != syncId || active.session == null
                || slotId <= QuickCraftMouseCraftLayout.OUTPUT_SLOT
                || slotId >= player.currentScreenHandler.slots.size()) {
            return false;
        }
        Session current = active.session;
        ScreenHandler handler = player.currentScreenHandler;
        ItemStack target = handler.getSlot(slotId).getStack().copy();
        if (!active.isPatternIngredient(current, target)) {
            return false;
        }
        current.ingredientDrops++;
        LOGGER.error("普通配方书已拦截客户端原料THROW：界面={}，批次=#{}，slot={}，物品={}，"
                        + "button={}，revision={}，光标={}，格子={}，材料={}",
                active.layout.name(), current.batchId, slotId, describeStack(target), button,
                handler.getRevision(), describeStack(handler.getCursorStack()),
                active.describeGrid(handler), active.describeMaterialLedger(current, handler));
        return true;
    }

    public static void onClientClickEnd(int syncId,
                                        int slotId,
                                        int button,
                                        SlotActionType actionType,
                                        PlayerEntity player) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active == null || !active.isActive() || player == null
                || player.currentScreenHandler == null
                || player.currentScreenHandler.syncId != syncId
                || active.session == null || !LOGGER.isDebugEnabled()) {
            return;
        }
        Session current = active.session;
        ScreenHandler handler = player.currentScreenHandler;
        ItemStack target = slotId >= 0 && slotId < handler.slots.size()
                ? handler.getSlot(slotId).getStack().copy()
                : ItemStack.EMPTY;
        LOGGER.debug("普通配方书客户端槽位操作结束：界面={}，批次=#{}，awaiting={}，slot={}，"
                        + "action={}，button={}，结果槽={}，光标={}，revision={}，格子={}，材料={}",
                active.layout.name(), active.dispatchingBatch
                        ? active.dispatchingBatchLogId : current.batchId,
                current.awaiting, slotId, actionType,
                button, describeStack(target), describeStack(handler.getCursorStack()),
                handler.getRevision(), active.describeGrid(handler),
                active.describeMaterialLedger(current, handler));
    }

    private boolean isPatternIngredient(Session current, ItemStack stack) {
        if (current == null || stack == null || stack.isEmpty()) {
            return false;
        }
        for (ItemStack template : current.pattern) {
            if (!template.isEmpty() && ItemStack.areItemsAndComponentsEqual(template, stack)) {
                return true;
            }
        }
        return false;
    }

    private String describeMaterialLedger(Session current, ScreenHandler handler) {
        return materialSnapshotFromHandler(current, handler).ledger();
    }

    public static void onServerSlotUpdate(int syncId,
                                          int revision,
                                          int slotId,
                                          ItemStack stack) {
        if (!CANCELED_BATCH_DRAINS.isEmpty()
                && updateCanceledBatchDrains(syncId, revision, slotId, stack)) {
            return;
        }
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerSlotUpdate(syncId, revision, slotId, stack);
        }
    }

    public static void onServerInventoryUpdate(int syncId,
                                               int revision,
                                               List<ItemStack> contents,
                                               ItemStack cursorStack) {
        if (!CANCELED_BATCH_DRAINS.isEmpty()) {
            if (updateCanceledBatchDrainsFull(
                    syncId, revision, contents, cursorStack)) {
                return;
            }
        }
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerInventoryUpdate(syncId, revision, contents, cursorStack);
        }
    }

    public static void onServerStatistics(ClientPlayNetworkHandler source) {
        QuickCraftMouseCraftAckExecutor active = activeExecutor;
        if (active != null) {
            active.handleServerStatistics(source);
        }
    }

    static boolean isCanceledBatchDraining(ScreenHandler handler) {
        return handler != null && hasCanceledBatchDrain(handler.syncId);
    }

    private static boolean hasCanceledBatchDrain(int syncId) {
        pruneExpiredCanceledBatchDrains();
        return hasCanceledBatchDrainWithoutPruning(syncId);
    }

    private static boolean hasCanceledBatchDrainWithoutPruning(int syncId) {
        for (CanceledBatchDrain drain : CANCELED_BATCH_DRAINS) {
            if (drain.syncId == syncId) {
                return true;
            }
        }
        return false;
    }

    private static void beginCanceledBatchDrain(
            Session canceled,
            QuickCraftMouseCraftLayout.Layout layout) {
        pruneExpiredCanceledBatchDrains();
        CANCELED_BATCH_DRAINS.removeIf(drain -> drain.syncId == canceled.syncId);
        CANCELED_BATCH_DRAINS.add(new CanceledBatchDrain(
                canceled.syncId,
                canceled.recipeId,
                layout,
                canceled.pattern,
                canceled.resultTemplate.copy(),
                canceled.outputAck,
                canceled.craftFailed,
                canceled.networkHandler,
                canceled.player,
                System.nanoTime() + CANCELED_BATCH_DRAIN_TIMEOUT_NANOS
        ));
        LOGGER.debug("手动补货未决批次进入隔离：syncId={}，配方={}，批次=#{}，失败包={}",
                canceled.syncId, canceled.recipeId, canceled.batchId, canceled.craftFailed);
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
            if (drain.syncId != syncId) {
                continue;
            }
            matched = true;
            if (slotId >= 0) {
                drain.observeSlot(revision, slotId, stack);
            }
        }
        return matched;
    }

    private static boolean updateCanceledBatchDrainsFull(int syncId,
                                                         int revision,
                                                         List<ItemStack> contents,
                                                         ItemStack cursorStack) {
        pruneExpiredCanceledBatchDrains();
        boolean matched = false;
        Iterator<CanceledBatchDrain> iterator = CANCELED_BATCH_DRAINS.iterator();
        while (iterator.hasNext()) {
            CanceledBatchDrain drain = iterator.next();
            if (drain.syncId != syncId) {
                continue;
            }
            matched = true;
            drain.observeFull(revision, contents, cursorStack);
            if (drain.isTerminalFull(revision)) {
                LOGGER.debug("手动补货取消批次终态已排空：syncId={}，配方={}，revision={}",
                        drain.syncId, drain.recipeId, revision);
                iterator.remove();
            }
        }
        return matched;
    }

    private static void pruneExpiredCanceledBatchDrains() {
        long now = System.nanoTime();
        MinecraftClient client = MinecraftClient.getInstance();
        Iterator<CanceledBatchDrain> iterator = CANCELED_BATCH_DRAINS.iterator();
        while (iterator.hasNext()) {
            CanceledBatchDrain drain = iterator.next();
            if (client.getNetworkHandler() != drain.networkHandler
                    || client.player != drain.player) {
                LOGGER.debug("手动补货取消批次因连接变化清理：syncId={}，配方={}",
                        drain.syncId, drain.recipeId);
                iterator.remove();
                continue;
            }
            if (now < drain.expiresAtNanos) {
                continue;
            }
            LOGGER.warn("手动补货取消批次隔离超时放行：syncId={}，配方={}",
                    drain.syncId, drain.recipeId);
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
        private final int syncId;
        private final NetworkRecipeId recipeId;
        private final QuickCraftMouseCraftLayout.Layout layout;
        private final List<ItemStack> pattern;
        private final ItemStack resultTemplate;
        private final OutputAckSequence outputAck;
        private final ClientPlayNetworkHandler networkHandler;
        private final ClientPlayerEntity player;
        private final long expiresAtNanos;
        private boolean failed;

        private CanceledBatchDrain(int syncId,
                                   NetworkRecipeId recipeId,
                                   QuickCraftMouseCraftLayout.Layout layout,
                                   List<ItemStack> pattern,
                                   ItemStack resultTemplate,
                                   OutputAckSequence outputAck,
                                   boolean failed,
                                   ClientPlayNetworkHandler networkHandler,
                                   ClientPlayerEntity player,
                                   long expiresAtNanos) {
            this.syncId = syncId;
            this.recipeId = recipeId;
            this.layout = layout;
            this.pattern = copyPattern(pattern);
            this.resultTemplate = resultTemplate;
            this.outputAck = outputAck;
            this.failed = failed;
            this.networkHandler = networkHandler;
            this.player = player;
            this.expiresAtNanos = expiresAtNanos;
            if (failed) {
                this.outputAck.markFailed();
            }
        }

        private void observeSlot(int revision, int slotId, ItemStack stack) {
            if (slotId == QuickCraftMouseCraftLayout.OUTPUT_SLOT) {
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
                    : contents.get(QuickCraftMouseCraftLayout.OUTPUT_SLOT);
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
        private final ScreenHandler handler;
        private final int syncId;
        private final NetworkRecipeId recipeId;
        private final ItemStack resultTemplate;
        private final java.util.List<ItemStack> pattern;
        private final java.util.List<ItemStack> materialTemplates;
        private final BooleanSupplier inputHeld;
        private final FinishHandler finishHandler;
        private final ClientPlayNetworkHandler networkHandler;
        private final ClientPlayerEntity player;
        private final long startedAtNanos;

        private boolean awaiting;
        private int outputThrows;
        private boolean stopRequested;
        private Text stopMessage;
        private final OutputAckSequence outputAck = new OutputAckSequence();
        private boolean craftFailed;
        private boolean unexpectedOutputLogged;
        private boolean invalidAuthoritativeOutput;
        private boolean batchSawExpectedOutput;
        private boolean outputExpectedAtDispatch;
        private boolean outputSprayMode;
        private boolean sprayInventoryCleared;
        private boolean noIngredientRescueAttempted;
        private boolean pickupWaitAttempted;
        private boolean pickupWaitForFullInventory;
        private int outputFillSlotsRemaining;
        private int inventoryResultDrops;
        private int intermediateOutputPackets;
        private int authoritativeWrongOutputPackets;
        private boolean materialLedgerDriftLogged;
        private String lastAuthoritativeMaterialLedger = "未收到";
        private int[] lastAuthoritativeMaterialCounts;
        private int lastAuthoritativeMaterialRevision = Integer.MIN_VALUE;
        private int[] batchMaterialCountsBeforeClicks;
        private int batchOutputOperations;
        private int batchClientSlotClicks;
        private int batchOutputSlotClicks;
        private int clientSlotClicks;
        private int maxClientSlotClicksPerBatch;
        private int maxFullUpdatesPerBatch;
        private int outputSlotClicks;
        private int remainderThrows;
        private long batchId;
        private int batchStartRevision;
        private int batchFullUpdatesStart;
        private int lastProgressRevision;
        private long batchSentAtNanos;
        private long lastProgressAtNanos;
        private boolean statsProbeDeferred;
        private boolean statsProbeSentForBatch;
        private boolean statsProbePending;
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
        private int authoritativeFullAcks;
        private int statsProbeFallbackAcks;
        private int statsProbesOmitted;
        private long plannedCrafts;
        private int fullCraftBatches;
        private int tailCraftBatches;
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
        private int batchPlannedCrafts;

        private Session(ScreenHandler handler,
                        int syncId,
                        NetworkRecipeId recipeId,
                        ItemStack resultTemplate,
                        java.util.List<ItemStack> pattern,
                        BooleanSupplier inputHeld,
                        FinishHandler finishHandler,
                        ClientPlayNetworkHandler networkHandler,
                        ClientPlayerEntity player,
                        long startedAtNanos) {
            this.handler = handler;
            this.syncId = syncId;
            this.recipeId = recipeId;
            this.resultTemplate = resultTemplate;
            this.pattern = pattern;
            this.materialTemplates = uniquePatternIngredients(pattern);
            this.inputHeld = inputHeld;
            this.finishHandler = finishHandler;
            this.networkHandler = networkHandler;
            this.player = player;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
