package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickContainerCopySources.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.render.QuickContainerFillStatus;
import com.yiyihehe.quickcraft.render.QuickContainerToolModeHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlastFurnaceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.FurnaceBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SmokerBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 容器内容复制：
 * - 支持漏斗、漏斗矿车、箱子、木桶、潜影盒、发射器、投掷器、合成器、炉子和酿造台
 * - 先用热键记录一个模板
 * - 再按住填充热键对准同类容器，把对应材料按槽位顺序复制进去
 */
public final class QuickContainerCopy implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int BACKGROUND_ACTION_TIMEOUT_TICKS = 40;
    private static final int CONTINUOUS_REOPEN_DELAY_TICKS = 1;

    private static boolean lastUseDown;
    private static boolean lastContinuousFillDown;
    private static int pendingTicks;
    private static PendingAction pendingAction = PendingAction.NONE;
    private static SupportedContainerType pendingContainerType;
    private static HitResult pendingFillTarget;
    private static QuickContainerFillStatus.Handle pendingFillStatus;
    private static RecordedContainerTemplate recordedTemplate;
    private static boolean allowQuickShulkerSources;
    private static ContinuousFillTask continuousTask;
    private static TargetInteraction suppressedContinuousTarget;
    private static PendingStashRecovery pendingStashRecovery;
    private static Object lastPendingStashScreen;
    private static boolean suppressContainerVerifierRemember;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        QuickContainerFillStatus.initialize();
        QuickContainerToolModeHud.initialize();
    }

    public static boolean handleRecordHotkey(MinecraftClient client) {
        if (client == null
                || client.player == null
                || client.world == null
                || client.interactionManager == null
                || client.currentScreen != null
                || !QuickCraftConfigs.isQuickContainerCopyEnabled()) {
            return false;
        }

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            clearRecordedTemplate(client);
            return true;
        }

        SupportedContainerType type = getSupportedContainerType(client, hitResult);
        if (type == null) {
            return false;
        }

        pendingAction = PendingAction.RECORD;
        pendingContainerType = type;
        QuickContainerFillStatus.stop(client, pendingFillStatus, false);
        pendingFillTarget = null;
        pendingFillStatus = null;
        pendingTicks = 0;
        if (hitResult instanceof BlockHitResult blockHitResult) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHitResult);
        } else if (hitResult instanceof EntityHitResult entityHitResult) {
            client.interactionManager.interactEntity(client.player, entityHitResult.getEntity(), Hand.MAIN_HAND);
        } else {
            return false;
        }
        sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.recording", type.displayName()));
        return true;
    }

    public static boolean canApplyTemplateSnapshot(ScreenHandler handler, TemplateSnapshot snapshot) {
        SupportedContainerType type = SupportedContainerType.fromPublicType(snapshot.type());
        return type != null && isSupportedHandlerForType(handler, type);
    }

    public static void applyTemplateSnapshot(MinecraftClient client,
                                             ScreenHandler handler,
                                             TemplateSnapshot snapshot,
                                             boolean useQuickShulkerSources,
                                             HitResult target,
                                             QuickContainerFillStatus.Handle fillStatus) {
        SupportedContainerType type = SupportedContainerType.fromPublicType(snapshot.type());
        if (type == null || !isSupportedHandlerForType(handler, type)) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.projection_type_mismatch"));
            return;
        }

        RecordedContainerTemplate previousTemplate = recordedTemplate;
        boolean previousQuickShulkerSources = allowQuickShulkerSources;
        recordedTemplate = new RecordedContainerTemplate(
                type,
                copyTemplates(snapshot.slotTemplates()),
                List.copyOf(snapshot.disabledStates())
        );
        allowQuickShulkerSources = useQuickShulkerSources;

        try {
            QuickContainerCopy copy = new QuickContainerCopy();
            FillResult result = copy.applyTemplate(
                    client,
                    handler,
                    SuccessMessage.of("quickcraft.message.container_copy.projection_filled", type.displayName()),
                    true,
                    target != null ? new TargetInteraction(target, type) : null
            );
            QuickContainerFillStatus.finish(client, fillStatus, result.issueCount());
            refreshContainerVerifier(client, target, handler);
            if (result.isComplete()) {
                suppressContinuousFillAfterSingleAction(client, target, type);
            }
        } finally {
            recordedTemplate = previousTemplate;
            allowQuickShulkerSources = previousQuickShulkerSources;
        }
    }

    private void onClientTick(MinecraftClient client) {
        processPendingStashRecovery(client);
        if (!QuickCraftConfigs.isQuickContainerCopyEnabled()
                && !QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            lastUseDown = false;
            lastContinuousFillDown = false;
            pendingAction = PendingAction.NONE;
            pendingContainerType = null;
            QuickContainerFillStatus.stop(client, pendingFillStatus, true);
            pendingFillTarget = null;
            pendingFillStatus = null;
            pendingTicks = 0;
            stopContinuousTask(client, false, null);
            return;
        }

        handleContinuousFillHotkey(client);
        processContinuousTask(client);

        if (continuousTask == null) {
            handleUseAttempt(client);
            processPendingOpen(client);
        }
    }

    private void handleContinuousFillHotkey(MinecraftClient client) {
        boolean fillDown = QuickCraftKeyBindings.isHotkeyDown(
                client,
                QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind(),
                client.options.useKey
        );
        if (!fillDown) {
            if (lastContinuousFillDown) {
                boolean showHoldHint = continuousTask != null;
                stopContinuousTask(client, true, null);
                if (showHoldHint) {
                    sendStatusMessage(
                            client,
                            Text.translatable("quickcraft.message.container_copy.hold_to_fill")
                                    .formatted(Formatting.RED)
                    );
                }
            }
            suppressedContinuousTarget = null;
            lastContinuousFillDown = false;
            return;
        }

        if (continuousTask == null) {
            tryStartContinuousTask(client);
        }
        lastContinuousFillDown = true;
    }

    private void tryStartContinuousTask(MinecraftClient client) {
        if (pendingAction == PendingAction.RECORD) {
            return;
        }
        if (client == null
                || client.player == null
                || client.world == null
                || client.interactionManager == null
                || client.currentScreen != null) {
            return;
        }

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            return;
        }

        SupportedContainerType type = getSupportedContainerType(client, hitResult);
        if (type == null) {
            return;
        }
        if (suppressedContinuousTarget != null && targetMatches(client, suppressedContinuousTarget, hitResult, type)) {
            return;
        }

        ContinuousTemplate template = resolveContinuousTemplate(client, hitResult, type);
        if (template == null) {
            if (!lastContinuousFillDown) {
                sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.no_fill_template"));
            }
            return;
        }

        QuickLitematicaContainerAutofill.cancelPendingForContinuousFill(client);
        pendingAction = PendingAction.NONE;
        pendingContainerType = null;
        QuickContainerFillStatus.stop(client, pendingFillStatus, false);
        pendingFillTarget = null;
        pendingFillStatus = null;
        pendingTicks = 0;
        suppressedContinuousTarget = null;
        continuousTask = new ContinuousFillTask(
                new TargetInteraction(hitResult, type),
                template,
                client.world.getRegistryKey(),
                QuickContainerFillStatus.begin(client, hitResult)
        );
        continuousTask.worldRef = new WeakReference<>(client.world);
        sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.background_start", type.displayName()));
    }

    public static boolean canHandleContinuousContainerFillHotkey(MinecraftClient client) {
        if (client == null
                || client.player == null
                || client.world == null
                || client.interactionManager == null
                || client.currentScreen != null) {
            return continuousTask != null;
        }

        if (!QuickCraftConfigs.isQuickContainerCopyEnabled()
                && !QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            return continuousTask != null;
        }

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            return continuousTask != null;
        }

        SupportedContainerType type = getSupportedContainerType(client, hitResult);
        if (type == null) {
            return continuousTask != null;
        }

        if (hitResult instanceof BlockHitResult blockHitResult
                && QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            TemplateSnapshot snapshot = QuickLitematicaContainerAutofill.getTemplateSnapshot(client, blockHitResult.getBlockPos());
            if (snapshot != null && snapshot.type() == type.publicType) {
                return true;
            }
        }

        return QuickCraftConfigs.isQuickContainerCopyEnabled()
                && recordedTemplate != null
                && recordedTemplate.type == type;
    }

    private ContinuousTemplate resolveContinuousTemplate(MinecraftClient client, HitResult hitResult, SupportedContainerType type) {
        if (hitResult instanceof BlockHitResult blockHitResult
                && QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            TemplateSnapshot snapshot = QuickLitematicaContainerAutofill.getTemplateSnapshot(client, blockHitResult.getBlockPos());
            if (snapshot != null && snapshot.type() == type.publicType) {
                return new ContinuousTemplate(
                        new RecordedContainerTemplate(
                                type,
                                copyTemplates(snapshot.slotTemplates()),
                                List.copyOf(snapshot.disabledStates())
                        ),
                        shouldUseQuickShulker(),
                        SuccessMessage.of("quickcraft.message.container_copy.projection_filled", type.displayName())
                );
            }
        }

        if (QuickCraftConfigs.isQuickContainerCopyEnabled()
                && recordedTemplate != null
                && recordedTemplate.type == type) {
            return new ContinuousTemplate(
                    copyRecordedTemplate(recordedTemplate),
                    shouldUseQuickShulker(),
                    SuccessMessage.of("quickcraft.message.container_copy.copied", type.displayName())
            );
        }

        return null;
    }

    private void processContinuousTask(MinecraftClient client) {
        if (continuousTask == null) {
            return;
        }
        if (client == null || client.player == null || client.interactionManager == null) {
            stopContinuousTask(client, false, null);
            return;
        }
        if (client.currentScreen != null && !(client.currentScreen instanceof HandledScreen<?>)) {
            stopContinuousTask(client, false, Text.translatable("quickcraft.message.container_copy.background_screen_open"));
            return;
        }

        switch (continuousTask.stage) {
            case OPEN_TARGET -> openContinuousTarget(client);
            case WAIT_TARGET_SCREEN -> waitForContinuousTarget(client);
            case FILL_TARGET -> fillContinuousTarget(client);
            case WAIT_SOURCE_SCREEN -> waitForSourceShulker(client);
            case EXTRACT_SOURCE -> extractFromSourceShulker(client);
            case REOPEN_DELAY -> waitBeforeReopen();
        }
    }

    private void openContinuousTarget(MinecraftClient client) {
        if (continuousTask == null) {
            return;
        }
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler != null && isSupportedHandlerForType(handler, continuousTask.target.type())) {
            continuousTask.stage = ContinuousStage.FILL_TARGET;
            continuousTask.ticks = 0;
            return;
        }
        if (client.currentScreen != null) {
            stopContinuousTask(client, false, Text.translatable("quickcraft.message.container_copy.background_screen_open"));
            return;
        }

        if (!openTarget(client, continuousTask.target)) {
            stopContinuousTask(client, false, Text.translatable("quickcraft.message.container_copy.target_open_failed"));
            return;
        }
        continuousTask.stage = ContinuousStage.WAIT_TARGET_SCREEN;
        continuousTask.ticks = 0;
    }

    private void waitForContinuousTarget(MinecraftClient client) {
        if (continuousTask == null) {
            return;
        }
        continuousTask.ticks++;
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null) {
            if (continuousTask.ticks > OPEN_TIMEOUT_TICKS) {
                stopContinuousTask(client, false, Text.translatable("quickcraft.message.container_copy.target_open_timeout"));
            }
            return;
        }

        if (!isSupportedHandlerForType(handler, continuousTask.target.type())) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.template_type_mismatch"));
            return;
        }

        continuousTask.stage = ContinuousStage.FILL_TARGET;
        continuousTask.ticks = 0;
    }

    private void fillContinuousTarget(MinecraftClient client) {
        if (continuousTask == null) {
            return;
        }

        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null) {
            continuousTask.stage = ContinuousStage.OPEN_TARGET;
            return;
        }

        if (!isSupportedHandlerForType(handler, continuousTask.target.type())) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.template_type_mismatch"));
            return;
        }

        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        if (continuousTask.temporaryStash != null
                && countEmptyPlayerStorageSlots(handler) > 0
                && !restoreTemporaryContainerStash(handler, containerSlotIds, client)) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.temporary_stash_restore_failed"));
            return;
        }

        RecordedContainerTemplate previousTemplate = recordedTemplate;
        boolean previousQuickShulkerSources = allowQuickShulkerSources;
        recordedTemplate = continuousTask.template.recordedTemplate();
        allowQuickShulkerSources = continuousTask.template.useQuickShulker();

        FillResult result;
        try {
            result = applyTemplate(client, handler, continuousTask.template.successMessage(), false,
                    continuousTask.target);
        } finally {
            recordedTemplate = previousTemplate;
            allowQuickShulkerSources = previousQuickShulkerSources;
        }
        QuickContainerFillStatus.progress(client, continuousTask.fillStatus, result.issueCount());

        if (result.isComplete()) {
            QuickContainerFillStatus.finish(client, continuousTask.fillStatus, 0);
            if (continuousTask.temporaryStash != null
                    && !restoreTemporaryContainerStash(handler, containerSlotIds, client)) {
                stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.filled_but_stash_restore_failed"));
                return;
            }
            refreshContainerVerifier(client, continuousTask.target.hitResult(), handler);
            stopContinuousTask(client, true, continuousTask.template.successMessage().text());
            return;
        }

        if (!continuousTask.template.useQuickShulker()
                || result.missingDemands().isEmpty()
                || !canUseQuickShulkerOpenPacket()) {
            QuickContainerFillStatus.finish(client, continuousTask.fillStatus, result.issueCount());
            refreshContainerVerifier(client, continuousTask.target.hitResult(), handler);
            stopContinuousTask(client, true, result.message(continuousTask.template.successMessage()));
            return;
        }

        PrepareBatchResult prepareResult;
        RecordedContainerTemplate previousPrepareTemplate = recordedTemplate;
        recordedTemplate = continuousTask.template.recordedTemplate();
        try {
            prepareResult = preparePlayerStorageForBatchExtraction(
                    handler,
                    containerSlotIds,
                    result.missingDemands(),
                    client
            );
        } finally {
            recordedTemplate = previousPrepareTemplate;
        }
        if (prepareResult == PrepareBatchResult.WAIT) {
            return;
        }
        if (prepareResult == PrepareBatchResult.BLOCKED) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.shulker_batch_no_space"));
            return;
        }

        SourceShulker source = findSourceShulkerForDemandsExcept(handler, result.missingDemands(), -1);
        if (source == null) {
            QuickContainerFillStatus.finish(client, continuousTask.fillStatus, result.issueCount());
            refreshContainerVerifier(client, continuousTask.target.hitResult(), handler);
            stopContinuousTask(client, true, result.message(continuousTask.template.successMessage()));
            return;
        }

        if (!canRunQuickShulkerAction(continuousTask)) {
            return;
        }
        int currentSyncId = handler.syncId;
        if (!sendOpenQuickShulkerPacket(source.slotId())) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.quick_shulker_open_failed"));
            return;
        }

        continuousTask.batchFreeSlotsTarget = -1;
        continuousTask.missingDemands = copyMissingDemands(result.missingDemands());
        continuousTask.currentSourcePlayerIndex = source.playerIndex();
        continuousTask.previousSyncId = currentSyncId;
        continuousTask.stage = ContinuousStage.WAIT_SOURCE_SCREEN;
        continuousTask.ticks = 0;
    }

    private void waitForSourceShulker(MinecraftClient client) {
        if (continuousTask == null) {
            return;
        }
        continuousTask.ticks++;
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null) {
            if (continuousTask.ticks > BACKGROUND_ACTION_TIMEOUT_TICKS) {
                stopContinuousTask(client, false, Text.translatable("quickcraft.message.container_copy.quick_shulker_open_timeout"));
            }
            return;
        }

        if (handler instanceof ShulkerBoxScreenHandler
                && handler.syncId != continuousTask.previousSyncId) {
            continuousTask.stage = ContinuousStage.EXTRACT_SOURCE;
            continuousTask.ticks = 0;
            return;
        }

        if (continuousTask.ticks > BACKGROUND_ACTION_TIMEOUT_TICKS) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.quick_shulker_open_timeout"));
        }
    }

    private void extractFromSourceShulker(MinecraftClient client) {
        if (continuousTask == null || continuousTask.missingDemands.isEmpty()) {
            stopContinuousTask(client, true, null);
            return;
        }

        ScreenHandler currentHandler = getOpenHandledContainer(client);
        if (!(currentHandler instanceof ShulkerBoxScreenHandler handler)) {
            stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.quick_shulker_screen_invalid"));
            return;
        }

        if (continuousTask.openNextSourceBeforeExtract) {
            openNextSourceShulkerOrReopenTarget(client, handler);
            return;
        }

        if (!canRunQuickShulkerAction(continuousTask)) {
            return;
        }

        ExtractResult extractResult = moveMatchingItemsFromOpenShulker(handler, continuousTask.missingDemands, client);
        continuousTask.missingDemands = extractResult.remainingDemands();

        if (extractResult.moved() > 0) {
            if (continuousTask.missingDemands.isEmpty()) {
                closeCurrentScreen(client);
                continuousTask.stage = ContinuousStage.REOPEN_DELAY;
                continuousTask.ticks = 0;
            } else if (hasPlayerStorageCapacityForAnyDemand(handler, continuousTask.missingDemands)) {
                continuousTask.openNextSourceBeforeExtract = true;
            }
            return;
        }

        if (!extractResult.attemptedMove()) {
            // 只做了本地扫描时不消耗限速，直接尝试下一个潜影盒。
            continuousTask.quickShulkerActionCooldown = 0;
        }
        continuousTask.openNextSourceBeforeExtract = true;
        openNextSourceShulkerOrReopenTarget(client, handler);
    }

    private void openNextSourceShulkerOrReopenTarget(MinecraftClient client, ShulkerBoxScreenHandler handler) {
        if (continuousTask == null) {
            return;
        }
        if (!continuousTask.missingDemands.isEmpty()
                && hasPlayerStorageCapacityForAnyDemand(handler, continuousTask.missingDemands)) {
            SourceShulker nextSource = findSourceShulkerForDemandsExcept(
                    handler,
                    continuousTask.missingDemands,
                    continuousTask.currentSourcePlayerIndex
            );
            if (nextSource != null) {
                if (!canRunQuickShulkerAction(continuousTask)) {
                    return;
                }
                int currentSyncId = handler.syncId;
                if (!sendOpenQuickShulkerPacket(nextSource.slotId())) {
                    stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.quick_shulker_open_failed"));
                    return;
                }

                continuousTask.currentSourcePlayerIndex = nextSource.playerIndex();
                continuousTask.previousSyncId = currentSyncId;
                continuousTask.openNextSourceBeforeExtract = false;
                continuousTask.stage = ContinuousStage.WAIT_SOURCE_SCREEN;
                continuousTask.ticks = 0;
                return;
            }
        }

        continuousTask.openNextSourceBeforeExtract = false;
        closeCurrentScreen(client);
        continuousTask.stage = ContinuousStage.REOPEN_DELAY;
        continuousTask.ticks = 0;
    }

    private void waitBeforeReopen() {
        if (continuousTask == null) {
            return;
        }
        continuousTask.ticks++;
        if (continuousTask.ticks >= CONTINUOUS_REOPEN_DELAY_TICKS) {
            continuousTask.stage = ContinuousStage.OPEN_TARGET;
            continuousTask.ticks = 0;
        }
    }

    private boolean openTarget(MinecraftClient client, TargetInteraction target) {
        HitResult hitResult = target.hitResult();
        if (hitResult instanceof BlockHitResult blockHitResult) {
            suppressContainerVerifierRemember = true;
            try {
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHitResult);
            } finally {
                suppressContainerVerifierRemember = false;
            }
            return true;
        }
        if (hitResult instanceof EntityHitResult entityHitResult) {
            client.interactionManager.interactEntity(client.player, entityHitResult.getEntity(), Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void stopContinuousTask(MinecraftClient client, boolean closeScreen, Text message) {
        if (continuousTask == null) {
            return;
        }
        ContinuousFillTask task = continuousTask;
        TemporaryContainerStash stash = task.temporaryStash;
        boolean stashPending = false;
        if (stash != null && !tryRestoreContinuousStash(client, task, stash)) {
            pendingStashRecovery = new PendingStashRecovery(task.target, task.worldRef, stash);
            stashPending = true;
        }
        Text displayMessage = stashPending ? stashPendingMessage(message, stash) : message;
        QuickContainerFillStatus.stop(client, task.fillStatus, displayMessage != null);
        suppressedContinuousTarget = lastContinuousFillDown ? task.target : null;
        if (lastContinuousFillDown) {
            // 本 tick 结束后台任务后仍会进入普通右键处理；消费同一次按住，避免重复打开已填完的容器。
            lastUseDown = true;
        }
        task.batchFreeSlotsTarget = -1;
        task.temporaryStash = null;
        continuousTask = null;
        allowQuickShulkerSources = false;
        clearLitematicaHandledScreenBinding();
        if (closeScreen) {
            closeCurrentScreen(client);
        }
        if (displayMessage != null) {
            sendStatusMessage(client, displayMessage);
        }
    }

    private boolean tryRestoreContinuousStash(MinecraftClient client,
                                             ContinuousFillTask task,
                                             TemporaryContainerStash stash) {
        if (client == null || client.player == null || client.world == null
                || task.worldRef == null
                || task.worldRef.get() != client.world
                || !task.worldKey.equals(client.world.getRegistryKey())) {
            return false;
        }
        if (task.stage == ContinuousStage.WAIT_SOURCE_SCREEN
                || task.stage == ContinuousStage.EXTRACT_SOURCE) {
            return false;
        }
        if (client.currentScreen != null && !(client.currentScreen instanceof HandledScreen<?>)) {
            return false;
        }
        if (!currentTargetStillMatches(client, task.target, client.crosshairTarget)) {
            return false;
        }
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null || !isSupportedHandlerForType(handler, task.target.type())) {
            return false;
        }
        continuousTask = task;
        task.temporaryStash = stash;
        boolean restored = restoreTemporaryContainerStash(handler, getContainerSlotIds(handler), client);
        task.temporaryStash = null;
        return restored;
    }

    private void processPendingStashRecovery(MinecraftClient client) {
        if (pendingStashRecovery == null) {
            lastPendingStashScreen = null;
            return;
        }
        if (client == null || client.world == null || client.player == null) {
            return;
        }
        if (client.currentScreen == null) {
            lastPendingStashScreen = null;
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            return;
        }
        if (screen == lastPendingStashScreen) {
            return;
        }
        lastPendingStashScreen = screen;

        if (continuousTask != null
                && (continuousTask.stage == ContinuousStage.WAIT_SOURCE_SCREEN
                || continuousTask.stage == ContinuousStage.EXTRACT_SOURCE)) {
            // 已知源盒阶段打开的潜影盒是取料来源，不能当作原目标恢复。
            return;
        }

        PendingStashRecovery pending = pendingStashRecovery;
        if (pending.worldRef() == null
                || pending.worldRef().get() != client.world
                || !currentTargetStillMatches(client, pending.target(), client.crosshairTarget)) {
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        if (!isSupportedHandlerForType(handler, pending.target().type())) {
            return;
        }

        pendingStashRecovery = null;
        if (restoreTemporaryContainerStash(handler, getContainerSlotIds(handler), client, pending.stash())) {
            sendStatusMessage(client, stashMessage("quickcraft.message.container_copy.temporary_stash_recovered", pending.stash()));
        } else {
            pendingStashRecovery = pending;
            sendStatusMessage(client, stashMessage("quickcraft.message.container_copy.temporary_stash_recovery_failed", pending.stash()));
        }
    }

    /**
     * 原目标寄存尚未恢复时，不能在同一容器的写入路径里清格或丢弃寄存物。
     * 客户端菜单库存与世界方块实体不是同一对象，只能按本次操作的实际目标位置和类型判断关系：
     * 同目标/可能同一容器先恢复，恢复失败就阻断；确认无关的容器照常操作；无法排除碰撞时阻断。
     */
    private boolean preparePendingStashForHandler(MinecraftClient client,
                                                  ScreenHandler handler,
                                                  TargetInteraction operationTarget) {
        PendingStashRecovery pending = pendingStashRecovery;
        if (pending == null) {
            return true;
        }
        if (client == null || client.world == null
                || pending.worldRef() == null
                || pending.worldRef().get() != client.world) {
            return true;
        }
        StashTargetRelation relation = classifyStashTarget(client, pending.target(), operationTarget);
        if (relation == StashTargetRelation.UNRELATED) {
            return true;
        }
        if (relation == StashTargetRelation.SAME_CONTAINER
                && restoreTemporaryContainerStash(handler, getContainerSlotIds(handler), client, pending.stash())) {
            pendingStashRecovery = null;
            return true;
        }
        return false;
    }

    private StashTargetRelation classifyStashTarget(MinecraftClient client,
                                                    TargetInteraction stashedTarget,
                                                    TargetInteraction operationTarget) {
        if (operationTarget == null) {
            return StashTargetRelation.UNCERTAIN;
        }
        if (!(stashedTarget.hitResult() instanceof BlockHitResult stashedHit)
                || !(operationTarget.hitResult() instanceof BlockHitResult operationHit)) {
            if (!QuickContainerFillStatus.sameTarget(client, stashedTarget.hitResult(), operationTarget.hitResult())) {
                return StashTargetRelation.UNRELATED;
            }
            return stashedTarget.type() == operationTarget.type()
                    ? StashTargetRelation.SAME_CONTAINER
                    : StashTargetRelation.UNCERTAIN;
        }

        BlockPos stashedPos = stashedHit.getBlockPos();
        BlockPos operationPos = operationHit.getBlockPos();
        boolean sameType = stashedTarget.type() == operationTarget.type();
        if (stashedPos.equals(operationPos)) {
            return sameType ? StashTargetRelation.SAME_CONTAINER : StashTargetRelation.UNCERTAIN;
        }
        if (sameType && QuickContainerFillStatus.sameTarget(
                client, stashedTarget.hitResult(), operationTarget.hitResult())) {
            return StashTargetRelation.SAME_CONTAINER;
        }
        if (areConnectedChestHalves(client, stashedPos, operationPos)) {
            return StashTargetRelation.UNCERTAIN;
        }
        return StashTargetRelation.UNRELATED;
    }

    private boolean areConnectedChestHalves(MinecraftClient client, BlockPos firstPos, BlockPos secondPos) {
        if (client == null || client.world == null || firstPos.getManhattanDistance(secondPos) != 1) {
            return false;
        }
        var firstState = client.world.getBlockState(firstPos);
        var secondState = client.world.getBlockState(secondPos);
        if (!(firstState.getBlock() instanceof ChestBlock)
                || firstState.getBlock() != secondState.getBlock()) {
            return false;
        }
        ChestType firstType = firstState.get(ChestBlock.CHEST_TYPE);
        return firstType != ChestType.SINGLE
                && secondState.get(ChestBlock.CHEST_TYPE) == firstType.getOpposite()
                && firstState.get(ChestBlock.FACING) == secondState.get(ChestBlock.FACING)
                && firstPos.offset(ChestBlock.getFacing(firstState)).equals(secondPos);
    }

    private Text stashPendingMessage(Text reason, TemporaryContainerStash stash) {
        Text pending = stashMessage("quickcraft.message.container_copy.temporary_stash_pending", stash);
        return reason == null
                ? pending
                : Text.translatable("quickcraft.message.container_copy.temporary_stash_pending_with_reason", reason, pending);
    }

    private Text stashMessage(String key, TemporaryContainerStash stash) {
        return Text.translatable(key, stash.stack().getName(), stash.stack().getCount());
    }

    public static boolean shouldHideBackgroundHandledScreen() {
        return continuousTask != null;
    }

    /**
     * 连续填充期间只保留后台 ScreenHandler，不真正把容器界面切到前台。
     */
    public static boolean shouldSuppressBackgroundHandledScreenOpen() {
        return continuousTask != null;
    }

    /**
     * 连续填充热键默认就是鼠标右键，前台不弹界面后要额外压住原版 use 输入。
     */
    public static boolean shouldSuppressContinuousFillUseInput() {
        return continuousTask != null || suppressedContinuousTarget != null && lastContinuousFillDown;
    }

    public static boolean shouldSuppressContainerVerifierRemember() {
        return suppressContainerVerifierRemember;
    }

    private static void refreshContainerVerifier(MinecraftClient client, HitResult target, ScreenHandler handler) {
        if (target instanceof BlockHitResult blockHit && FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaContainerVerifier.refreshFilledContainer(client, blockHit.getBlockPos(), handler);
        }
    }

    private void clearLitematicaHandledScreenBinding() {
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaContainerVerifier.clearCurrentHandledScreenBinding();
        }
    }

    private ScreenHandler getOpenHandledContainer(MinecraftClient client) {
        if (client == null || client.player == null) {
            return null;
        }

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            return screen.getScreenHandler();
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler == client.player.playerScreenHandler || handler.syncId == 0) {
            return null;
        }

        return handler;
    }

    private boolean targetMatches(MinecraftClient client, TargetInteraction target, HitResult hitResult, SupportedContainerType type) {
        return target.type() == type
                && QuickContainerFillStatus.sameTarget(client, target.hitResult(), hitResult);
    }

    /**
     * 保存的 type 只描述记录时的容器；同形状菜单（小箱/木桶/三行Generic）还要核对当前世界里准星目标的实际类型，
     * 避免原容器被拆换后从同坐标的另一类容器取走物品。
     */
    private boolean currentTargetStillMatches(MinecraftClient client,
                                              TargetInteraction target,
                                              HitResult currentHitResult) {
        return targetMatches(client, target, currentHitResult, target.type())
                && getSupportedContainerType(client, currentHitResult) == target.type();
    }

    public static boolean requireContainerFillHotkey(MinecraftClient client) {
        var keybind = QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind();
        if (!keybind.getKeys().isEmpty()
                && QuickCraftKeyBindings.isHotkeyDown(client, keybind, client.options.useKey)) {
            return true;
        }
        String messageKey = keybind.getKeys().isEmpty()
                ? "quickcraft.message.container_copy.fill_hotkey_unbound"
                : "quickcraft.message.container_copy.hold_to_fill";
        sendStatusMessage(client, Text.translatable(messageKey).formatted(Formatting.RED));
        return false;
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (suppressedContinuousTarget != null && lastContinuousFillDown) {
            lastUseDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
            return;
        }
        if (pendingAction != PendingAction.NONE
                || !QuickCraftConfigs.isQuickContainerCopyEnabled()
                || recordedTemplate == null
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (useDown && !lastUseDown) {
            if (QuickMaterialCollector.shouldHandleCurrentTarget(client)
                    || QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)) {
                lastUseDown = true;
                return;
            }

            SupportedContainerType type = getSupportedContainerType(client, client.crosshairTarget);
            if (type == recordedTemplate.type && requireContainerFillHotkey(client)) {
                pendingAction = PendingAction.APPLY;
                pendingContainerType = type;
                pendingFillTarget = client.crosshairTarget;
                pendingTicks = 0;
                pendingFillStatus = QuickContainerFillStatus.begin(client, pendingFillTarget);
            }
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (pendingAction == PendingAction.NONE) {
            return;
        }

        pendingTicks++;
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                QuickContainerFillStatus.stop(client, pendingFillStatus, true);
                pendingAction = PendingAction.NONE;
                pendingContainerType = null;
                pendingFillTarget = null;
                pendingFillStatus = null;
                pendingTicks = 0;
            }
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        SupportedContainerType type = pendingContainerType;
        if (type == null || !isSupportedHandlerForType(handler, type)) {
            QuickContainerFillStatus.stop(client, pendingFillStatus, true);
            pendingAction = PendingAction.NONE;
            pendingContainerType = null;
            pendingFillTarget = null;
            pendingFillStatus = null;
            pendingTicks = 0;
            return;
        }

        PendingAction action = pendingAction;
        HitResult fillTarget = pendingFillTarget;
        QuickContainerFillStatus.Handle fillStatus = pendingFillStatus;
        pendingAction = PendingAction.NONE;
        pendingContainerType = null;
        pendingFillTarget = null;
        pendingFillStatus = null;
        pendingTicks = 0;

        if (action == PendingAction.RECORD) {
            recordTemplate(client, handler, type);
            closeCurrentScreen(client);
            return;
        }

        if (recordedTemplate == null || recordedTemplate.type != type) {
            QuickContainerFillStatus.stop(client, fillStatus, true);
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.no_record_for_type", type.displayName()));
            closeCurrentScreen(client);
            return;
        }

        allowQuickShulkerSources = shouldUseQuickShulker();
        try {
            FillResult result = applyTemplate(
                    client,
                    handler,
                    SuccessMessage.of("quickcraft.message.container_copy.copied", recordedTemplate.type.displayName()),
                    true,
                    fillTarget != null ? new TargetInteraction(fillTarget, type) : null
            );
            QuickContainerFillStatus.finish(client, fillStatus, result.issueCount());
            refreshContainerVerifier(client, fillTarget, handler);
            if (result.isComplete()) {
                suppressContinuousFillAfterSingleAction(client, fillTarget, type);
            }
        } finally {
            allowQuickShulkerSources = false;
        }
        closeCurrentScreen(client);
    }

    private static void suppressContinuousFillAfterSingleAction(
            MinecraftClient client,
            HitResult target,
            SupportedContainerType type
    ) {
        boolean hotkeyHeld = QuickCraftKeyBindings.isHotkeyDown(
                client,
                QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind(),
                client.options.useKey
        );
        if ((!lastContinuousFillDown && !hotkeyHeld) || continuousTask != null || target == null) {
            return;
        }
        suppressedContinuousTarget = new TargetInteraction(target, type);
    }

    private void recordTemplate(MinecraftClient client, ScreenHandler handler, SupportedContainerType type) {
        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        List<ItemStack> templates = new ArrayList<>(containerSlotIds.size());
        List<Boolean> disabledStates = new ArrayList<>(containerSlotIds.size());

        for (int slotId : containerSlotIds) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(isCrafterInputSlotDisabled(handler, slotId));
        }

        recordedTemplate = new RecordedContainerTemplate(type, templates, disabledStates);
        sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.recorded", type.displayName()));
    }

    private FillResult applyTemplate(MinecraftClient client,
                                     ScreenHandler handler,
                                     SuccessMessage successMessage,
                                     boolean showMessages,
                                     TargetInteraction operationTarget) {
        List<Text> missingMessages = new ArrayList<>();
        List<Text> blockedMessages = new ArrayList<>();
        List<MissingDemand> missingDemands = new ArrayList<>();

        if (client.player == null || client.interactionManager == null || recordedTemplate == null) {
            return FillResult.empty();
        }

        if (!handler.getCursorStack().isEmpty()) {
            FillResult result = FillResult.blocked(Text.translatable("quickcraft.message.container_copy.cursor_stack_blocked"));
            if (showMessages) {
                sendStatusMessage(client, result.message(successMessage));
            }
            return result;
        }

        if (!preparePendingStashForHandler(client, handler, operationTarget)) {
            PendingStashRecovery pending = pendingStashRecovery;
            FillResult result = FillResult.blocked(stashMessage(
                    "quickcraft.message.container_copy.temporary_stash_recovery_failed",
                    pending.stash()
            ));
            if (showMessages) {
                sendStatusMessage(client, result.message(successMessage));
            }
            return result;
        }

        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        applyCrafterSlotStates(handler, containerSlotIds, client);
        if (client.player.getAbilities().creativeMode && QuickCraftConfigs.isCreativeContainerFillEnabled()) {
            FillResult result = applyCreativeTemplate(client, handler, containerSlotIds);
            if (showMessages) {
                Text message = result.isComplete()
                        ? successMessage.text()
                        : Text.translatable(
                                "quickcraft.message.container_copy.creative_partial_failed",
                                joinTexts(result.blockedMessages())
                        );
                sendStatusMessage(client, message);
            }
            return result;
        }

        List<Integer> excessSlotIndexes = new ArrayList<>();
        List<Integer> wrongSlotIndexes = new ArrayList<>();

        // 先补齐正确格/空格，材料优先从容器内其它错误格拿，背包只做补充来源。
        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            ItemStack template = recordedTemplate.slotTemplates.get(i);
            int containerSlotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(containerSlotId);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            if (template.isEmpty()) {
                if (slot.hasStack()) {
                    wrongSlotIndexes.add(i);
                }
                continue;
            }

            ItemStack currentStack = slot.getStack();
            if (!currentStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(currentStack, template)) {
                wrongSlotIndexes.add(i);
                continue;
            }
            if (currentStack.getCount() > template.getCount()) {
                excessSlotIndexes.add(i);
                continue;
            }

            int neededCount = currentStack.isEmpty()
                    ? template.getCount()
                    : Math.max(0, template.getCount() - currentStack.getCount());
            if (neededCount <= 0) {
                continue;
            }

            int missingCount = fillContainerSlotFromContainer(handler, containerSlotIds, i, template, neededCount, client);
            if (missingCount > 0) {
                missingCount = fillContainerSlot(handler, containerSlotId, template, missingCount, client);
            }
            if (missingCount > 0) {
                missingMessages.add(Text.translatable(
                        "quickcraft.message.container_copy.slot_missing_item",
                        i + 1,
                        missingCount,
                        template.getName()
                ));
                addMissingDemand(missingDemands, template, missingCount);
            }
        }

        for (int index : excessSlotIndexes) {
            if (index >= containerSlotIds.size() || index >= recordedTemplate.slotTemplates.size()) {
                continue;
            }

            int containerSlotId = containerSlotIds.get(index);
            ItemStack template = recordedTemplate.slotTemplates.get(index);
            Slot slot = handler.getSlot(containerSlotId);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            int extraCount = slot.getStack().getCount() - template.getCount();
            if (extraCount <= 0) {
                continue;
            }

            if (!trimMatchingContainerSlot(handler, containerSlotId, template, client)) {
                blockedMessages.add(Text.translatable(
                        "quickcraft.message.container_copy.slot_extra_item",
                        index + 1,
                        extraCount,
                        template.getName()
                ));
            }
        }

        // 错误格先尝试容器内部换位，再动背包，避免打乱容器时第一次只把东西全取出来。
        for (int index : wrongSlotIndexes) {
            if (index >= containerSlotIds.size() || index >= recordedTemplate.slotTemplates.size()) {
                continue;
            }

            int containerSlotId = containerSlotIds.get(index);
            ItemStack template = recordedTemplate.slotTemplates.get(index);
            if (QuickContainerLock.isLockedSlot(handler, containerSlotId)) {
                continue;
            }
            if (template.isEmpty()) {
                continue;
            }

            int missingCount = normalizeNonEmptyTemplateSlot(handler, containerSlotIds, index, template, client);
            if (missingCount > 0) {
                missingMessages.add(Text.translatable(
                        "quickcraft.message.container_copy.slot_missing_item",
                        index + 1,
                        missingCount,
                        template.getName()
                ));
                addMissingDemand(missingDemands, template, missingCount);
            }
        }

        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            if (excessSlotIndexes.contains(i)) {
                continue;
            }

            ItemStack template = recordedTemplate.slotTemplates.get(i);
            if (template.isEmpty()) {
                continue;
            }

            int containerSlotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(containerSlotId);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            if (!slot.hasStack()
                    || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)
                    || slot.getStack().getCount() <= template.getCount()) {
                continue;
            }

            int extraCount = slot.getStack().getCount() - template.getCount();
            if (!trimMatchingContainerSlot(handler, containerSlotId, template, client)) {
                blockedMessages.add(Text.translatable(
                        "quickcraft.message.container_copy.slot_extra_item",
                        i + 1,
                        extraCount,
                        template.getName()
                ));
            }
        }

        for (int index : wrongSlotIndexes) {
            if (index >= containerSlotIds.size() || index >= recordedTemplate.slotTemplates.size()) {
                continue;
            }

            ItemStack template = recordedTemplate.slotTemplates.get(index);
            if (!template.isEmpty()) {
                continue;
            }

            int containerSlotId = containerSlotIds.get(index);
            if (QuickContainerLock.isLockedSlot(handler, containerSlotId)) {
                continue;
            }
            if (!tryMoveSlotToPlayerStorage(handler, containerSlotId, client)) {
                blockedMessages.add(Text.translatable("quickcraft.message.container_copy.slot_cannot_clear", index + 1));
            }
        }

        FillResult result = new FillResult(missingMessages, blockedMessages, missingDemands);
        if (showMessages) {
            sendStatusMessage(client, result.message(successMessage));
        }
        return result;
    }

    private void addMissingDemand(List<MissingDemand> missingDemands, ItemStack template, int count) {
        if (count <= 0 || template.isEmpty()) {
            return;
        }

        for (int i = 0; i < missingDemands.size(); i++) {
            MissingDemand demand = missingDemands.get(i);
            if (ItemStack.areItemsAndComponentsEqual(demand.template(), template)) {
                missingDemands.set(i, new MissingDemand(demand.template(), demand.count() + count));
                return;
            }
        }

        ItemStack demandTemplate = template.copy();
        demandTemplate.setCount(1);
        missingDemands.add(new MissingDemand(demandTemplate, count));
    }

    private FillResult applyCreativeTemplate(MinecraftClient client,
                                             ScreenHandler handler,
                                             List<Integer> containerSlotIds) {
        List<Text> blockedMessages = new ArrayList<>();
        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            int containerSlotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(containerSlotId);
            ItemStack template = recordedTemplate.slotTemplates.get(i);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            if (slotMatchesTemplate(slot, template)) {
                continue;
            }
            if (!slot.isEnabled()) {
                blockedMessages.add(Text.translatable("quickcraft.message.container_copy.slot_cannot_fill", i + 1));
                continue;
            }

            boolean applied = template.isEmpty()
                    ? clearContainerSlotInCreative(handler, containerSlotId, client)
                    : setContainerSlotInCreative(handler, containerSlotId, template, client);
            if (!applied) {
                blockedMessages.add(Text.translatable("quickcraft.message.container_copy.slot_cannot_fill", i + 1));
            }
        }

        return new FillResult(List.of(), blockedMessages, List.of());
    }

    private static Text joinTexts(List<Text> texts) {
        MutableText joined = Text.empty();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                joined.append(Text.translatable("quickcraft.message.separator"));
            }
            joined.append(texts.get(i));
        }
        return joined;
    }

    private boolean slotMatchesTemplate(Slot slot, ItemStack template) {
        ItemStack currentStack = slot.getStack();
        if (template.isEmpty()) {
            return currentStack.isEmpty();
        }

        return !currentStack.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(currentStack, template)
                && currentStack.getCount() == template.getCount();
    }

    private boolean setContainerSlotInCreative(ScreenHandler handler,
                                               int containerSlotId,
                                               ItemStack template,
                                               MinecraftClient client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isEnabled()
                || !targetSlot.canInsert(template)
                || template.getCount() > Math.min(template.getMaxCount(), targetSlot.getMaxItemCount(template))) {
            return false;
        }

        if (targetSlot.hasStack() && !clearContainerSlotInCreative(handler, containerSlotId, client)) {
            return false;
        }

        int scratchSlotId = findCreativeScratchSlotId(handler);
        if (scratchSlotId == -1) {
            return false;
        }

        Slot scratchSlot = handler.getSlot(scratchSlotId);
        ItemStack originalScratchStack = scratchSlot.getStack().copy();
        setCreativePlayerSlot(handler, scratchSlotId, template.copy(), client);

        client.interactionManager.clickSlot(
                handler.syncId,
                scratchSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (!handler.getCursorStack().isEmpty()) {
            returnCursorStack(handler, scratchSlotId, client);
        }
        setCreativePlayerSlot(handler, scratchSlotId, originalScratchStack, client);
        return handler.getCursorStack().isEmpty()
                && targetSlot.hasStack()
                && ItemStack.areItemsAndComponentsEqual(targetSlot.getStack(), template)
                && targetSlot.getStack().getCount() == template.getCount();
    }

    private boolean clearContainerSlotInCreative(ScreenHandler handler, int containerSlotId, MinecraftClient client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.hasStack()) {
            return true;
        }
        if (!targetSlot.canTakeItems(client.player)) {
            return false;
        }

        int scratchSlotId = findCreativeScratchSlotId(handler);
        if (scratchSlotId == -1) {
            return false;
        }

        Slot scratchSlot = handler.getSlot(scratchSlotId);
        ItemStack originalScratchStack = scratchSlot.getStack().copy();
        setCreativePlayerSlot(handler, scratchSlotId, ItemStack.EMPTY, client);

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        returnCursorStack(handler, scratchSlotId, client);
        setCreativePlayerSlot(handler, scratchSlotId, originalScratchStack, client);
        return handler.getCursorStack().isEmpty() && !targetSlot.hasStack();
    }

    private int findCreativeScratchSlotId(ScreenHandler handler) {
        int fallbackSlotId = -1;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isEnabled()) {
                continue;
            }
            if (!slot.hasStack()) {
                return slotId;
            }
            if (fallbackSlotId == -1) {
                fallbackSlotId = slotId;
            }
        }

        return fallbackSlotId;
    }

    private void setCreativePlayerSlot(ScreenHandler handler, int slotId, ItemStack stack, MinecraftClient client) {
        int creativeSlotId = getCreativePlayerScreenSlotId(handler.getSlot(slotId));
        if (creativeSlotId == -1) {
            return;
        }

        ItemStack stackCopy = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        client.interactionManager.clickCreativeStack(stackCopy, creativeSlotId);
        handler.getSlot(slotId).setStack(stackCopy.copy());
    }

    private int getCreativePlayerScreenSlotId(Slot slot) {
        if (!isPlayerStorageSlot(slot)) {
            return -1;
        }

        int index = slot.getIndex();
        return index < 9 ? 36 + index : index;
    }

    private void applyCrafterSlotStates(ScreenHandler handler, List<Integer> containerSlotIds, MinecraftClient client) {
        if (!(handler instanceof CrafterScreenHandler crafterHandler)
                || client.interactionManager == null
                || recordedTemplate == null) {
            return;
        }

        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.disabledStates.size(); i++) {
            int slotId = containerSlotIds.get(i);
            if (QuickContainerLock.isLockedSlot(handler, slotId)) {
                continue;
            }
            boolean shouldBeEnabled = !recordedTemplate.disabledStates.get(i);
            boolean isEnabled = !crafterHandler.isSlotDisabled(slotId);
            if (isEnabled == shouldBeEnabled) {
                continue;
            }

            crafterHandler.setSlotEnabled(slotId, shouldBeEnabled);
            client.interactionManager.slotChangedState(slotId, handler.syncId, shouldBeEnabled);
        }
    }

    private int fillContainerSlot(ScreenHandler handler,
                                  int containerSlotId,
                                  ItemStack template,
                                  int neededCount,
                                  MinecraftClient client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isEnabled()
                || !targetSlot.canInsert(template)) {
            return neededCount;
        }

        int remaining = neededCount;
        while (remaining > 0) {
            int sourceSlotId = findMatchingPlayerSlotId(handler, template);
            if (sourceSlotId == -1) {
                break;
            }

            int moved = moveItemsToTargetSlot(handler, sourceSlotId, containerSlotId, remaining, client);
            if (moved <= 0) {
                break;
            }

            remaining -= moved;
        }

        return remaining;
    }

    private int normalizeNonEmptyTemplateSlot(ScreenHandler handler,
                                              List<Integer> containerSlotIds,
                                              int templateIndex,
                                              ItemStack template,
                                              MinecraftClient client) {
        int containerSlotId = containerSlotIds.get(templateIndex);
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isEnabled()
                || !targetSlot.canInsert(template)
                || !handler.getCursorStack().isEmpty()) {
            return template.getCount();
        }

        ItemStack targetStack = targetSlot.getStack();
        if (!targetStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(targetStack, template)) {
            int neededCount = template.getCount() - targetStack.getCount();
            if (neededCount <= 0) {
                return 0;
            }
            int missingCount = fillContainerSlotFromContainer(handler, containerSlotIds, templateIndex, template, neededCount, client);
            return missingCount == 0 ? 0 : fillContainerSlot(handler, containerSlotId, template, missingCount, client);
        }

        int sourceSlotId = findMatchingWrongContainerSlotId(handler, containerSlotIds, templateIndex, template);
        if (sourceSlotId != -1 && swapContainerSlots(handler, sourceSlotId, containerSlotId, client)) {
            return topUpMatchingContainerSlot(handler, containerSlotId, template, client);
        }

        if (!targetSlot.canTakeItems(client.player)) {
            return template.getCount();
        }
        if (tryMoveSlotToPlayerStorage(handler, containerSlotId, client)) {
            return fillContainerSlot(handler, containerSlotId, template, template.getCount(), client);
        }

        int playerSourceSlotId = findBestReplacementSourceSlotId(handler, template);
        if (playerSourceSlotId == -1) {
            return template.getCount();
        }

        if (!swapPlayerStackIntoContainerSlot(handler, playerSourceSlotId, containerSlotId, client)) {
            return template.getCount();
        }

        return topUpMatchingContainerSlot(handler, containerSlotId, template, client);
    }

    private int fillContainerSlotFromContainer(ScreenHandler handler,
                                               List<Integer> containerSlotIds,
                                               int targetTemplateIndex,
                                               ItemStack template,
                                               int neededCount,
                                               MinecraftClient client) {
        int targetSlotId = containerSlotIds.get(targetTemplateIndex);
        Slot targetSlot = handler.getSlot(targetSlotId);
        if (!targetSlot.isEnabled() || !targetSlot.canInsert(template)) {
            return neededCount;
        }

        int remaining = neededCount;
        while (remaining > 0) {
            int sourceSlotId = findMatchingWrongContainerSlotId(handler, containerSlotIds, targetTemplateIndex, template);
            if (sourceSlotId == -1) {
                break;
            }

            int moved = moveItemsToTargetSlot(handler, sourceSlotId, targetSlotId, remaining, client);
            if (moved <= 0) {
                break;
            }

            remaining -= moved;
        }

        return remaining;
    }

    private int findMatchingWrongContainerSlotId(ScreenHandler handler,
                                                 List<Integer> containerSlotIds,
                                                 int targetTemplateIndex,
                                                 ItemStack template) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return -1;
        }

        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            if (i == targetTemplateIndex) {
                continue;
            }

            ItemStack expected = recordedTemplate.slotTemplates.get(i);
            int slotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.canTakeItems(client.player)
                    || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                continue;
            }

            if (expected.isEmpty() || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), expected)) {
                return slotId;
            }
        }

        return -1;
    }

    private boolean swapContainerSlots(ScreenHandler handler, int sourceSlotId, int targetSlotId, MinecraftClient client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        Slot targetSlot = handler.getSlot(targetSlotId);
        if (!sourceSlot.hasStack()
                || !sourceSlot.canTakeItems(client.player)
                || !targetSlot.canInsert(sourceSlot.getStack())
                || (targetSlot.hasStack() && !targetSlot.canTakeItems(client.player))) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                targetSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (!handler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    sourceSlotId,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }

        return handler.getCursorStack().isEmpty();
    }

    private boolean swapPlayerStackIntoContainerSlot(ScreenHandler handler,
                                                     int sourceSlotId,
                                                     int containerSlotId,
                                                     MinecraftClient client) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        returnCursorStack(handler, sourceSlotId, client);
        return handler.getCursorStack().isEmpty();
    }

    private int topUpMatchingContainerSlot(ScreenHandler handler,
                                           int containerSlotId,
                                           ItemStack template,
                                           MinecraftClient client) {
        ItemStack currentStack = handler.getSlot(containerSlotId).getStack();
        if (currentStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(currentStack, template)) {
            return template.getCount();
        }

        int neededCount = Math.max(0, template.getCount() - currentStack.getCount());
        return neededCount == 0 ? 0 : fillContainerSlot(handler, containerSlotId, template, neededCount, client);
    }

    private boolean tryMoveSlotToPlayerStorage(ScreenHandler handler, int containerSlotId, MinecraftClient client) {
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
            return !slot.hasStack();
        }

        QuickSlotTransfer.quickMove(client, handler, containerSlotId, QuickSlotTransfer::isPlayerStorageSlot, true);

        if (handler.getSlot(containerSlotId).hasStack()
                && handler.getCursorStack().isEmpty()
                && allowQuickShulkerSources) {
            if (packSlotIntoQuickShulker(handler, containerSlotId, client)) {
                return true;
            }

            if (freePlayerStorageSlotIntoQuickShulker(handler, client)) {
                QuickSlotTransfer.quickMove(client, handler, containerSlotId, QuickSlotTransfer::isPlayerStorageSlot, true);
            }
        }

        if (handler.getSlot(containerSlotId).hasStack()
                && handler.getCursorStack().isEmpty()
                && !isTemporaryStashSlot(containerSlotId)
                && QuickCraftConfigs.isContainerFillOverflowDropEnabled()) {
            return dropContainerSlot(handler, containerSlotId, client);
        }

        return !handler.getSlot(containerSlotId).hasStack() && handler.getCursorStack().isEmpty();
    }

    private boolean trimMatchingContainerSlot(ScreenHandler handler,
                                              int containerSlotId,
                                              ItemStack template,
                                              MinecraftClient client) {
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasStack()
                || !slot.canTakeItems(client.player)
                || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
            return false;
        }

        int extraCount = slot.getStack().getCount() - template.getCount();
        if (extraCount <= 0) {
            return true;
        }
        ItemStack extraStack = template.copyWithCount(extraCount);
        boolean canReturnExtra = canStoreStackInPlayerStorage(handler, extraStack)
                || (allowQuickShulkerSources && canStoreStackInQuickShulkers(handler, extraStack));
        if (!canReturnExtra && allowQuickShulkerSources && freePlayerStorageSlotIntoQuickShulker(handler, client)) {
            canReturnExtra = canStoreStackInPlayerStorage(handler, extraStack)
                    || canStoreStackInQuickShulkers(handler, extraStack);
        }
        boolean allowOverflowDrop = QuickCraftConfigs.isContainerFillOverflowDropEnabled();
        if (!canReturnExtra && !allowOverflowDrop) {
            return false;
        }

        if (QuickShulkerClickSafety.isBundlingConflict(template)) {
            int remaining = extraCount;
            for (int returnSlotId : getPlayerStorageSlotIds(handler)) {
                int moved = QuickShulkerClickSafety.moveExactAmountToSlot(
                        handler, containerSlotId, returnSlotId, remaining, client);
                remaining -= moved;
                if (remaining == 0 || !handler.getCursorStack().isEmpty()) {
                    break;
                }
            }
            return remaining == 0
                    && handler.getCursorStack().isEmpty()
                    && handler.getSlot(containerSlotId).hasStack()
                    && handler.getSlot(containerSlotId).getStack().getCount() == template.getCount();
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        for (int i = 0; i < template.getCount(); i++) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    containerSlotId,
                    1,
                    SlotActionType.PICKUP,
                    client.player
            );
        }

        if (!handler.getCursorStack().isEmpty()) {
            if (canReturnExtra) {
                returnCursorStack(handler, findReturnSlotId(handler, handler.getCursorStack()), client);
            }
            if (!handler.getCursorStack().isEmpty() && allowOverflowDrop) {
                dropCursorStack(handler, client);
            }
        }
        return handler.getCursorStack().isEmpty()
                && handler.getSlot(containerSlotId).hasStack()
                && handler.getSlot(containerSlotId).getStack().getCount() == template.getCount();
    }

    private boolean dropContainerSlot(ScreenHandler handler, int slotId, MinecraftClient client) {
        Slot slot = handler.getSlot(slotId);
        if (!slot.hasStack() || !slot.canTakeItems(client.player) || !handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                1,
                SlotActionType.THROW,
                client.player
        );
        return !handler.getSlot(slotId).hasStack();
    }

    private void dropCursorStack(ScreenHandler handler, MinecraftClient client) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                -999,
                0,
                SlotActionType.PICKUP,
                client.player
        );
    }

    int moveItemsToTargetSlot(ScreenHandler handler,
                                      int sourceSlotId,
                                      int targetSlotId,
                                      int neededCount,
                                      MinecraftClient client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!sourceSlot.hasStack()) {
            return 0;
        }

        if (QuickShulkerClickSafety.isBundlingConflict(sourceSlot.getStack())
                && sourceSlot.getStack().getCount() > neededCount) {
            return QuickShulkerClickSafety.moveExactAmountToSlot(
                    handler, sourceSlotId, targetSlotId, neededCount, client);
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) {
            return 0;
        }

        Slot targetSlot = handler.getSlot(targetSlotId);
        int beforeCount = targetSlot.hasStack() ? targetSlot.getStack().getCount() : 0;

        boolean split = cursorStack.getCount() > neededCount;
        QuickSlotTransfer.placeCursorAmount(client, handler, targetSlot, neededCount);
        if (split) {
            returnCursorStack(handler, sourceSlotId, client);
        }

        int afterCount = targetSlot.hasStack() ? targetSlot.getStack().getCount() : 0;
        return Math.max(0, afterCount - beforeCount);
    }

    private void returnCursorStack(ScreenHandler handler, int preferredSlotId, MinecraftClient client) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }

        int targetSlotId = preferredSlotId >= 0
                && preferredSlotId < handler.slots.size()
                && canAcceptCursorStack(handler.getSlot(preferredSlotId), handler.getCursorStack())
                ? preferredSlotId
                : findReturnSlotId(handler, handler.getCursorStack());

        if (targetSlotId != -1) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    targetSlotId,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
            return;
        }

        if (allowQuickShulkerSources) {
            packCursorIntoQuickShulkerExcept(handler, -1, client);
        }
    }

    private int findMatchingPlayerSlotId(ScreenHandler handler, ItemStack template) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()) {
                continue;
            }

            if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                return slotId;
            }
        }

        return -1;
    }

    ExtractResult moveMatchingItemsFromOpenShulker(ShulkerBoxScreenHandler handler,
                                                           List<MissingDemand> demands,
                                                           MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) {
            return new ExtractResult(0, demands, false);
        }

        int totalMoved = 0;
        boolean attemptedMove = false;
        List<MissingDemand> remainingDemands = demands;
        List<Integer> remainingSourceSlotIds = new ArrayList<>(getContainerSlotIds(handler));
        while (!remainingSourceSlotIds.isEmpty()) {
            if (remainingDemands.isEmpty()
                    || !hasPlayerStorageCapacityForAnyDemand(handler, remainingDemands)) {
                break;
            }

            int slotId = findBestSourceContainerSlotId(handler, remainingSourceSlotIds, remainingDemands);
            if (slotId == -1) {
                break;
            }
            remainingSourceSlotIds.remove(Integer.valueOf(slotId));

            Slot slot = handler.getSlot(slotId);
            MissingDemand demand = slot.hasStack() ? findDemandForStack(remainingDemands, slot.getStack()) : null;
            if (!slot.hasStack()
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.canTakeItems(client.player)
                    || demand == null) {
                continue;
            }

            ItemStack template = demand.template();
            if (!canStoreAnyStackInPlayerStorage(handler, slot.getStack())) {
                continue;
            }

            int before = countMatchingPlayerStorage(handler, template);
            List<Integer> playerSlotIds = new ArrayList<>(getPlayerStorageSlotIds(handler));
            playerSlotIds.sort(Comparator.comparing(playerSlotId -> !handler.getSlot(playerSlotId).hasStack()));
            for (int playerSlotId : playerSlotIds) {
                int remaining = demand.count() - (countMatchingPlayerStorage(handler, template) - before);
                if (remaining <= 0 || !slot.hasStack() || !handler.getCursorStack().isEmpty()) {
                    break;
                }

                Slot playerSlot = handler.getSlot(playerSlotId);
                if (!playerSlot.canInsert(template)
                        || playerSlot.hasStack() && !ItemStack.areItemsAndComponentsEqual(playerSlot.getStack(), template)) {
                    continue;
                }
                int capacity = Math.min(template.getMaxCount(), playerSlot.getMaxItemCount(template))
                        - (playerSlot.hasStack() ? playerSlot.getStack().getCount() : 0);
                if (capacity <= 0) {
                    continue;
                }

                // 限制为缺口和目标容量，拆分后的余量由现有转移逻辑放回原盒槽位。
                moveItemsToTargetSlot(handler, slotId, playerSlotId, Math.min(remaining, capacity), client);
                attemptedMove = true;
            }

            int moved = Math.max(0, countMatchingPlayerStorage(handler, template) - before);
            if (moved <= 0) {
                break;
            }

            totalMoved += moved;
            remainingDemands = subtractMissingDemand(remainingDemands, template, moved);
            if (!handler.getCursorStack().isEmpty()) {
                break;
            }
        }

        return new ExtractResult(totalMoved, remainingDemands, attemptedMove);
    }

    private int findReturnSlotId(ScreenHandler handler, ItemStack cursorStack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (canAcceptCursorStack(handler.getSlot(slotId), cursorStack)) {
                return slotId;
            }
        }

        return -1;
    }

    private boolean canAcceptCursorStack(Slot slot, ItemStack cursorStack) {
        if (!slot.isEnabled() || !slot.canInsert(cursorStack)) {
            return false;
        }

        if (!slot.hasStack()) {
            return true;
        }

        ItemStack existing = slot.getStack();
        return ItemStack.areItemsAndComponentsEqual(existing, cursorStack)
                && existing.getCount() + cursorStack.getCount() <= existing.getMaxCount();
    }

    private boolean canStoreStackInPlayerStorage(ScreenHandler handler, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isEnabled() || !slot.canInsert(stack)) {
                continue;
            }

            if (!slot.hasStack()) {
                remaining -= Math.min(stack.getMaxCount(), slot.getMaxItemCount(stack));
            } else if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)) {
                int maxCount = Math.min(slot.getStack().getMaxCount(), slot.getMaxItemCount(stack));
                remaining -= Math.max(0, maxCount - slot.getStack().getCount());
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private boolean hasPlayerStorageCapacityForAnyDemand(ScreenHandler handler, List<MissingDemand> demands) {
        for (MissingDemand demand : demands) {
            if (demand.count() > 0 && canStoreAnyStackInPlayerStorage(handler, demand.template())) {
                return true;
            }
        }

        return false;
    }

    private PrepareBatchResult preparePlayerStorageForBatchExtraction(ScreenHandler handler,
                                                                      List<Integer> containerSlotIds,
                                                                      List<MissingDemand> demands,
                                                                      MinecraftClient client) {
        if (continuousTask != null && continuousTask.batchFreeSlotsTarget == -1) {
            int limit = QuickCraftConfigs.getContainerFillFreeSlotsLimit();
            continuousTask.batchFreeSlotsTarget = limit == 0 ? 36 : limit;
        }

        int targetFreeSlots = continuousTask != null
                ? continuousTask.batchFreeSlotsTarget
                : Math.max(1, QuickCraftConfigs.getContainerFillFreeSlotsLimit());
        if (countEmptyPlayerStorageSlots(handler) >= targetFreeSlots) {
            return PrepareBatchResult.READY;
        }

        PrepareBatchResult freeResult = freePlayerStorageSlotsIntoQuickShulkers(handler, demands, targetFreeSlots, client);
        if (freeResult == PrepareBatchResult.WAIT) {
            return PrepareBatchResult.WAIT;
        }
        if (countEmptyPlayerStorageSlots(handler) > 0) {
            return PrepareBatchResult.READY;
        }

        if (continuousTask != null
                && continuousTask.temporaryStash == null
                && pendingStashRecovery == null
                && stashOnePlayerStackInEmptyContainerSlot(handler, containerSlotIds, client)) {
            return PrepareBatchResult.READY;
        }

        return countEmptyPlayerStorageSlots(handler) > 0 ? PrepareBatchResult.READY : PrepareBatchResult.BLOCKED;
    }

    private PrepareBatchResult freePlayerStorageSlotsIntoQuickShulkers(ScreenHandler handler,
                                                                       List<MissingDemand> demands,
                                                                       int targetFreeSlots,
                                                                       MinecraftClient client) {
        int emptySlots = countEmptyPlayerStorageSlots(handler);
        if (emptySlots >= targetFreeSlots) {
            return PrepareBatchResult.READY;
        }

        int candidateSlotId = findFreeablePlayerStorageSlotId(handler, demands);
        if (candidateSlotId == -1) {
            return emptySlots > 0 ? PrepareBatchResult.READY : PrepareBatchResult.BLOCKED;
        }

        if (!canRunQuickShulkerAction(continuousTask)) {
            return PrepareBatchResult.WAIT;
        }

        return packSlotIntoQuickShulker(handler, candidateSlotId, client)
                ? PrepareBatchResult.WAIT
                : (countEmptyPlayerStorageSlots(handler) > 0 ? PrepareBatchResult.READY : PrepareBatchResult.BLOCKED);
    }

    private int countEmptyPlayerStorageSlots(ScreenHandler handler) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.isEnabled() && !slot.hasStack()) {
                count++;
            }
        }
        return count;
    }

    private int findFreeablePlayerStorageSlotId(ScreenHandler handler, List<MissingDemand> demands) {
        boolean keptFireworkRocketStack = false;
        int bestSlotId = -1;
        int bestDestinationCount = Integer.MAX_VALUE;
        int bestDestinationCapacity = -1;
        int bestStackCount = Integer.MAX_VALUE;

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isOf(Items.FIREWORK_ROCKET) && !keptFireworkRocketStack) {
                keptFireworkRocketStack = true;
                continue;
            }
            if (!canUsePlayerStackToFreeQuickShulkerSpace(stack)
                    || findDemandForStack(demands, stack) != null
                    || !canStoreStackInQuickShulkers(handler, stack)) {
                continue;
            }

            int destinationCount = getQuickShulkerDestinationCount(handler, stack, -1);
            int destinationCapacity = getLargestQuickShulkerCapacity(handler, stack, -1);
            if (destinationCount < bestDestinationCount
                    || destinationCount == bestDestinationCount && destinationCapacity > bestDestinationCapacity
                    || destinationCount == bestDestinationCount
                    && destinationCapacity == bestDestinationCapacity
                    && stack.getCount() < bestStackCount) {
                bestSlotId = slotId;
                bestDestinationCount = destinationCount;
                bestDestinationCapacity = destinationCapacity;
                bestStackCount = stack.getCount();
            }
        }

        return bestSlotId;
    }

    private boolean stashOnePlayerStackInEmptyContainerSlot(ScreenHandler handler,
                                                           List<Integer> containerSlotIds,
                                                           MinecraftClient client) {
        int playerSlotId = findTemporaryStashPlayerSlotId(handler, client);
        if (playerSlotId == -1) {
            return false;
        }

        Slot playerSlot = handler.getSlot(playerSlotId);
        ItemStack stashedStack = playerSlot.getStack().copy();
        int containerSlotId = findTemporaryStashContainerSlotId(handler, containerSlotIds, stashedStack);
        if (containerSlotId == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                playerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        if (!handler.getCursorStack().isEmpty()) {
            returnCursorStack(handler, playerSlotId, client);
            return false;
        }

        continuousTask.temporaryStash = new TemporaryContainerStash(getContainerTemplateIndex(containerSlotIds, containerSlotId), containerSlotId, stashedStack);
        return !playerSlot.hasStack();
    }

    private int findTemporaryStashContainerSlotId(ScreenHandler handler, List<Integer> containerSlotIds, ItemStack stack) {
        int fallback = -1;
        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            int slotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(slotId);
            if (!slot.isEnabled()
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || slot.hasStack()
                    || !slot.canInsert(stack)) {
                continue;
            }
            if (recordedTemplate.slotTemplates.get(i).isEmpty()) {
                return slotId;
            }
            if (fallback == -1) {
                fallback = slotId;
            }
        }
        return fallback;
    }

    private int findTemporaryStashPlayerSlotId(ScreenHandler handler, MinecraftClient client) {
        int shulkerFallback = -1;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (isShulkerBox(stack)) {
                if (shulkerFallback == -1) {
                    shulkerFallback = slotId;
                }
                continue;
            }
            if (canUsePlayerStackToFreeQuickShulkerSpace(stack)) {
                return slotId;
            }
        }

        return shulkerFallback;
    }

    private boolean isTemporaryStashSlot(int slotId) {
        return continuousTask != null
                && continuousTask.temporaryStash != null
                && continuousTask.temporaryStash.slotId() == slotId;
    }

    private int getContainerTemplateIndex(List<Integer> containerSlotIds, int containerSlotId) {
        for (int i = 0; i < containerSlotIds.size(); i++) {
            if (containerSlotIds.get(i) == containerSlotId) {
                return i;
            }
        }
        return -1;
    }

    private boolean restoreTemporaryContainerStash(ScreenHandler handler,
                                                   List<Integer> containerSlotIds,
                                                   MinecraftClient client) {
        if (continuousTask == null || continuousTask.temporaryStash == null) {
            return true;
        }

        boolean restored = restoreTemporaryContainerStash(handler, containerSlotIds, client, continuousTask.temporaryStash);
        if (restored) {
            continuousTask.temporaryStash = null;
        }
        return restored;
    }

    private boolean restoreTemporaryContainerStash(ScreenHandler handler,
                                                   List<Integer> containerSlotIds,
                                                   MinecraftClient client,
                                                   TemporaryContainerStash stash) {
        if (stash.templateIndex() < 0 || stash.templateIndex() >= containerSlotIds.size()) {
            return false;
        }

        int containerSlotId = containerSlotIds.get(stash.templateIndex());
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasStack()) {
            return true;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), stash.stack())
                || slot.getStack().getCount() != stash.stack().getCount()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        returnCursorStack(handler, -1, client);
        if (!handler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    containerSlotId,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
            return false;
        }

        return true;
    }

    private boolean canStoreStackInQuickShulkers(ScreenHandler handler, ItemStack stack) {
        return canStoreStackInQuickShulkersExcept(handler, stack, -1);
    }

    private boolean canStoreStackInQuickShulkersExcept(ScreenHandler handler, ItemStack stack, int excludedSlotId) {
        if (stack.isEmpty() || isShulkerBox(stack)) {
            return false;
        }

        int remaining = stack.getCount();
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }

            remaining -= getShulkerCapacityFor(slot.getStack(), stack);
            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private boolean freePlayerStorageSlotIntoQuickShulker(ScreenHandler handler, MinecraftClient client) {
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }

        boolean keptFireworkRocketStack = false;
        for (int sourceSlotId : getPlayerStorageSlotIds(handler)) {
            Slot sourceSlot = handler.getSlot(sourceSlotId);
            if (!sourceSlot.hasStack()) {
                continue;
            }

            ItemStack sourceStack = sourceSlot.getStack();
            if (sourceStack.isOf(Items.FIREWORK_ROCKET) && !keptFireworkRocketStack) {
                keptFireworkRocketStack = true;
                continue;
            }
            if (!canUsePlayerStackToFreeQuickShulkerSpace(sourceStack)) {
                continue;
            }

            if (packSlotIntoQuickShulker(handler, sourceSlotId, client)) {
                return true;
            }
        }

        return false;
    }

    boolean canUsePlayerStackToFreeQuickShulkerSpace(ItemStack stack) {
        // 腾格子保护常用物，其它物品允许临时塞进潜影盒。
        if (stack.isEmpty()
                || isShulkerBox(stack)
                || isConfiguredProtectedFillItem(stack)) {
            return false;
        }

        return !isProtectedEnchantedDiamondOrNetheriteGear(stack);
    }

    private boolean isConfiguredProtectedFillItem(ItemStack stack) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        String fullId = itemId.toString();
        String pathId = itemId.getPath();
        String displayName = stack.getName().getString();

        for (String entry : QuickCraftConfigs.getContainerFillProtectedItems()) {
            String value = normalizeProtectedItemEntry(entry);
            if (value.isEmpty()) {
                continue;
            }

            if (value.equalsIgnoreCase(displayName)
                    || value.equalsIgnoreCase(fullId)
                    || value.equalsIgnoreCase(pathId)) {
                return true;
            }
        }

        return false;
    }

    private String normalizeProtectedItemEntry(String entry) {
        return entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isProtectedEnchantedDiamondOrNetheriteGear(ItemStack stack) {
        if (!stack.hasEnchantments()) {
            return false;
        }

        return isDiamondOrNetheriteItem(stack.getItem());
    }

    private boolean isDiamondOrNetheriteItem(Item item) {
        return item == Items.DIAMOND_SHOVEL
                || item == Items.DIAMOND_PICKAXE
                || item == Items.DIAMOND_AXE
                || item == Items.DIAMOND_HOE
                || item == Items.DIAMOND_SWORD
                || item == Items.DIAMOND_HELMET
                || item == Items.DIAMOND_CHESTPLATE
                || item == Items.DIAMOND_LEGGINGS
                || item == Items.DIAMOND_BOOTS
                || item == Items.NETHERITE_SHOVEL
                || item == Items.NETHERITE_PICKAXE
                || item == Items.NETHERITE_AXE
                || item == Items.NETHERITE_HOE
                || item == Items.NETHERITE_SWORD
                || item == Items.NETHERITE_HELMET
                || item == Items.NETHERITE_CHESTPLATE
                || item == Items.NETHERITE_LEGGINGS
                || item == Items.NETHERITE_BOOTS;
    }

    private boolean packSlotIntoQuickShulker(ScreenHandler handler, int sourceSlotId, MinecraftClient client) {
        return packSlotIntoQuickShulkerExcept(handler, sourceSlotId, -1, client);
    }

    boolean packSlotIntoQuickShulkerExcept(ScreenHandler handler,
                                                   int sourceSlotId,
                                                   int excludedShulkerSlotId,
                                                   MinecraftClient client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!sourceSlot.hasStack()
                || !sourceSlot.canTakeItems(client.player)
                || isShulkerBox(sourceSlot.getStack())
                || !canStoreStackInQuickShulkersExcept(handler, sourceSlot.getStack(), excludedShulkerSlotId)) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        int moved = packCursorIntoQuickShulkerExcept(handler, excludedShulkerSlotId, client);
        returnCursorStack(handler, sourceSlotId, client);
        return moved > 0 && !sourceSlot.hasStack() && handler.getCursorStack().isEmpty();
    }

    private int packCursorIntoQuickShulkerExcept(ScreenHandler handler, int excludedSlotId, MinecraftClient client) {
        if (handler.getCursorStack().isEmpty() || isShulkerBox(handler.getCursorStack())) {
            return 0;
        }

        int moved = 0;
        while (!handler.getCursorStack().isEmpty()) {
            int shulkerSlotId = findQuickShulkerDestinationSlotId(handler, handler.getCursorStack(), excludedSlotId);
            if (shulkerSlotId == -1) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            client.interactionManager.clickSlot(
                    handler.syncId,
                    shulkerSlotId,
                    1,
                    SlotActionType.PICKUP,
                    client.player
            );
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (isShulkerBox(handler.getCursorStack())
                    && !isShulkerBox(handler.getSlot(shulkerSlotId).getStack())) {
                // 未启用快盒装盒时，撤销原版交换，交由调用方归还光标物品。
                client.interactionManager.clickSlot(
                        handler.syncId, shulkerSlotId, 0, SlotActionType.PICKUP, client.player);
                break;
            }
            if (after >= before) {
                break;
            }
            moved += before - after;
        }

        return moved;
    }

    private int findQuickShulkerDestinationSlotId(ScreenHandler handler, ItemStack insertStack, int excludedSlotId) {
        if (insertStack.isEmpty() || isShulkerBox(insertStack)) {
            return -1;
        }

        int bestSlotId = -1;
        int bestCapacity = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }

            int capacity = getShulkerCapacityFor(slot.getStack(), insertStack);
            if (capacity > bestCapacity) {
                bestSlotId = slotId;
                bestCapacity = capacity;
            }
        }

        return bestSlotId;
    }

    private int getLargestQuickShulkerCapacity(ScreenHandler handler, ItemStack insertStack, int excludedSlotId) {
        int largestCapacity = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }

            largestCapacity = Math.max(largestCapacity, getShulkerCapacityFor(slot.getStack(), insertStack));
        }
        return largestCapacity;
    }

    private int getQuickShulkerDestinationCount(ScreenHandler handler, ItemStack insertStack, int excludedSlotId) {
        List<Integer> capacities = new ArrayList<>();
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }

            int capacity = getShulkerCapacityFor(slot.getStack(), insertStack);
            if (capacity > 0) {
                capacities.add(capacity);
            }
        }

        capacities.sort(Comparator.reverseOrder());
        int remaining = insertStack.getCount();
        for (int i = 0; i < capacities.size(); i++) {
            remaining -= capacities.get(i);
            if (remaining <= 0) {
                return i + 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    private boolean shouldUseQuickShulker() {
        return QuickCraftConfigs.isLitematicaContainerAutofillWithQuickShulkerEnabled()
                && QuickShulkerConnection.canBundle();
    }

    private boolean canUseQuickShulkerOpenPacket() {
        return QuickShulkerConnection.canOpen();
    }

    private boolean canRunQuickShulkerAction(ContinuousFillTask task) {
        if (task == null) {
            return true;
        }

        int interval = QuickCraftConfigs.getQuickShulkerActionIntervalTicks();
        if (interval <= 0) {
            return true;
        }
        if (task.quickShulkerActionCooldown > 0) {
            task.quickShulkerActionCooldown--;
            return false;
        }

        task.quickShulkerActionCooldown = Math.max(0, interval - 1);
        return true;
    }

    private boolean sendOpenQuickShulkerPacket(int slotId) {
        return QuickShulkerConnection.sendOpen(slotId);
    }

    private static SupportedContainerType getSupportedContainerType(MinecraftClient client, BlockHitResult blockHitResult) {
        if (client.world == null) {
            return null;
        }

        var blockState = client.world.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        if (block instanceof HopperBlock) {
            return SupportedContainerType.HOPPER;
        }
        if (block instanceof ChestBlock) {
            ChestType chestType = blockState.get(ChestBlock.CHEST_TYPE);
            return chestType == ChestType.SINGLE ? SupportedContainerType.SMALL_CHEST : SupportedContainerType.LARGE_CHEST;
        }
        if (block instanceof BarrelBlock) {
            return SupportedContainerType.BARREL;
        }
        if (block instanceof ShulkerBoxBlock) {
            return SupportedContainerType.SHULKER_BOX;
        }
        if (block instanceof DropperBlock) {
            return SupportedContainerType.DROPPER;
        }
        if (block instanceof DispenserBlock) {
            return SupportedContainerType.DISPENSER;
        }
        if (block instanceof CrafterBlock) {
            return SupportedContainerType.CRAFTER;
        }
        if (block instanceof FurnaceBlock) {
            return SupportedContainerType.FURNACE;
        }
        if (block instanceof BlastFurnaceBlock) {
            return SupportedContainerType.BLAST_FURNACE;
        }
        if (block instanceof SmokerBlock) {
            return SupportedContainerType.SMOKER;
        }
        if (block instanceof BrewingStandBlock) {
            return SupportedContainerType.BREWING_STAND;
        }

        return null;
    }

    public static PublicContainerType getPublicContainerType(MinecraftClient client, BlockHitResult blockHitResult) {
        SupportedContainerType type = getSupportedContainerType(client, blockHitResult);
        return type != null ? type.publicType : null;
    }

    public static PublicContainerType getPublicContainerType(Block block, ChestType chestType) {
        if (block instanceof HopperBlock) {
            return PublicContainerType.HOPPER;
        }
        if (block instanceof ChestBlock) {
            return chestType == ChestType.SINGLE ? PublicContainerType.SMALL_CHEST : PublicContainerType.LARGE_CHEST;
        }
        if (block instanceof BarrelBlock) {
            return PublicContainerType.BARREL;
        }
        if (block instanceof ShulkerBoxBlock) {
            return PublicContainerType.SHULKER_BOX;
        }
        if (block instanceof DropperBlock) {
            return PublicContainerType.DROPPER;
        }
        if (block instanceof DispenserBlock) {
            return PublicContainerType.DISPENSER;
        }
        if (block instanceof CrafterBlock) {
            return PublicContainerType.CRAFTER;
        }
        if (block instanceof FurnaceBlock) {
            return PublicContainerType.FURNACE;
        }
        if (block instanceof BlastFurnaceBlock) {
            return PublicContainerType.BLAST_FURNACE;
        }
        if (block instanceof SmokerBlock) {
            return PublicContainerType.SMOKER;
        }
        if (block instanceof BrewingStandBlock) {
            return PublicContainerType.BREWING_STAND;
        }
        return null;
    }

    private static SupportedContainerType getSupportedContainerType(MinecraftClient client, EntityHitResult entityHitResult) {
        return entityHitResult.getEntity() instanceof HopperMinecartEntity ? SupportedContainerType.HOPPER : null;
    }

    private static SupportedContainerType getSupportedContainerType(MinecraftClient client, HitResult hitResult) {
        if (hitResult instanceof BlockHitResult blockHitResult) {
            return getSupportedContainerType(client, blockHitResult);
        }
        if (hitResult instanceof EntityHitResult entityHitResult) {
            return getSupportedContainerType(client, entityHitResult);
        }
        return null;
    }

    private static boolean isSupportedHandlerForType(ScreenHandler handler, SupportedContainerType type) {
        return switch (type) {
            case HOPPER -> handler instanceof HopperScreenHandler;
            case SMALL_CHEST, BARREL -> handler instanceof GenericContainerScreenHandler genericHandler
                    && genericHandler.getRows() == 3;
            case LARGE_CHEST -> handler instanceof GenericContainerScreenHandler genericHandler
                    && genericHandler.getRows() == 6;
            case SHULKER_BOX -> handler instanceof ShulkerBoxScreenHandler;
            case DISPENSER, DROPPER -> handler instanceof Generic3x3ContainerScreenHandler;
            case CRAFTER -> handler instanceof CrafterScreenHandler;
            case FURNACE -> handler instanceof FurnaceScreenHandler;
            case BLAST_FURNACE -> handler instanceof BlastFurnaceScreenHandler;
            case SMOKER -> handler instanceof SmokerScreenHandler;
            case BREWING_STAND -> handler instanceof BrewingStandScreenHandler;
        };
    }

    private boolean isCrafterInputSlotDisabled(ScreenHandler handler, int slotId) {
        return handler instanceof CrafterScreenHandler crafterHandler && crafterHandler.isSlotDisabled(slotId);
    }

    private static void clearRecordedTemplate(MinecraftClient client) {
        recordedTemplate = null;
        sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.cache_cleared"));
    }

    private void closeCurrentScreen(MinecraftClient client) {
        clearLitematicaHandledScreenBinding();
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }

    private static void sendStatusMessage(MinecraftClient client, Text message) {
        if (client != null && client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private enum PendingAction {
        NONE,
        RECORD,
        APPLY
    }

    private enum ContinuousStage {
        OPEN_TARGET,
        WAIT_TARGET_SCREEN,
        FILL_TARGET,
        WAIT_SOURCE_SCREEN,
        EXTRACT_SOURCE,
        REOPEN_DELAY
    }

    private enum StashTargetRelation {
        SAME_CONTAINER,
        UNRELATED,
        UNCERTAIN
    }

    private static final class ContinuousFillTask {
        private final TargetInteraction target;
        private final ContinuousTemplate template;
        private final RegistryKey<World> worldKey;
        private final QuickContainerFillStatus.Handle fillStatus;
        private WeakReference<World> worldRef;
        private ContinuousStage stage = ContinuousStage.OPEN_TARGET;
        private int ticks;
        private int previousSyncId = -1;
        private int quickShulkerActionCooldown;
        private int batchFreeSlotsTarget = -1;
        private int currentSourcePlayerIndex = -1;
        private boolean openNextSourceBeforeExtract;
        private List<MissingDemand> missingDemands = List.of();
        private TemporaryContainerStash temporaryStash;

        private ContinuousFillTask(TargetInteraction target,
                                   ContinuousTemplate template,
                                   RegistryKey<World> worldKey,
                                   QuickContainerFillStatus.Handle fillStatus) {
            this.target = target;
            this.template = template;
            this.worldKey = worldKey;
            this.fillStatus = fillStatus;
        }
    }

    private record TargetInteraction(HitResult hitResult, SupportedContainerType type) {
        private TargetInteraction {
            if (hitResult instanceof BlockHitResult blockHitResult) {
                hitResult = blockHitResult.withBlockPos(blockHitResult.getBlockPos().toImmutable());
            }
        }
    }

    private record ContinuousTemplate(RecordedContainerTemplate recordedTemplate,
                                      boolean useQuickShulker,
                                      SuccessMessage successMessage) {
    }

    private record TemporaryContainerStash(int templateIndex, int slotId, ItemStack stack) {
    }

    private record PendingStashRecovery(TargetInteraction target,
                                        WeakReference<World> worldRef,
                                        TemporaryContainerStash stash) {
    }

    record ExtractResult(int moved, List<MissingDemand> remainingDemands, boolean attemptedMove) {
    }

    private record FillResult(List<Text> missingMessages,
                              List<Text> blockedMessages,
                              List<MissingDemand> missingDemands) {
        private static FillResult empty() {
            return new FillResult(List.of(), List.of(), List.of());
        }

        private static FillResult blocked(Text message) {
            return new FillResult(List.of(), List.of(message), List.of());
        }

        private boolean isComplete() {
            return missingMessages.isEmpty() && blockedMessages.isEmpty();
        }

        private int issueCount() {
            return missingMessages.size() + blockedMessages.size();
        }

        private Text message(SuccessMessage successMessage) {
            if (isComplete()) {
                return successMessage.text();
            }
            if (!missingMessages.isEmpty() && blockedMessages.isEmpty()) {
                return Text.translatable(
                        "quickcraft.message.container_copy.material_shortage_prefix",
                        joinTexts(missingMessages)
                );
            }
            if (missingMessages.isEmpty()) {
                return Text.translatable(
                        "quickcraft.message.container_copy.inventory_shortage_prefix",
                        joinTexts(blockedMessages)
                );
            }
            return Text.translatable(
                    "quickcraft.message.container_copy.both_shortages",
                    joinTexts(missingMessages),
                    joinTexts(blockedMessages)
            );
        }
    }

    private enum PrepareBatchResult {
        READY,
        WAIT,
        BLOCKED
    }

    /**
     * 类型按“实际容器种类”区分，避免不同槽位数的容器误套模板。
     */
    private enum SupportedContainerType {
        HOPPER(PublicContainerType.HOPPER, "quickcraft.container_type.hopper"),
        SMALL_CHEST(PublicContainerType.SMALL_CHEST, "quickcraft.container_type.small_chest"),
        LARGE_CHEST(PublicContainerType.LARGE_CHEST, "quickcraft.container_type.large_chest"),
        BARREL(PublicContainerType.BARREL, "quickcraft.container_type.barrel"),
        SHULKER_BOX(PublicContainerType.SHULKER_BOX, "quickcraft.container_type.shulker_box"),
        DISPENSER(PublicContainerType.DISPENSER, "quickcraft.container_type.dispenser"),
        DROPPER(PublicContainerType.DROPPER, "quickcraft.container_type.dropper"),
        CRAFTER(PublicContainerType.CRAFTER, "quickcraft.container_type.crafter"),
        FURNACE(PublicContainerType.FURNACE, "quickcraft.container_type.furnace"),
        BLAST_FURNACE(PublicContainerType.BLAST_FURNACE, "quickcraft.container_type.blast_furnace"),
        SMOKER(PublicContainerType.SMOKER, "quickcraft.container_type.smoker"),
        BREWING_STAND(PublicContainerType.BREWING_STAND, "quickcraft.container_type.brewing_stand");

        private final PublicContainerType publicType;
        private final String displayNameKey;

        SupportedContainerType(PublicContainerType publicType, String displayNameKey) {
            this.publicType = publicType;
            this.displayNameKey = displayNameKey;
        }

        private Text displayName() {
            return Text.translatable(displayNameKey);
        }

        private static SupportedContainerType fromPublicType(PublicContainerType publicType) {
            for (SupportedContainerType type : values()) {
                if (type.publicType == publicType) {
                    return type;
                }
            }

            return null;
        }
    }

    private static final class RecordedContainerTemplate {
        private final SupportedContainerType type;
        private final List<ItemStack> slotTemplates;
        private final List<Boolean> disabledStates;

        private RecordedContainerTemplate(SupportedContainerType type,
                                          List<ItemStack> slotTemplates,
                                          List<Boolean> disabledStates) {
            this.type = type;
            this.slotTemplates = slotTemplates;
            this.disabledStates = disabledStates;
        }
    }

    private record SuccessMessage(String translationKey, Object[] args) {
        private static SuccessMessage of(String translationKey, Object... args) {
            return new SuccessMessage(translationKey, args);
        }

        private Text text() {
            return Text.translatable(translationKey, args);
        }
    }

    private static List<ItemStack> copyTemplates(List<ItemStack> templates) {
        List<ItemStack> copies = new ArrayList<>(templates.size());
        for (ItemStack template : templates) {
            copies.add(template.isEmpty() ? ItemStack.EMPTY : template.copy());
        }
        return copies;
    }

    private static RecordedContainerTemplate copyRecordedTemplate(RecordedContainerTemplate template) {
        return new RecordedContainerTemplate(
                template.type,
                copyTemplates(template.slotTemplates),
                List.copyOf(template.disabledStates)
        );
    }

    public enum PublicContainerType {
        HOPPER,
        SMALL_CHEST,
        LARGE_CHEST,
        BARREL,
        SHULKER_BOX,
        DISPENSER,
        DROPPER,
        CRAFTER,
        FURNACE,
        BLAST_FURNACE,
        SMOKER,
        BREWING_STAND
    }

    public record TemplateSnapshot(
            PublicContainerType type,
            List<ItemStack> slotTemplates,
            List<Boolean> disabledStates,
            String replacementContextKey,
            String containerGroupKey
    ) {
        public TemplateSnapshot(
                PublicContainerType type,
                List<ItemStack> slotTemplates,
                List<Boolean> disabledStates
        ) {
            this(type, slotTemplates, disabledStates, "", "");
        }
    }
}
