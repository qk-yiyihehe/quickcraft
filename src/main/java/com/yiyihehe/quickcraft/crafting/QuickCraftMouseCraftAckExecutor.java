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
    private static final long CANCELED_BATCH_DRAIN_TIMEOUT_NANOS = 15_000_000_000L;
    private static final int MAX_REFILL_RETRIES = 3;
    private static final int MAX_DRAIN_RETRIES = 3;
    private static final int MAX_AUTHORITATIVE_CURSOR_RECOVERIES = 1;
    private static final int MAX_PICKUP_WAIT_TICKS = 10;
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
                || world == null || !world.isClient()
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
        } catch (Throwable ignored) {
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
                QuickCraftConfigs.isRetainOneCraftIngredientEnabled()
        );
        session.outputFillSlotsRemaining = QuickCraftMouseCraftInventory.unlockedEmptySlots(
                handler, layout);
        session.outputSprayMode = session.outputFillSlotsRemaining == 0;
        session.lastAuthoritativeMaterialRevision = handler.getRevision();
        activeExecutor = this;
        dispatchedThisTick = 0;
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
        finishNoIngredients("ACK超时");
    }

    private void tickPickupWait(MinecraftClient client, Session current) {
        if (current.stopRequested || !current.inputHeld.getAsBoolean()) {
            current.pickupWaitTicks = 0;
            finishAfterInputRelease(current, "拾取等待期间输入释放");
            return;
        }
        boolean ingredientsAvailable = hasIngredients(current.handler);
        if (!current.retainIngredientSamples) {
            current.pickupWaitTicks--;
            if (!shouldResumePickupWait(ingredientsAvailable, current.pickupWaitTicks)) {
                return;
            }
            current.pickupWaitTicks = 0;
            if (ingredientsAvailable) {
                pump(client);
            } else {
                finishNoIngredients(
                        current.pickupWaitForFullInventory
                                ? "释放满包产物后等待拾取到期"
                                : "非满包自然拾取等待到期后仍无可补原料");
            }
            return;
        }
        current.pickupWaitTicks--;
        if (!shouldResumePickupWait(ingredientsAvailable, current.pickupWaitTicks)) {
            return;
        }
        current.pickupWaitTicks = 0;
        if (ingredientsAvailable) {
            pump(client);
        } else {
            finishNoIngredients(
                    current.pickupWaitForFullInventory
                            ? "满包拾取等待到期后仍无可补原料"
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
        try {
            if (!handler.getCursorStack().isEmpty()) {
                finishStopped("光标非空");
                return false;
            }

            ItemStack output = handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).getStack();
            boolean expectedOutput = isExpectedOutput(output, current.resultTemplate);
            if (!output.isEmpty()
                    && !expectedOutput
                    && !isIntermediateOutput(current, handler)) {
                finishStopped("输出不一致");
                return false;
            }
            current.outputExpectedAtDispatch = expectedOutput;

            int[] gridBeforeClicks = snapshotGridCounts(handler);
            int clientSlotClicksBefore = current.clientSlotClicks;
            int outputSlotClicksBefore = current.outputSlotClicks;
            boolean refilled = false;

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
                moveOrThrowOutput(client, current, handler, interactionManager, output);
                current.outputExpectedAtDispatch = true;
                refreshClientPrediction(handler);
            }

            if (!parkCursor(client, handler, "批次发送前")) {
                finishStopped("批次发送前光标无法放回");
                return false;
            }

            BatchPath path = outputOperations == 0
                    ? BatchPath.MANUAL_REFILL
                    : refilled ? BatchPath.MANUAL_COMBINED : BatchPath.OUTPUT_DRAIN;

            beginAwaitingBatch(current, handler, path, gridBeforeClicks, gridBeforeOutput);
            current.batchOutputOperations = outputOperations;
            current.batchClientSlotClicks = Math.max(0,
                    current.clientSlotClicks - clientSlotClicksBefore);
            current.batchOutputSlotClicks = Math.max(0,
                    current.outputSlotClicks - outputSlotClicksBefore);
            if (outputOperations > 0) {
                current.pickupWaitAttempted = false;
            }

            try {
                sendAckBoundary(networkHandler, current);
            } catch (Throwable throwable) {
                finishStopped("屏障发送异常");
                return false;
            }
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
        return true;
    }

    private void activateOutputSprayMode(MinecraftClient client,
                                         Session current,
                                         String reason) {
        if (!current.outputSprayMode) {
            current.outputSprayMode = true;
            current.outputFillSlotsRemaining = 0;
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
        return QuickCraftMouseCraftInventory.dropMatchingUnlockedInventory(
                client, current.handler, layout, current.resultTemplate, reason);
    }

    private void beginAwaitingBatch(Session current,
                                    ScreenHandler handler,
                                    BatchPath path,
                                    int[] gridBeforeClicks,
                                    int[] gridBeforeOutput) {
        current.batchPath = path;
        current.batchStartRevision = handler.getRevision();
        current.lastProgressRevision = current.batchStartRevision;
        current.outputAck.reset(current.batchStartRevision);
        current.batchGridCounts = gridBeforeClicks != null
                ? gridBeforeClicks
                : snapshotGridCounts(handler);
        current.batchCraftGridCounts = gridBeforeOutput != null
                ? gridBeforeOutput
                : current.batchGridCounts;
        current.craftFailed = false;
        current.invalidAuthoritativeOutput = false;
        current.batchSawExpectedOutput = false;
        current.authoritativeCursorRecoveries = 0;
        // pending 表示“请求已经发出、等待回包”，不能在发送前预置为 true。
        current.statsProbePending = false;
        current.statsProbeDeferred = false;
        current.statsProbeSentForBatch = false;
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
    }

    private void handleServerSlotUpdate(int syncId, int revision, int slotId, ItemStack stack) {
        if (!isActive() || !session.awaiting || syncId != session.syncId) {
            return;
        }
        Session current = session;
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
            }
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
        }
        boolean packetExpectedOutput = isExpectedOutput(packetOutput, current.resultTemplate);
        boolean packetUnexpectedOutput = !packetOutput.isEmpty() && !packetExpectedOutput;
        if (packetUnexpectedOutput) {
            if (packetRecipeMatches) {
                current.invalidAuthoritativeOutput = true;
            }
        }
        observeOutput(current, revision, packetOutput, true);
        current.outputAck.observeRecipeReadyFull(
                revision,
                packetRecipeMatches && cursorEmpty
                        && packetExpectedOutput
        );


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
        long now = System.nanoTime();
        current.statsProbePending = false;
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
        if (!outputEmpty && !expectedOutput
                && currentRecipeMatches && !current.invalidAuthoritativeOutput) {
            current.invalidAuthoritativeOutput = true;
        }
        boolean decreased = ingredientsDecreased(
                current.batchGridCounts, snapshotGridCounts(handler));
        boolean terminalGridCompatible = isPatternOrRemainderGridCompatible(
                current, handler);
        if (current.invalidAuthoritativeOutput) {
            finishStopped("权威错误产物");
            return;
        }

        if (tryConfirmCurrentBatch(revision, now, false)) {
            return;
        }

        if (!cursorEmpty) {
            if (current.retainIngredientSamples
                    && current.authoritativeCursorRecoveries < MAX_AUTHORITATIVE_CURSOR_RECOVERIES) {
                current.authoritativeCursorRecoveries++;
                boolean parked = parkCursor(client, handler, "统计屏障权威光标回收");
                if (parked) {
                    requestStatsProbe(current, source, "权威光标回收后再次确认");
                    return;
                }
            }
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
                    drainOutcome = "统计屏障后产物仍在但原料未减少，重试取产物";
                }
                acknowledgeAndPump(client, current, now, drainOutcome);
                return;
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
            if (current.retainIngredientSamples && intermediateOutput && hasIngredients) {
                if (handler.getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT).inventory
                        instanceof CraftingResultInventory resultInventory) {
                    resultInventory.setStack(QuickCraftMouseCraftLayout.OUTPUT_SLOT, ItemStack.EMPTY);
                }
                acknowledgeAndPump(client, current, now,
                        "统计屏障确认兼容中间产物，清除后继续补货");
                return;
            }
            if (outputEmpty) {
                if (current.retainIngredientSamples
                        && current.batchPath == BatchPath.MANUAL_REFILL
                        && current.batchClientSlotClicks == 0
                        && current.batchOutputSlotClicks == 0) {
                    handleMissingIngredients(client, current, now, true);
                    return;
                }
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

        current.pickupWaitForFullInventory = true;
        current.pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        acknowledgeBatch(current, current.handler.getRevision(), now,
                "首次缺料已释放满包产物，等待拾取");
        return true;
    }

    private boolean tryStartPickupGrace(Session current, long now) {
        int emptySlots = QuickCraftMouseCraftInventory.unlockedEmptySlots(current.handler, layout);
        if (current.pickupWaitAttempted
                || (emptySlots <= 0
                && !(current.retainIngredientSamples && current.outputSprayMode))) {
            return false;
        }
        current.pickupWaitAttempted = true;
        current.pickupWaitForFullInventory = emptySlots <= 0;
        current.pickupWaitTicks = MAX_PICKUP_WAIT_TICKS;
        acknowledgeBatch(current, current.handler.getRevision(), now,
                current.retainIngredientSamples ? "缺料等待自然拾取" : "非满包缺料等待自然拾取");
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
        current.awaiting = false;
        current.statsProbeDeferred = false;
        current.maxAckLatencyNanos = Math.max(current.maxAckLatencyNanos, latencyNanos);
    }

    private void observeOutput(Session current,
                               int revision,
                               ItemStack output,
                               boolean allowExpectedOutput) {
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
        completed.finishHandler.finish(message, allowTailDrop);
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
        clearSession();
        if (fallbackHandler != null && client != null && client.player != null) {
            fallbackHandler.fallback(client, handedOff.handler, handedOff.recipeId);
        }
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
        return parked;
    }

    private boolean tryRetryManualRefill(Session current, long now, String reason) {
        if (current == null || current.handler == null) {
            return false;
        }
        if (current.refillRetries >= MAX_REFILL_RETRIES) {
            return false;
        }
        if (!hasIngredients(current.handler)) {
            return false;
        }
        current.refillRetries++;
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

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
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
        return true;
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
                iterator.remove();
                continue;
            }
            if (now < drain.expiresAtNanos) {
                continue;
            }
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
        private final BooleanSupplier inputHeld;
        private final FinishHandler finishHandler;
        private final ClientPlayNetworkHandler networkHandler;
        private final ClientPlayerEntity player;
        private final boolean retainIngredientSamples;

        private boolean awaiting;
        private boolean stopRequested;
        private Text stopMessage;
        private final OutputAckSequence outputAck = new OutputAckSequence();
        private boolean craftFailed;
        private boolean invalidAuthoritativeOutput;
        private boolean batchSawExpectedOutput;
        private boolean outputExpectedAtDispatch;
        private boolean outputSprayMode;
        private boolean sprayInventoryCleared;
        private boolean noIngredientRescueAttempted;
        private boolean pickupWaitAttempted;
        private boolean pickupWaitForFullInventory;
        private int outputFillSlotsRemaining;
        private int lastAuthoritativeMaterialRevision = Integer.MIN_VALUE;
        private int batchOutputOperations;
        private int batchClientSlotClicks;
        private int batchOutputSlotClicks;
        private int clientSlotClicks;
        private int outputSlotClicks;
        private int batchStartRevision;
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
        private int drainRetries;
        private int authoritativeCursorRecoveries;
        private int pickupWaitTicks;
        private long maxAckLatencyNanos;

        private Session(ScreenHandler handler,
                        int syncId,
                        NetworkRecipeId recipeId,
                        ItemStack resultTemplate,
                        java.util.List<ItemStack> pattern,
                        BooleanSupplier inputHeld,
                        FinishHandler finishHandler,
                        ClientPlayNetworkHandler networkHandler,
                        ClientPlayerEntity player,
                        boolean retainIngredientSamples) {
            this.handler = handler;
            this.syncId = syncId;
            this.recipeId = recipeId;
            this.resultTemplate = resultTemplate;
            this.pattern = pattern;
            this.inputHeld = inputHeld;
            this.finishHandler = finishHandler;
            this.networkHandler = networkHandler;
            this.player = player;
            this.retainIngredientSamples = retainIngredientSamples;
        }
    }
}
