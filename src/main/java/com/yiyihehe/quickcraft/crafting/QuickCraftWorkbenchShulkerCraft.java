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
    private static final int ULTRA_PIPELINE_STEPS_PER_BURST = 4;
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
    private long sessionStartedAtNanos;
    private int sessionCrafted;
    private int sessionOutputBursts;
    private boolean sessionUltraFast;
    private int serverCorrectionPauseTicks;
    private int occupiedCursorTicks;
    private int sessionCursorWaitEvents;
    private int sessionCursorWaitTicks;
    private int sessionCursorRecoveries;
    private int sessionCorrectionPauseTicks;
    private int sessionUltraPipelineTicks;
    private int sessionUltraPipelineExtraBursts;
    private int sessionUltraBurstsPerTick;
    private int sessionCursorSettleTicks;
    private int sessionRecoveryPauseTicks;
    private int sessionCursorTimeoutTicks;
    private boolean sessionOutputMismatchLogged;

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

        if (handleCursorBoundary(handler)) {
            return;
        }
        if (serverCorrectionPauseTicks > 0) {
            serverCorrectionPauseTicks--;
            sessionCorrectionPauseTicks++;
            return;
        }

        if (isUltraPipelineEnabled()) {
            QuickContainerLock.runWithPlayerSlotLocksBypassed(
                    () -> processUltraPipelineTick(client, handler));
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
        if (!sessionUltraFast && QuickCraftWorkbenchShulker.isShulkerCraftActionCoolingDown()) {
            return;
        }
        QuickContainerLock.runWithPlayerSlotLocksBypassed(
                () -> processCraftTick(client, handler));
    }

    private boolean handleCursorBoundary(CraftingScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            if (occupiedCursorTicks > 0) {
                LOGGER.debug("服务端同步后光标已清空：会话t+{} ms，等待={} Tick，revision={}",
                        sessionElapsedMillis(), occupiedCursorTicks, handler.getRevision());
            }
            occupiedCursorTicks = 0;
            return false;
        }

        occupiedCursorTicks++;
        sessionCursorWaitTicks++;
        if (occupiedCursorTicks == 1) {
            sessionCursorWaitEvents++;
            consecutiveFailures = 0;
            LOGGER.debug("持续合成等待服务端光标最终态：会话t+{} ms，光标={}，revision={}，syncId={}，"
                            + "潜影盒恢复等待={} Tick，最长等待={} Tick",
                    sessionElapsedMillis(), describeStack(cursor), handler.getRevision(), handler.syncId,
                    sessionCursorSettleTicks, sessionCursorTimeoutTicks);
        }
        if (shouldRecoverShulkerCursor(occupiedCursorTicks, sessionCursorSettleTicks)
                && QuickCraftWorkbenchShulker.recoverShulkerCraftCursor(handler)) {
            LOGGER.warn("持续合成检测到服务端回退潜影盒：会话t+{} ms，已安全放回并暂停={} Tick，"
                            + "检测前等待={} Tick，revision={}，syncId={}",
                    sessionElapsedMillis(), sessionRecoveryPauseTicks,
                    occupiedCursorTicks, handler.getRevision(), handler.syncId);
            occupiedCursorTicks = 0;
            serverCorrectionPauseTicks = sessionRecoveryPauseTicks;
            sessionCursorRecoveries++;
            consecutiveFailures = 0;
            return true;
        }

        if (!shouldStopForOccupiedCursor(occupiedCursorTicks, sessionCursorTimeoutTicks)) {
            return true;
        }

        LOGGER.error("持续合成光标同步超时：会话t+{} ms，光标={}，等待={} Tick，revision={}，syncId={}",
                sessionElapsedMillis(), describeStack(cursor), occupiedCursorTicks,
                handler.getRevision(), handler.syncId);
        stop(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
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

    private boolean isUltraPipelineEnabled() {
        return sessionUltraFast && QuickCraftConfigs.getQuickShulkerActionIntervalTicks() == 0;
    }

    private void processUltraPipelineTick(MinecraftClient client,
                                          CraftingScreenHandler handler) {
        int burstsBefore = sessionOutputBursts;
        int steps = 0;
        int maxSteps = sessionUltraBurstsPerTick * ULTRA_PIPELINE_STEPS_PER_BURST;
        while (canContinueUltraPipeline(active, steps,
                sessionOutputBursts - burstsBefore, handler.getCursorStack().isEmpty(),
                maxSteps, sessionUltraBurstsPerTick)) {
            int craftedBefore = sessionCrafted;
            int failuresBefore = consecutiveFailures;
            int tasksBefore = QuickCraftWorkbenchShulker.debugSessionTaskCount();
            int sourceBatchesBefore = QuickCraftWorkbenchShulker.debugSessionSourceBatches();
            boolean busyBefore = QuickCraftWorkbenchShulker.isShulkerCraftBusy();

            if (busyBefore) {
                if (!isRapidInputHeld(client)) {
                    QuickCraftWorkbenchShulker.requestShulkerCraftStopAfterCurrentAction();
                }
                QuickCraftWorkbenchShulker.tickShulkerCraft(client);
                processRefillResult(client, handler);
            } else {
                processCraftTick(client, handler);
            }
            steps++;

            boolean progressed = sessionCrafted != craftedBefore
                    || consecutiveFailures != failuresBefore
                    || QuickCraftWorkbenchShulker.debugSessionTaskCount() != tasksBefore
                    || QuickCraftWorkbenchShulker.debugSessionSourceBatches() != sourceBatchesBefore
                    || QuickCraftWorkbenchShulker.isShulkerCraftBusy() != busyBefore;
            if (!progressed) {
                break;
            }
        }

        int bursts = sessionOutputBursts - burstsBefore;
        if (bursts > 1) {
            sessionUltraPipelineTicks++;
            sessionUltraPipelineExtraBursts += bursts - 1;
            LOGGER.debug("极速流水 Tick：会话t+{} ms，步骤={}，输出Burst={}，额外Burst={}，累计合成={}",
                    sessionElapsedMillis(), steps, bursts, bursts - 1, sessionCrafted);
        }
    }

    static boolean canContinueUltraPipeline(boolean active,
                                            int completedSteps,
                                            int completedBursts,
                                            boolean cursorEmpty,
                                            int maxSteps,
                                            int maxBursts) {
        return active && cursorEmpty
                && completedSteps < maxSteps
                && completedBursts < maxBursts;
    }

    private void processCraftTick(MinecraftClient client, CraftingScreenHandler handler) {
        if (hasMissingPerCraftMaterial(handler)) {
            int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
            int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
            if (movedLooseItems > 0) {
                boolean primed = primeOutputLocally(client, handler);
                if (sessionUltraFast && primed) {
                    recordProgressOrStop(client, drainOutputBurst(client, handler));
                } else {
                    consecutiveFailures = 0;
                }
                return;
            }
            if (sessionUltraFast && rebalancedItems > 0 && primeOutputLocally(client, handler)) {
                recordProgressOrStop(client, drainOutputBurst(client, handler));
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

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            boolean progressed = drainOutputBurst(client, handler);
            recordProgressOrStop(client, progressed);
            return;
        }

        if (primeOutputLocally(client, handler)) {
            if (sessionUltraFast) {
                recordProgressOrStop(client, drainOutputBurst(client, handler));
            } else {
                consecutiveFailures = 0;
            }
            return;
        }
        int rebalancedItems = rebalanceIncompleteRepeatedMaterials(client, handler);
        int movedLooseItems = fillMissingSlotsFromPlayerInventory(client, handler);
        if (movedLooseItems > 0) {
            boolean primed = primeOutputLocally(client, handler);
            if (sessionUltraFast && primed) {
                recordProgressOrStop(client, drainOutputBurst(client, handler));
            } else {
                consecutiveFailures = 0;
            }
            return;
        }
        if (sessionUltraFast && rebalancedItems > 0 && primeOutputLocally(client, handler)) {
            recordProgressOrStop(client, drainOutputBurst(client, handler));
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

    private boolean handleRefillStart(MinecraftClient client,
                                      CraftingScreenHandler handler,
                                      QuickCraftWorkbenchShulker.RefillStart refill) {
        if (refill == QuickCraftWorkbenchShulker.RefillStart.STARTED) {
            runFirstRefillActionImmediately(client, handler);
            return true;
        }
        if (refill == QuickCraftWorkbenchShulker.RefillStart.RECOVERED_DESYNC) {
            consecutiveFailures = 0;
            serverCorrectionPauseTicks = sessionRecoveryPauseTicks;
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
                logUnexpectedOutput(handler);
                break;
            }
            client.interactionManager.clickSlot(handler.syncId, OUTPUT_SLOT, 1,
                    SlotActionType.THROW, client.player);
            completed++;
            sessionCrafted++;
            if (completed == 1) {
                sessionOutputBursts++;
            }
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接丢弃 burst：会话t+{} ms，合成={} 次，耗时={} us，配方={}",
                    sessionElapsedMillis(), completed, (System.nanoTime() - startedAtNanos) / 1_000L,
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
                logUnexpectedOutput(handler);
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
            sessionCrafted++;
            if (completed == 1) {
                sessionOutputBursts++;
            }
        }
        if (!QuickCraftWorkbenchShulkerOutput.returnBox(client, handler, sourceSlot)) {
            stop(client, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
        }
        if (completed > 0) {
            LOGGER.debug("输出槽直接装盒 burst：会话t+{} ms，合成={} 次，耗时={} us，不受补货间隔限制，配方={}",
                    sessionElapsedMillis(), completed, (System.nanoTime() - startedAtNanos) / 1_000L,
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
            return;
        }
        if (!sessionUltraFast) {
            consecutiveFailures = 0;
            return;
        }
        if (!active) {
            return;
        }
        long outputStartedAtNanos = System.nanoTime();
        boolean progressed = drainOutputBurst(client, handler);
        LOGGER.debug("快速模式补货完成同Tick输出：会话t+{} ms，任务结果={}，成功={}，耗时={} us",
                sessionElapsedMillis(), result, progressed,
                (System.nanoTime() - outputStartedAtNanos) / 1_000L);
        recordProgressOrStop(client, progressed);
    }

    private boolean drainOutputBurst(MinecraftClient client, CraftingScreenHandler handler) {
        return QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
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
        sessionStartedAtNanos = System.nanoTime();
        sessionCrafted = 0;
        sessionOutputBursts = 0;
        sessionUltraFast = QuickCraftConfigs.isWorkbenchQuickShulkerUltraFastEnabled();
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorWaitEvents = 0;
        sessionCursorWaitTicks = 0;
        sessionCursorRecoveries = 0;
        sessionCorrectionPauseTicks = 0;
        sessionUltraPipelineTicks = 0;
        sessionUltraPipelineExtraBursts = 0;
        sessionUltraBurstsPerTick = QuickCraftConfigs.getWorkbenchQuickShulkerUltraBurstsPerTick();
        sessionCursorSettleTicks = QuickCraftConfigs.getWorkbenchQuickShulkerCursorSettleTicks();
        sessionRecoveryPauseTicks = QuickCraftConfigs.getWorkbenchQuickShulkerRecoveryPauseTicks();
        sessionCursorTimeoutTicks = QuickCraftConfigs.getWorkbenchQuickShulkerCursorTimeoutTicks();
        sessionOutputMismatchLogged = false;
        QuickCraftWorkbenchShulker.beginDebugSession(sessionUltraFast);
        sendMessage(client, Text.translatable("quickcraft.message.crafting.started"));
        LOGGER.info("开始潜影盒工作台喷射：会话t+0 ms，实验极速={}，执行模式={}，极速Burst上限={}，"
                        + "光标策略={}/{}/{} Tick，配方={}，输出装盒={}，操作间隔={} Tick",
                sessionUltraFast, sessionUltraFast ? "同Tick多来源+同Tick输出" : "安全单Tick边界",
                sessionUltraBurstsPerTick, sessionCursorSettleTicks, sessionRecoveryPauseTicks,
                sessionCursorTimeoutTicks, recipe.id(), QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled(),
                QuickCraftConfigs.getQuickShulkerActionIntervalTicks());
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
            LOGGER.debug("复杂配方尾数工作台内重新均分：会话t+{} ms，移动={} 个，耗时={} us，配方={}",
                    sessionElapsedMillis(), movedItems, (System.nanoTime() - startedAtNanos) / 1_000L,
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
        logSessionSummary(message == null ? "无" : message.getString());
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
        if (active) {
            logSessionSummary("工作台关闭或世界退出");
        }
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
        sessionStartedAtNanos = 0L;
        sessionCrafted = 0;
        sessionOutputBursts = 0;
        sessionUltraFast = false;
        serverCorrectionPauseTicks = 0;
        occupiedCursorTicks = 0;
        sessionCursorWaitEvents = 0;
        sessionCursorWaitTicks = 0;
        sessionCursorRecoveries = 0;
        sessionCorrectionPauseTicks = 0;
        sessionUltraPipelineTicks = 0;
        sessionUltraPipelineExtraBursts = 0;
        sessionUltraBurstsPerTick = 0;
        sessionCursorSettleTicks = 0;
        sessionRecoveryPauseTicks = 0;
        sessionCursorTimeoutTicks = 0;
        sessionOutputMismatchLogged = false;
        QuickCraftWorkbenchShulker.clearDebugSession();
        QuickCraftWorkbenchShulker.resetShulkerCraft();
    }

    private void logSessionSummary(String reason) {
        long elapsedMillis = sessionElapsedMillis();
        LOGGER.info("潜影盒工作台喷射汇总：耗时={} ms，合成={} 次，速度={} 次/秒，输出Burst={}，"
                        + "来源任务={}，来源盒批次={}，光标等待={} 次/{} Tick，自动恢复={} 次，恢复静默={} Tick，"
                        + "极速流水={} Tick/额外{} Burst，配置Burst上限={}，光标策略={}/{}/{} Tick，"
                        + "实验极速={}，配方={}，连续失败={}，补料中={}，结束原因={}",
                elapsedMillis, sessionCrafted, craftsPerSecond(sessionCrafted, elapsedMillis), sessionOutputBursts,
                QuickCraftWorkbenchShulker.debugSessionTaskCount(),
                QuickCraftWorkbenchShulker.debugSessionSourceBatches(),
                sessionCursorWaitEvents, sessionCursorWaitTicks, sessionCursorRecoveries,
                sessionCorrectionPauseTicks, sessionUltraPipelineTicks, sessionUltraPipelineExtraBursts,
                sessionUltraBurstsPerTick, sessionCursorSettleTicks, sessionRecoveryPauseTicks,
                sessionCursorTimeoutTicks,
                sessionUltraFast,
                recipe == null ? "NONE" : recipe.id(), consecutiveFailures,
                QuickCraftWorkbenchShulker.isShulkerCraftBusy(), reason);
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
                parts.add((i + 1) + "=" + stack.getName().getString());
            }
        }
        return parts.toString();
    }

    private void logUnexpectedOutput(CraftingScreenHandler handler) {
        if (sessionOutputMismatchLogged) {
            return;
        }
        sessionOutputMismatchLogged = true;
        LOGGER.error("服务端工作台输出与锁定配方不一致：会话t+{} ms，配方={}，期望输出={}，实际输出={}，"
                        + "revision={}，光标={}，合成格={}",
                sessionElapsedMillis(), recipe == null ? "NONE" : recipe.id(), describeStack(resultTemplate),
                describeStack(handler.getSlot(OUTPUT_SLOT).getStack()), handler.getRevision(),
                describeStack(handler.getCursorStack()), describePattern(snapshotPattern(handler)));
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "空" : stack.getName().getString() + "x" + stack.getCount();
    }

    private void sendMessage(MinecraftClient client, Text message) {
        if (message != null && client != null && client.player != null) {
            client.player.sendMessage(message, true);
        }
    }
}
