package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
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
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 容器内容复制：
 * - 支持漏斗、漏斗矿车、箱子、木桶、潜影盒、发射器、投掷器、合成器、炉子和酿造台
 * - 先用热键记录一个模板
 * - 再对下一个同类容器右键，把背包里的对应材料按槽位顺序复制进去
 */
public final class QuickContainerCopy implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int BACKGROUND_ACTION_TIMEOUT_TICKS = 40;
    private static final int CONTINUOUS_REOPEN_DELAY_TICKS = 1;
    private static final int VANILLA_SHULKER_SLOTS = 27;
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.of("quickshulker", "quick_bundleheld_packet");
    private static final Identifier QUICK_SHULKER_OPEN_PACKET = Identifier.of("quickshulker", "open_shulker_packet");

    private static boolean lastUseDown;
    private static boolean lastContinuousFillDown;
    private static int pendingTicks;
    private static PendingAction pendingAction = PendingAction.NONE;
    private static SupportedContainerType pendingContainerType;
    private static RecordedContainerTemplate recordedTemplate;
    private static boolean allowQuickShulkerSources;
    private static ContinuousFillTask continuousTask;
    private static TargetInteraction suppressedContinuousTarget;
    private static boolean suppressContainerVerifierRemember;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
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
                                             boolean useQuickShulkerSources) {
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
            new QuickContainerCopy().applyTemplate(
                    client,
                    handler,
                    SuccessMessage.of("quickcraft.message.container_copy.projection_filled", type.displayName()),
                    true
            );
        } finally {
            recordedTemplate = previousTemplate;
            allowQuickShulkerSources = previousQuickShulkerSources;
        }
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickContainerCopyEnabled()
                && !QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            lastUseDown = false;
            lastContinuousFillDown = false;
            pendingAction = PendingAction.NONE;
            pendingContainerType = null;
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
        boolean fillDown = QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind().isKeybindHeld();
        if (!fillDown) {
            if (lastContinuousFillDown) {
                stopContinuousTask(client, true, null);
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
        if (suppressedContinuousTarget != null && targetMatches(suppressedContinuousTarget, hitResult, type)) {
            return;
        }

        ContinuousTemplate template = resolveContinuousTemplate(client, hitResult, type);
        if (template == null) {
            // 默认热键与原版使用键同为右键；没有匹配模板时必须静默放行普通容器交互。
            return;
        }

        pendingAction = PendingAction.NONE;
        pendingContainerType = null;
        pendingTicks = 0;
        suppressedContinuousTarget = null;
        continuousTask = new ContinuousFillTask(new TargetInteraction(hitResult, type), template);
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
            result = applyTemplate(client, handler, continuousTask.template.successMessage(), false);
        } finally {
            recordedTemplate = previousTemplate;
            allowQuickShulkerSources = previousQuickShulkerSources;
        }

        if (result.isComplete()) {
            if (continuousTask.temporaryStash != null
                    && !restoreTemporaryContainerStash(handler, containerSlotIds, client)) {
                stopContinuousTask(client, true, Text.translatable("quickcraft.message.container_copy.filled_but_stash_restore_failed"));
                return;
            }
            stopContinuousTask(client, true, continuousTask.template.successMessage().text());
            return;
        }

        if (!continuousTask.template.useQuickShulker()
                || result.missingDemands().isEmpty()
                || !canUseQuickShulkerOpenPacket()) {
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
        suppressedContinuousTarget = lastContinuousFillDown ? continuousTask.target : null;
        continuousTask.batchFreeSlotsTarget = -1;
        continuousTask = null;
        allowQuickShulkerSources = false;
        clearLitematicaHandledScreenBinding();
        if (closeScreen) {
            closeCurrentScreen(client);
        }
        if (message != null) {
            sendStatusMessage(client, message);
        }
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

    private boolean targetMatches(TargetInteraction target, HitResult hitResult, SupportedContainerType type) {
        if (target.type() != type) {
            return false;
        }
        if (target.hitResult() instanceof BlockHitResult targetBlock
                && hitResult instanceof BlockHitResult currentBlock) {
            return targetBlock.getBlockPos().equals(currentBlock.getBlockPos());
        }
        if (target.hitResult() instanceof EntityHitResult targetEntity
                && hitResult instanceof EntityHitResult currentEntity) {
            return targetEntity.getEntity().getId() == currentEntity.getEntity().getId();
        }
        return false;
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (pendingAction != PendingAction.NONE
                || !QuickCraftConfigs.isQuickContainerCopyEnabled()
                || recordedTemplate == null
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.useKey);
        if (useDown && !lastUseDown) {
            if (QuickMaterialCollector.shouldHandleCurrentTarget(client)
                    || QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)) {
                lastUseDown = true;
                return;
            }

            SupportedContainerType type = getSupportedContainerType(client, client.crosshairTarget);
            if (type == recordedTemplate.type) {
                pendingAction = PendingAction.APPLY;
                pendingContainerType = type;
                pendingTicks = 0;
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
                pendingAction = PendingAction.NONE;
                pendingContainerType = null;
                pendingTicks = 0;
            }
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        SupportedContainerType type = pendingContainerType;
        if (type == null || !isSupportedHandlerForType(handler, type)) {
            pendingAction = PendingAction.NONE;
            pendingContainerType = null;
            pendingTicks = 0;
            return;
        }

        PendingAction action = pendingAction;
        pendingAction = PendingAction.NONE;
        pendingContainerType = null;
        pendingTicks = 0;

        if (action == PendingAction.RECORD) {
            recordTemplate(client, handler, type);
            closeCurrentScreen(client);
            return;
        }

        if (recordedTemplate == null || recordedTemplate.type != type) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_copy.no_record_for_type", type.displayName()));
            closeCurrentScreen(client);
            return;
        }

        allowQuickShulkerSources = shouldUseQuickShulker();
        try {
            applyTemplate(
                    client,
                    handler,
                    SuccessMessage.of("quickcraft.message.container_copy.copied", recordedTemplate.type.displayName()),
                    true
            );
        } finally {
            allowQuickShulkerSources = false;
        }
        closeCurrentScreen(client);
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
                                     boolean showMessages) {
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

        client.interactionManager.clickSlot(
                handler.syncId,
                containerSlotId,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        if (handler.getSlot(containerSlotId).hasStack()
                && handler.getCursorStack().isEmpty()
                && allowQuickShulkerSources) {
            if (packSlotIntoQuickShulker(handler, containerSlotId, client)) {
                return true;
            }

            if (freePlayerStorageSlotIntoQuickShulker(handler, client)) {
                client.interactionManager.clickSlot(
                        handler.syncId,
                        containerSlotId,
                        0,
                        SlotActionType.QUICK_MOVE,
                        client.player
                );
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

    private int moveItemsToTargetSlot(ScreenHandler handler,
                                      int sourceSlotId,
                                      int targetSlotId,
                                      int neededCount,
                                      MinecraftClient client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!sourceSlot.hasStack()) {
            return 0;
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

        if (cursorStack.getCount() <= neededCount) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    targetSlotId,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        } else {
            for (int i = 0; i < neededCount; i++) {
                client.interactionManager.clickSlot(
                        handler.syncId,
                        targetSlotId,
                        1,
                        SlotActionType.PICKUP,
                        client.player
                );
            }
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

    private int findBestReplacementSourceSlotId(ScreenHandler handler, ItemStack template) {
        int neededCount = template.getCount();
        int bestUnderSlotId = -1;
        int bestUnderCount = 0;
        int bestOverSlotId = -1;
        int bestOverCount = Integer.MAX_VALUE;

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                continue;
            }

            int count = slot.getStack().getCount();
            if (count == neededCount) {
                return slotId;
            }
            if (count < neededCount && count > bestUnderCount) {
                bestUnderSlotId = slotId;
                bestUnderCount = count;
            }
            if (count > neededCount && count < bestOverCount) {
                bestOverSlotId = slotId;
                bestOverCount = count;
            }
        }

        return bestUnderSlotId != -1 ? bestUnderSlotId : bestOverSlotId;
    }

    private SourceShulker findSourceShulkerForDemandsExcept(ScreenHandler handler,
                                                            List<MissingDemand> demands,
                                                            int excludedPlayerIndex) {
        if (demands.isEmpty()) {
            return null;
        }

        for (int shulkerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot shulkerSlot = handler.getSlot(shulkerSlotId);
            if (shulkerSlot.getIndex() == excludedPlayerIndex) {
                continue;
            }

            if (!shulkerSlot.hasStack()
                    || shulkerSlot.getStack().getCount() != 1
                    || !isShulkerBox(shulkerSlot.getStack())
                    || !containsStoredDemand(shulkerSlot.getStack(), demands)) {
                continue;
            }

            return new SourceShulker(shulkerSlotId, shulkerSlot.getIndex());
        }

        return null;
    }

    private boolean containsStoredDemand(ItemStack shulker, List<MissingDemand> demands) {
        for (ItemStack stack : getStoredStacksBySlot(shulker)) {
            if (findDemandForStack(demands, stack) != null) {
                return true;
            }
        }

        return false;
    }

    private ExtractResult moveMatchingItemsFromOpenShulker(ShulkerBoxScreenHandler handler,
                                                           List<MissingDemand> demands,
                                                           MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) {
            return new ExtractResult(0, demands, false);
        }

        int totalMoved = 0;
        boolean attemptedMove = false;
        List<MissingDemand> remainingDemands = demands;
        for (int slotId : getContainerSlotIds(handler)) {
            if (remainingDemands.isEmpty()
                    || !hasPlayerStorageCapacityForAnyDemand(handler, remainingDemands)) {
                break;
            }

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
            // 整组优先取货；多出来的材料留在背包，后续腾格子时会作为普通无关物品处理。
            client.interactionManager.clickSlot(
                    handler.syncId,
                    slotId,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            attemptedMove = true;

            int moved = Math.max(0, countMatchingPlayerStorage(handler, template) - before);
            if (moved <= 0) {
                break;
            }

            totalMoved += moved;
            remainingDemands = subtractMissingDemand(remainingDemands, template, moved);
        }

        return new ExtractResult(totalMoved, remainingDemands, attemptedMove);
    }

    private MissingDemand findDemandForStack(List<MissingDemand> demands, ItemStack stack) {
        for (MissingDemand demand : demands) {
            if (demand.count() > 0 && ItemStack.areItemsAndComponentsEqual(stack, demand.template())) {
                return demand;
            }
        }

        return null;
    }

    private int countMatchingPlayerStorage(ScreenHandler handler, ItemStack template) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasStack() && ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                count += slot.getStack().getCount();
            }
        }
        return count;
    }

    private List<MissingDemand> subtractMissingDemand(List<MissingDemand> demands, ItemStack template, int moved) {
        List<MissingDemand> remaining = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            if (!ItemStack.areItemsAndComponentsEqual(demand.template(), template)) {
                remaining.add(demand);
                continue;
            }

            int count = Math.max(0, demand.count() - moved);
            if (count > 0) {
                remaining.add(new MissingDemand(demand.template(), count));
            }
        }
        return remaining;
    }

    private List<MissingDemand> copyMissingDemands(List<MissingDemand> demands) {
        List<MissingDemand> copies = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            copies.add(new MissingDemand(demand.template().copy(), demand.count()));
        }
        return copies;
    }

    private DefaultedList<ItemStack> getStoredStacksBySlot(ItemStack shulker) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(VANILLA_SHULKER_SLOTS, ItemStack.EMPTY);
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        container.copyTo(stacks);
        return stacks;
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

    private boolean canStoreAnyStackInPlayerStorage(ScreenHandler handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isEnabled() || !slot.canInsert(stack)) {
                continue;
            }

            if (!slot.hasStack()) {
                return true;
            }

            ItemStack existing = slot.getStack();
            if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxCount(), slot.getMaxItemCount(stack))) {
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
        if (countEmptyPlayerStorageSlots(handler) > 0) {
            if (continuousTask == null || continuousTask.batchFreeSlotsTarget == -1) {
                return PrepareBatchResult.READY;
            }
            if (countEmptyPlayerStorageSlots(handler) >= continuousTask.batchFreeSlotsTarget) {
                return PrepareBatchResult.READY;
            }
        }

        if (continuousTask != null && continuousTask.batchFreeSlotsTarget == -1) {
            int limit = QuickCraftConfigs.getContainerFillFreeSlotsLimit();
            continuousTask.batchFreeSlotsTarget = limit == 0 ? 36 : limit;
        }

        int targetFreeSlots = continuousTask != null
                ? continuousTask.batchFreeSlotsTarget
                : Math.max(1, QuickCraftConfigs.getContainerFillFreeSlotsLimit());

        PrepareBatchResult freeResult = freePlayerStorageSlotsIntoQuickShulkers(handler, demands, targetFreeSlots, client);
        if (freeResult == PrepareBatchResult.WAIT) {
            return PrepareBatchResult.WAIT;
        }
        if (countEmptyPlayerStorageSlots(handler) > 0) {
            return PrepareBatchResult.READY;
        }

        if (continuousTask != null && continuousTask.temporaryStash == null
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
                    || isTemplateRelatedStack(stack)
                    || findDemandForStack(demands, stack) != null
                    || !canStoreStackInQuickShulkers(handler, stack)) {
                continue;
            }

            return slotId;
        }

        return -1;
    }

    private boolean isTemplateRelatedStack(ItemStack stack) {
        if (recordedTemplate == null || stack.isEmpty()) {
            return false;
        }

        for (ItemStack template : recordedTemplate.slotTemplates) {
            if (!template.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }

        return false;
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
            if (canUsePlayerStackToFreeQuickShulkerSpace(stack) && !isTemplateRelatedStack(stack)) {
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

        TemporaryContainerStash stash = continuousTask.temporaryStash;
        if (stash.templateIndex() < 0 || stash.templateIndex() >= containerSlotIds.size()) {
            return false;
        }

        int containerSlotId = containerSlotIds.get(stash.templateIndex());
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasStack()) {
            continuousTask.temporaryStash = null;
            return true;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), stash.stack())) {
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
            continuousTask.temporaryStash = null;
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

        continuousTask.temporaryStash = null;
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

    private boolean canUsePlayerStackToFreeQuickShulkerSpace(ItemStack stack) {
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

    private boolean packSlotIntoQuickShulkerExcept(ScreenHandler handler,
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

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }

            if (getShulkerCapacityFor(slot.getStack(), insertStack) > 0) {
                return slotId;
            }
        }

        return -1;
    }

    private int getShulkerCapacityFor(ItemStack shulker, ItemStack insertStack) {
        if (!isShulkerBox(shulker) || insertStack.isEmpty() || isShulkerBox(insertStack)) {
            return 0;
        }

        int usedSlots = 0;
        int capacity = 0;
        for (ItemStack stored : getStoredStacksBySlot(shulker)) {
            if (stored.isEmpty()) {
                continue;
            }

            usedSlots++;
            if (ItemStack.areItemsAndComponentsEqual(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }

        return capacity + Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots) * insertStack.getMaxCount();
    }

    private boolean shouldUseQuickShulker() {
        if (!QuickCraftConfigs.isLitematicaContainerAutofillWithQuickShulkerEnabled()
                || !FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_BUNDLE_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean canUseQuickShulkerOpenPacket() {
        if (!FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_OPEN_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
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
        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            Object packet = packetClass.getConstructor(int.class).newInstance(slotId);
            ClientPlayNetworking.send((CustomPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return false;
        }
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

    private List<Integer> getContainerSlotIds(ScreenHandler handler) {
        if (handler instanceof AbstractFurnaceScreenHandler
                || handler instanceof BrewingStandScreenHandler) {
            return getContainerSlotIdsByInventoryIndex(handler);
        }
        if (handler instanceof CrafterScreenHandler crafterHandler) {
            List<Slot> crafterSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (!isVisibleSlot(slot) || slot.inventory != crafterHandler.getInputInventory()) {
                    continue;
                }
                crafterSlots.add(slot);
            }

            crafterSlots.sort(Comparator
                    .comparingInt((Slot slot) -> slot.y)
                    .thenComparingInt(slot -> slot.x)
                    .thenComparingInt(slot -> slot.id));

            return crafterSlots.stream()
                    .map(slot -> slot.id)
                    .toList();
        }

        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id));

        return containerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    private List<Integer> getContainerSlotIdsByInventoryIndex(ScreenHandler handler) {
        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));

        return containerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    private List<Integer> getPlayerStorageSlotIds(ScreenHandler handler) {
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || !isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            playerSlots.add(slot);
        }

        playerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));

        return playerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private boolean isCrafterInputSlotDisabled(ScreenHandler handler, int slotId) {
        return handler instanceof CrafterScreenHandler crafterHandler && crafterHandler.isSlotDisabled(slotId);
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
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

    private static final class ContinuousFillTask {
        private final TargetInteraction target;
        private final ContinuousTemplate template;
        private ContinuousStage stage = ContinuousStage.OPEN_TARGET;
        private int ticks;
        private int previousSyncId = -1;
        private int quickShulkerActionCooldown;
        private int batchFreeSlotsTarget = -1;
        private int currentSourcePlayerIndex = -1;
        private boolean openNextSourceBeforeExtract;
        private List<MissingDemand> missingDemands = List.of();
        private TemporaryContainerStash temporaryStash;

        private ContinuousFillTask(TargetInteraction target, ContinuousTemplate template) {
            this.target = target;
            this.template = template;
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

    private record SourceShulker(int slotId, int playerIndex) {
    }

    private record MissingDemand(ItemStack template, int count) {
    }

    private record TemporaryContainerStash(int templateIndex, int slotId, ItemStack stack) {
    }

    private record ExtractResult(int moved, List<MissingDemand> remainingDemands, boolean attemptedMove) {
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
            List<Boolean> disabledStates
    ) {
    }

}
