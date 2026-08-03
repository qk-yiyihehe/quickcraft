package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.core.NonNullList;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

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
    private static final Identifier QUICK_SHULKER_BUNDLE_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "quick_bundleheld_packet");
    private static final Identifier QUICK_SHULKER_OPEN_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "open_shulker_packet");

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

    public static boolean handleRecordHotkey(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || client.screen != null
                || !QuickCraftConfigs.isQuickContainerCopyEnabled()) {
            return false;
        }

        HitResult hitResult = client.hitResult;
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
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHitResult);
        } else if (hitResult instanceof EntityHitResult entityHitResult) {
            client.gameMode.interact(client.player, entityHitResult.getEntity(), entityHitResult, InteractionHand.MAIN_HAND);
        } else {
            return false;
        }
        sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.recording", type.displayName()));
        return true;
    }

    public static boolean canApplyTemplateSnapshot(AbstractContainerMenu handler, TemplateSnapshot snapshot) {
        SupportedContainerType type = SupportedContainerType.fromPublicType(snapshot.type());
        return type != null && isSupportedHandlerForType(handler, type);
    }

    public static void applyTemplateSnapshot(Minecraft client,
                                             AbstractContainerMenu handler,
                                             TemplateSnapshot snapshot,
                                             boolean useQuickShulkerSources) {
        SupportedContainerType type = SupportedContainerType.fromPublicType(snapshot.type());
        if (type == null || !isSupportedHandlerForType(handler, type)) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.projection_type_mismatch"));
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

    private void onClientTick(Minecraft client) {
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

    private void handleContinuousFillHotkey(Minecraft client) {
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

    private void tryStartContinuousTask(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || client.screen != null) {
            return;
        }

        HitResult hitResult = client.hitResult;
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
            if (!lastContinuousFillDown) {
                sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.no_fill_template"));
            }
            return;
        }

        pendingAction = PendingAction.NONE;
        pendingContainerType = null;
        pendingTicks = 0;
        suppressedContinuousTarget = null;
        continuousTask = new ContinuousFillTask(new TargetInteraction(hitResult, type), template);
        sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.background_start", type.displayName()));
    }

    public static boolean canHandleContinuousContainerFillHotkey(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || client.screen != null) {
            return continuousTask != null;
        }

        if (!QuickCraftConfigs.isQuickContainerCopyEnabled()
                && !QuickCraftConfigs.isLitematicaContainerAutofillEnabled()) {
            return continuousTask != null;
        }

        HitResult hitResult = client.hitResult;
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

    private ContinuousTemplate resolveContinuousTemplate(Minecraft client, HitResult hitResult, SupportedContainerType type) {
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

    private void processContinuousTask(Minecraft client) {
        if (continuousTask == null) {
            return;
        }
        if (client == null || client.player == null || client.gameMode == null) {
            stopContinuousTask(client, false, null);
            return;
        }
        if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
            stopContinuousTask(client, false, Component.translatable("quickcraft.message.container_copy.background_screen_open"));
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

    private void openContinuousTarget(Minecraft client) {
        if (continuousTask == null) {
            return;
        }
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler != null && isSupportedHandlerForType(handler, continuousTask.target.type())) {
            continuousTask.stage = ContinuousStage.FILL_TARGET;
            continuousTask.ticks = 0;
            return;
        }
        if (client.screen != null) {
            stopContinuousTask(client, false, Component.translatable("quickcraft.message.container_copy.background_screen_open"));
            return;
        }

        if (!openTarget(client, continuousTask.target)) {
            stopContinuousTask(client, false, Component.translatable("quickcraft.message.container_copy.target_open_failed"));
            return;
        }
        continuousTask.stage = ContinuousStage.WAIT_TARGET_SCREEN;
        continuousTask.ticks = 0;
    }

    private void waitForContinuousTarget(Minecraft client) {
        if (continuousTask == null) {
            return;
        }
        continuousTask.ticks++;
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler == null) {
            if (continuousTask.ticks > OPEN_TIMEOUT_TICKS) {
                stopContinuousTask(client, false, Component.translatable("quickcraft.message.container_copy.target_open_timeout"));
            }
            return;
        }

        if (!isSupportedHandlerForType(handler, continuousTask.target.type())) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.template_type_mismatch"));
            return;
        }

        continuousTask.stage = ContinuousStage.FILL_TARGET;
        continuousTask.ticks = 0;
    }

    private void fillContinuousTarget(Minecraft client) {
        if (continuousTask == null) {
            return;
        }

        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler == null) {
            continuousTask.stage = ContinuousStage.OPEN_TARGET;
            return;
        }

        if (!isSupportedHandlerForType(handler, continuousTask.target.type())) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.template_type_mismatch"));
            return;
        }

        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        if (continuousTask.temporaryStash != null
                && countEmptyPlayerStorageSlots(handler) > 0
                && !restoreTemporaryContainerStash(handler, containerSlotIds, client)) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.temporary_stash_restore_failed"));
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
                stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.filled_but_stash_restore_failed"));
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
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.shulker_batch_no_space"));
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
        int currentSyncId = handler.containerId;
        if (!sendOpenQuickShulkerPacket(source.slotId())) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.quick_shulker_open_failed"));
            return;
        }

        continuousTask.batchFreeSlotsTarget = -1;
        continuousTask.missingDemands = copyMissingDemands(result.missingDemands());
        continuousTask.currentSourcePlayerIndex = source.playerIndex();
        continuousTask.previousSyncId = currentSyncId;
        continuousTask.stage = ContinuousStage.WAIT_SOURCE_SCREEN;
        continuousTask.ticks = 0;
    }

    private void waitForSourceShulker(Minecraft client) {
        if (continuousTask == null) {
            return;
        }
        continuousTask.ticks++;
        AbstractContainerMenu handler = getOpenHandledContainer(client);
        if (handler == null) {
            if (continuousTask.ticks > BACKGROUND_ACTION_TIMEOUT_TICKS) {
                stopContinuousTask(client, false, Component.translatable("quickcraft.message.container_copy.quick_shulker_open_timeout"));
            }
            return;
        }

        if (handler instanceof ShulkerBoxMenu
                && handler.containerId != continuousTask.previousSyncId) {
            continuousTask.stage = ContinuousStage.EXTRACT_SOURCE;
            continuousTask.ticks = 0;
            return;
        }

        if (continuousTask.ticks > BACKGROUND_ACTION_TIMEOUT_TICKS) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.quick_shulker_open_timeout"));
        }
    }

    private void extractFromSourceShulker(Minecraft client) {
        if (continuousTask == null || continuousTask.missingDemands.isEmpty()) {
            stopContinuousTask(client, true, null);
            return;
        }

        AbstractContainerMenu currentHandler = getOpenHandledContainer(client);
        if (!(currentHandler instanceof ShulkerBoxMenu handler)) {
            stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.quick_shulker_screen_invalid"));
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

    private void openNextSourceShulkerOrReopenTarget(Minecraft client, ShulkerBoxMenu handler) {
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
                int currentSyncId = handler.containerId;
                if (!sendOpenQuickShulkerPacket(nextSource.slotId())) {
                    stopContinuousTask(client, true, Component.translatable("quickcraft.message.container_copy.quick_shulker_open_failed"));
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

    private boolean openTarget(Minecraft client, TargetInteraction target) {
        HitResult hitResult = target.hitResult();
        if (hitResult instanceof BlockHitResult blockHitResult) {
            suppressContainerVerifierRemember = true;
            try {
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHitResult);
            } finally {
                suppressContainerVerifierRemember = false;
            }
            return true;
        }
        if (hitResult instanceof EntityHitResult entityHitResult) {
            client.gameMode.interact(client.player, entityHitResult.getEntity(), entityHitResult, InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void stopContinuousTask(Minecraft client, boolean closeScreen, Component message) {
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
     * 连续填充期间只保留后台 AbstractContainerMenu，不真正把容器界面切到前台。
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

    private AbstractContainerMenu getOpenHandledContainer(Minecraft client) {
        if (client == null || client.player == null) {
            return null;
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            return screen.getMenu();
        }

        AbstractContainerMenu handler = client.player.containerMenu;
        if (handler == null || handler == client.player.inventoryMenu || handler.containerId == 0) {
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

    private void handleUseAttempt(Minecraft client) {
        if (pendingAction != PendingAction.NONE
                || !QuickCraftConfigs.isQuickContainerCopyEnabled()
                || recordedTemplate == null
                || client.player == null
                || client.level == null
                || client.screen != null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown) {
            if (QuickMaterialCollector.shouldHandleCurrentTarget(client)
                    || QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)) {
                lastUseDown = true;
                return;
            }

            SupportedContainerType type = getSupportedContainerType(client, client.hitResult);
            if (type == recordedTemplate.type) {
                pendingAction = PendingAction.APPLY;
                pendingContainerType = type;
                pendingTicks = 0;
            }
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (pendingAction == PendingAction.NONE) {
            return;
        }

        pendingTicks++;
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                pendingAction = PendingAction.NONE;
                pendingContainerType = null;
                pendingTicks = 0;
            }
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
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
            sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.no_record_for_type", type.displayName()));
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

    private void recordTemplate(Minecraft client, AbstractContainerMenu handler, SupportedContainerType type) {
        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        List<ItemStack> templates = new ArrayList<>(containerSlotIds.size());
        List<Boolean> disabledStates = new ArrayList<>(containerSlotIds.size());

        for (int slotId : containerSlotIds) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(isCrafterInputSlotDisabled(handler, slotId));
        }

        recordedTemplate = new RecordedContainerTemplate(type, templates, disabledStates);
        sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.recorded", type.displayName()));
    }

    private FillResult applyTemplate(Minecraft client,
                                     AbstractContainerMenu handler,
                                     SuccessMessage successMessage,
                                     boolean showMessages) {
        List<Component> missingMessages = new ArrayList<>();
        List<Component> blockedMessages = new ArrayList<>();
        List<MissingDemand> missingDemands = new ArrayList<>();

        if (client.player == null || client.gameMode == null || recordedTemplate == null) {
            return FillResult.empty();
        }

        if (!handler.getCarried().isEmpty()) {
            FillResult result = FillResult.blocked(Component.translatable("quickcraft.message.container_copy.cursor_stack_blocked"));
            if (showMessages) {
                sendStatusMessage(client, result.message(successMessage));
            }
            return result;
        }

        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        applyCrafterSlotStates(handler, containerSlotIds, client);
        if (client.player.getAbilities().instabuild && QuickCraftConfigs.isCreativeContainerFillEnabled()) {
            FillResult result = applyCreativeTemplate(client, handler, containerSlotIds);
            if (showMessages) {
                Component message = result.isComplete()
                        ? successMessage.text()
                        : Component.translatable(
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
            if (template.isEmpty()) {
                if (slot.hasItem()) {
                    wrongSlotIndexes.add(i);
                }
                continue;
            }

            ItemStack currentStack = slot.getItem();
            if (!currentStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentStack, template)) {
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
                missingMessages.add(Component.translatable(
                        "quickcraft.message.container_copy.slot_missing_item",
                        i + 1,
                        missingCount,
                        template.getHoverName()
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
            int extraCount = slot.getItem().getCount() - template.getCount();
            if (extraCount <= 0) {
                continue;
            }

            if (!trimMatchingContainerSlot(handler, containerSlotId, template, client)) {
                blockedMessages.add(Component.translatable(
                        "quickcraft.message.container_copy.slot_extra_item",
                        index + 1,
                        extraCount,
                        template.getHoverName()
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
            if (template.isEmpty()) {
                continue;
            }

            int missingCount = normalizeNonEmptyTemplateSlot(handler, containerSlotIds, index, template, client);
            if (missingCount > 0) {
                missingMessages.add(Component.translatable(
                        "quickcraft.message.container_copy.slot_missing_item",
                        index + 1,
                        missingCount,
                        template.getHoverName()
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
            if (!slot.hasItem()
                    || !ItemStack.isSameItemSameComponents(slot.getItem(), template)
                    || slot.getItem().getCount() <= template.getCount()) {
                continue;
            }

            int extraCount = slot.getItem().getCount() - template.getCount();
            if (!trimMatchingContainerSlot(handler, containerSlotId, template, client)) {
                blockedMessages.add(Component.translatable(
                        "quickcraft.message.container_copy.slot_extra_item",
                        i + 1,
                        extraCount,
                        template.getHoverName()
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
            if (!tryMoveSlotToPlayerStorage(handler, containerSlotId, client)) {
                blockedMessages.add(Component.translatable("quickcraft.message.container_copy.slot_cannot_clear", index + 1));
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
            if (ItemStack.isSameItemSameComponents(demand.template(), template)) {
                missingDemands.set(i, new MissingDemand(demand.template(), demand.count() + count));
                return;
            }
        }

        ItemStack demandTemplate = template.copy();
        demandTemplate.setCount(1);
        missingDemands.add(new MissingDemand(demandTemplate, count));
    }

    private FillResult applyCreativeTemplate(Minecraft client,
                                             AbstractContainerMenu handler,
                                             List<Integer> containerSlotIds) {
        List<Component> blockedMessages = new ArrayList<>();
        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            int containerSlotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(containerSlotId);
            ItemStack template = recordedTemplate.slotTemplates.get(i);
            if (slotMatchesTemplate(slot, template)) {
                continue;
            }
            if (!slot.isActive()) {
                blockedMessages.add(Component.translatable("quickcraft.message.container_copy.slot_cannot_fill", i + 1));
                continue;
            }

            boolean applied = template.isEmpty()
                    ? clearContainerSlotInCreative(handler, containerSlotId, client)
                    : setContainerSlotInCreative(handler, containerSlotId, template, client);
            if (!applied) {
                blockedMessages.add(Component.translatable("quickcraft.message.container_copy.slot_cannot_fill", i + 1));
            }
        }

        return new FillResult(List.of(), blockedMessages, List.of());
    }

    private static Component joinTexts(List<Component> texts) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                joined.append(Component.translatable("quickcraft.message.separator"));
            }
            joined.append(texts.get(i));
        }
        return joined;
    }

    private boolean slotMatchesTemplate(Slot slot, ItemStack template) {
        ItemStack currentStack = slot.getItem();
        if (template.isEmpty()) {
            return currentStack.isEmpty();
        }

        return !currentStack.isEmpty()
                && ItemStack.isSameItemSameComponents(currentStack, template)
                && currentStack.getCount() == template.getCount();
    }

    private boolean setContainerSlotInCreative(AbstractContainerMenu handler,
                                               int containerSlotId,
                                               ItemStack template,
                                               Minecraft client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isActive()
                || !targetSlot.mayPlace(template)
                || template.getCount() > Math.min(template.getMaxStackSize(), targetSlot.getMaxStackSize(template))) {
            return false;
        }

        if (targetSlot.hasItem() && !clearContainerSlotInCreative(handler, containerSlotId, client)) {
            return false;
        }

        int scratchSlotId = findCreativeScratchSlotId(handler);
        if (scratchSlotId == -1) {
            return false;
        }

        Slot scratchSlot = handler.getSlot(scratchSlotId);
        ItemStack originalScratchStack = scratchSlot.getItem().copy();
        setCreativePlayerSlot(handler, scratchSlotId, template.copy(), client);

        client.gameMode.handleContainerInput(
                handler.containerId,
                scratchSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (!handler.getCarried().isEmpty()) {
            returnCursorStack(handler, scratchSlotId, client);
        }
        setCreativePlayerSlot(handler, scratchSlotId, originalScratchStack, client);
        return handler.getCarried().isEmpty()
                && targetSlot.hasItem()
                && ItemStack.isSameItemSameComponents(targetSlot.getItem(), template)
                && targetSlot.getItem().getCount() == template.getCount();
    }

    private boolean clearContainerSlotInCreative(AbstractContainerMenu handler, int containerSlotId, Minecraft client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.hasItem()) {
            return true;
        }
        if (!targetSlot.mayPickup(client.player)) {
            return false;
        }

        int scratchSlotId = findCreativeScratchSlotId(handler);
        if (scratchSlotId == -1) {
            return false;
        }

        Slot scratchSlot = handler.getSlot(scratchSlotId);
        ItemStack originalScratchStack = scratchSlot.getItem().copy();
        setCreativePlayerSlot(handler, scratchSlotId, ItemStack.EMPTY, client);

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        returnCursorStack(handler, scratchSlotId, client);
        setCreativePlayerSlot(handler, scratchSlotId, originalScratchStack, client);
        return handler.getCarried().isEmpty() && !targetSlot.hasItem();
    }

    private int findCreativeScratchSlotId(AbstractContainerMenu handler) {
        int fallbackSlotId = -1;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isActive()) {
                continue;
            }
            if (!slot.hasItem()) {
                return slotId;
            }
            if (fallbackSlotId == -1) {
                fallbackSlotId = slotId;
            }
        }

        return fallbackSlotId;
    }

    private void setCreativePlayerSlot(AbstractContainerMenu handler, int slotId, ItemStack stack, Minecraft client) {
        int creativeSlotId = getCreativePlayerScreenSlotId(handler.getSlot(slotId));
        if (creativeSlotId == -1) {
            return;
        }

        ItemStack stackCopy = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        client.gameMode.handleCreativeModeItemAdd(stackCopy, creativeSlotId);
        handler.getSlot(slotId).set(stackCopy.copy());
    }

    private int getCreativePlayerScreenSlotId(Slot slot) {
        if (!isPlayerStorageSlot(slot)) {
            return -1;
        }

        int index = slot.getContainerSlot();
        return index < 9 ? 36 + index : index;
    }

    private void applyCrafterSlotStates(AbstractContainerMenu handler, List<Integer> containerSlotIds, Minecraft client) {
        if (!(handler instanceof CrafterMenu crafterHandler)
                || client.gameMode == null
                || recordedTemplate == null) {
            return;
        }

        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.disabledStates.size(); i++) {
            int slotId = containerSlotIds.get(i);
            boolean shouldBeEnabled = !recordedTemplate.disabledStates.get(i);
            boolean isEnabled = !crafterHandler.isSlotDisabled(slotId);
            if (isEnabled == shouldBeEnabled) {
                continue;
            }

            crafterHandler.setSlotState(slotId, shouldBeEnabled);
            client.gameMode.handleSlotStateChanged(slotId, handler.containerId, shouldBeEnabled);
        }
    }

    private int fillContainerSlot(AbstractContainerMenu handler,
                                  int containerSlotId,
                                  ItemStack template,
                                  int neededCount,
                                  Minecraft client) {
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isActive()
                || !targetSlot.mayPlace(template)) {
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

    private int normalizeNonEmptyTemplateSlot(AbstractContainerMenu handler,
                                              List<Integer> containerSlotIds,
                                              int templateIndex,
                                              ItemStack template,
                                              Minecraft client) {
        int containerSlotId = containerSlotIds.get(templateIndex);
        Slot targetSlot = handler.getSlot(containerSlotId);
        if (!targetSlot.isActive()
                || !targetSlot.mayPlace(template)
                || !handler.getCarried().isEmpty()) {
            return template.getCount();
        }

        ItemStack targetStack = targetSlot.getItem();
        if (!targetStack.isEmpty() && ItemStack.isSameItemSameComponents(targetStack, template)) {
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

        if (!targetSlot.mayPickup(client.player)) {
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

    private int fillContainerSlotFromContainer(AbstractContainerMenu handler,
                                               List<Integer> containerSlotIds,
                                               int targetTemplateIndex,
                                               ItemStack template,
                                               int neededCount,
                                               Minecraft client) {
        int targetSlotId = containerSlotIds.get(targetTemplateIndex);
        Slot targetSlot = handler.getSlot(targetSlotId);
        if (!targetSlot.isActive() || !targetSlot.mayPlace(template)) {
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

    private int findMatchingWrongContainerSlotId(AbstractContainerMenu handler,
                                                 List<Integer> containerSlotIds,
                                                 int targetTemplateIndex,
                                                 ItemStack template) {
        Minecraft client = Minecraft.getInstance();
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
            if (!slot.hasItem()
                    || !slot.mayPickup(client.player)
                    || !ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                continue;
            }

            if (expected.isEmpty() || !ItemStack.isSameItemSameComponents(slot.getItem(), expected)) {
                return slotId;
            }
        }

        return -1;
    }

    private boolean swapContainerSlots(AbstractContainerMenu handler, int sourceSlotId, int targetSlotId, Minecraft client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        Slot targetSlot = handler.getSlot(targetSlotId);
        if (!sourceSlot.hasItem()
                || !sourceSlot.mayPickup(client.player)
                || !targetSlot.mayPlace(sourceSlot.getItem())
                || (targetSlot.hasItem() && !targetSlot.mayPickup(client.player))) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                targetSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (!handler.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    sourceSlotId,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        }

        return handler.getCarried().isEmpty();
    }

    private boolean swapPlayerStackIntoContainerSlot(AbstractContainerMenu handler,
                                                     int sourceSlotId,
                                                     int containerSlotId,
                                                     Minecraft client) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        returnCursorStack(handler, sourceSlotId, client);
        return handler.getCarried().isEmpty();
    }

    private int topUpMatchingContainerSlot(AbstractContainerMenu handler,
                                           int containerSlotId,
                                           ItemStack template,
                                           Minecraft client) {
        ItemStack currentStack = handler.getSlot(containerSlotId).getItem();
        if (currentStack.isEmpty() || !ItemStack.isSameItemSameComponents(currentStack, template)) {
            return template.getCount();
        }

        int neededCount = Math.max(0, template.getCount() - currentStack.getCount());
        return neededCount == 0 ? 0 : fillContainerSlot(handler, containerSlotId, template, neededCount, client);
    }

    private boolean tryMoveSlotToPlayerStorage(AbstractContainerMenu handler, int containerSlotId, Minecraft client) {
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasItem() || !slot.mayPickup(client.player)) {
            return !slot.hasItem();
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );

        if (handler.getSlot(containerSlotId).hasItem()
                && handler.getCarried().isEmpty()
                && allowQuickShulkerSources) {
            if (packSlotIntoQuickShulker(handler, containerSlotId, client)) {
                return true;
            }

            if (freePlayerStorageSlotIntoQuickShulker(handler, client)) {
                client.gameMode.handleContainerInput(
                        handler.containerId,
                        containerSlotId,
                        0,
                        ContainerInput.QUICK_MOVE,
                        client.player
                );
            }
        }

        if (handler.getSlot(containerSlotId).hasItem()
                && handler.getCarried().isEmpty()
                && !isTemporaryStashSlot(containerSlotId)
                && QuickCraftConfigs.isContainerFillOverflowDropEnabled()) {
            return dropContainerSlot(handler, containerSlotId, client);
        }

        return !handler.getSlot(containerSlotId).hasItem() && handler.getCarried().isEmpty();
    }

    private boolean trimMatchingContainerSlot(AbstractContainerMenu handler,
                                              int containerSlotId,
                                              ItemStack template,
                                              Minecraft client) {
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasItem()
                || !slot.mayPickup(client.player)
                || !ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
            return false;
        }

        int extraCount = slot.getItem().getCount() - template.getCount();
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

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (handler.getCarried().isEmpty()) {
            return false;
        }

        for (int i = 0; i < template.getCount(); i++) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    containerSlotId,
                    1,
                    ContainerInput.PICKUP,
                    client.player
            );
        }

        if (!handler.getCarried().isEmpty()) {
            if (canReturnExtra) {
                returnCursorStack(handler, findReturnSlotId(handler, handler.getCarried()), client);
            }
            if (!handler.getCarried().isEmpty() && allowOverflowDrop) {
                dropCursorStack(handler, client);
            }
        }
        return handler.getCarried().isEmpty()
                && handler.getSlot(containerSlotId).hasItem()
                && handler.getSlot(containerSlotId).getItem().getCount() == template.getCount();
    }

    private boolean dropContainerSlot(AbstractContainerMenu handler, int slotId, Minecraft client) {
        Slot slot = handler.getSlot(slotId);
        if (!slot.hasItem() || !slot.mayPickup(client.player) || !handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                1,
                ContainerInput.THROW,
                client.player
        );
        return !handler.getSlot(slotId).hasItem();
    }

    private void dropCursorStack(AbstractContainerMenu handler, Minecraft client) {
        if (handler.getCarried().isEmpty()) {
            return;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                -999,
                0,
                ContainerInput.PICKUP,
                client.player
        );
    }

    private int moveItemsToTargetSlot(AbstractContainerMenu handler,
                                      int sourceSlotId,
                                      int targetSlotId,
                                      int neededCount,
                                      Minecraft client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!sourceSlot.hasItem()) {
            return 0;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        ItemStack cursorStack = handler.getCarried();
        if (cursorStack.isEmpty()) {
            return 0;
        }

        Slot targetSlot = handler.getSlot(targetSlotId);
        int beforeCount = targetSlot.hasItem() ? targetSlot.getItem().getCount() : 0;

        if (cursorStack.getCount() <= neededCount) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    targetSlotId,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        } else {
            for (int i = 0; i < neededCount; i++) {
                client.gameMode.handleContainerInput(
                        handler.containerId,
                        targetSlotId,
                        1,
                        ContainerInput.PICKUP,
                        client.player
                );
            }
            returnCursorStack(handler, sourceSlotId, client);
        }

        int afterCount = targetSlot.hasItem() ? targetSlot.getItem().getCount() : 0;
        return Math.max(0, afterCount - beforeCount);
    }

    private void returnCursorStack(AbstractContainerMenu handler, int preferredSlotId, Minecraft client) {
        if (handler.getCarried().isEmpty()) {
            return;
        }

        int targetSlotId = preferredSlotId >= 0
                && preferredSlotId < handler.slots.size()
                && canAcceptCursorStack(handler.getSlot(preferredSlotId), handler.getCarried())
                ? preferredSlotId
                : findReturnSlotId(handler, handler.getCarried());

        if (targetSlotId != -1) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    targetSlotId,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            return;
        }

        if (allowQuickShulkerSources) {
            packCursorIntoQuickShulkerExcept(handler, -1, client);
        }
    }

    private int findMatchingPlayerSlotId(AbstractContainerMenu handler, ItemStack template) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem()) {
                continue;
            }

            if (ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                return slotId;
            }
        }

        return -1;
    }

    private int findBestReplacementSourceSlotId(AbstractContainerMenu handler, ItemStack template) {
        int neededCount = template.getCount();
        int bestUnderSlotId = -1;
        int bestUnderCount = 0;
        int bestOverSlotId = -1;
        int bestOverCount = Integer.MAX_VALUE;

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || !ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                continue;
            }

            int count = slot.getItem().getCount();
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

    private SourceShulker findSourceShulkerForDemandsExcept(AbstractContainerMenu handler,
                                                            List<MissingDemand> demands,
                                                            int excludedPlayerIndex) {
        if (demands.isEmpty()) {
            return null;
        }

        for (int shulkerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot shulkerSlot = handler.getSlot(shulkerSlotId);
            if (shulkerSlot.getContainerSlot() == excludedPlayerIndex) {
                continue;
            }

            if (!shulkerSlot.hasItem()
                    || shulkerSlot.getItem().getCount() != 1
                    || !isShulkerBox(shulkerSlot.getItem())
                    || !containsStoredDemand(shulkerSlot.getItem(), demands)) {
                continue;
            }

            return new SourceShulker(shulkerSlotId, shulkerSlot.getContainerSlot());
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

    private ExtractResult moveMatchingItemsFromOpenShulker(ShulkerBoxMenu handler,
                                                           List<MissingDemand> demands,
                                                           Minecraft client) {
        if (client.player == null || client.gameMode == null || !handler.getCarried().isEmpty()) {
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
            MissingDemand demand = slot.hasItem() ? findDemandForStack(remainingDemands, slot.getItem()) : null;
            if (!slot.hasItem()
                    || !slot.mayPickup(client.player)
                    || demand == null) {
                continue;
            }

            ItemStack template = demand.template();
            if (!canStoreAnyStackInPlayerStorage(handler, slot.getItem())) {
                continue;
            }

            int before = countMatchingPlayerStorage(handler, template);
            // 整组优先取货；多出来的材料留在背包，后续腾格子时会作为普通无关物品处理。
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    slotId,
                    0,
                    ContainerInput.QUICK_MOVE,
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
            if (demand.count() > 0 && ItemStack.isSameItemSameComponents(stack, demand.template())) {
                return demand;
            }
        }

        return null;
    }

    private int countMatchingPlayerStorage(AbstractContainerMenu handler, ItemStack template) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    private List<MissingDemand> subtractMissingDemand(List<MissingDemand> demands, ItemStack template, int moved) {
        List<MissingDemand> remaining = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            if (!ItemStack.isSameItemSameComponents(demand.template(), template)) {
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

    private NonNullList<ItemStack> getStoredStacksBySlot(ItemStack shulker) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(VANILLA_SHULKER_SLOTS, ItemStack.EMPTY);
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        container.copyInto(stacks);
        return stacks;
    }

    private int findReturnSlotId(AbstractContainerMenu handler, ItemStack cursorStack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (canAcceptCursorStack(handler.getSlot(slotId), cursorStack)) {
                return slotId;
            }
        }

        return -1;
    }

    private boolean canAcceptCursorStack(Slot slot, ItemStack cursorStack) {
        if (!slot.isActive() || !slot.mayPlace(cursorStack)) {
            return false;
        }

        if (!slot.hasItem()) {
            return true;
        }

        ItemStack existing = slot.getItem();
        return ItemStack.isSameItemSameComponents(existing, cursorStack)
                && existing.getCount() + cursorStack.getCount() <= existing.getMaxStackSize();
    }

    private boolean canStoreStackInPlayerStorage(AbstractContainerMenu handler, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isActive() || !slot.mayPlace(stack)) {
                continue;
            }

            if (!slot.hasItem()) {
                remaining -= Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
            } else if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
                int maxCount = Math.min(slot.getItem().getMaxStackSize(), slot.getMaxStackSize(stack));
                remaining -= Math.max(0, maxCount - slot.getItem().getCount());
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private boolean canStoreAnyStackInPlayerStorage(AbstractContainerMenu handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isActive() || !slot.mayPlace(stack)) {
                continue;
            }

            if (!slot.hasItem()) {
                return true;
            }

            ItemStack existing = slot.getItem();
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(stack))) {
                return true;
            }
        }

        return false;
    }

    private boolean hasPlayerStorageCapacityForAnyDemand(AbstractContainerMenu handler, List<MissingDemand> demands) {
        for (MissingDemand demand : demands) {
            if (demand.count() > 0 && canStoreAnyStackInPlayerStorage(handler, demand.template())) {
                return true;
            }
        }

        return false;
    }

    private PrepareBatchResult preparePlayerStorageForBatchExtraction(AbstractContainerMenu handler,
                                                                      List<Integer> containerSlotIds,
                                                                      List<MissingDemand> demands,
                                                                      Minecraft client) {
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

    private PrepareBatchResult freePlayerStorageSlotsIntoQuickShulkers(AbstractContainerMenu handler,
                                                                       List<MissingDemand> demands,
                                                                       int targetFreeSlots,
                                                                       Minecraft client) {
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

    private int countEmptyPlayerStorageSlots(AbstractContainerMenu handler) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.isActive() && !slot.hasItem()) {
                count++;
            }
        }
        return count;
    }

    private int findFreeablePlayerStorageSlotId(AbstractContainerMenu handler, List<MissingDemand> demands) {
        boolean keptFireworkRocketStack = false;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.is(Items.FIREWORK_ROCKET) && !keptFireworkRocketStack) {
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
            if (!template.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                return true;
            }
        }

        return false;
    }

    private boolean stashOnePlayerStackInEmptyContainerSlot(AbstractContainerMenu handler,
                                                           List<Integer> containerSlotIds,
                                                           Minecraft client) {
        int playerSlotId = findTemporaryStashPlayerSlotId(handler, client);
        if (playerSlotId == -1) {
            return false;
        }

        Slot playerSlot = handler.getSlot(playerSlotId);
        ItemStack stashedStack = playerSlot.getItem().copy();
        int containerSlotId = findTemporaryStashContainerSlotId(handler, containerSlotIds, stashedStack);
        if (containerSlotId == -1) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                playerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        if (!handler.getCarried().isEmpty()) {
            returnCursorStack(handler, playerSlotId, client);
            return false;
        }

        continuousTask.temporaryStash = new TemporaryContainerStash(getContainerTemplateIndex(containerSlotIds, containerSlotId), containerSlotId, stashedStack);
        return !playerSlot.hasItem();
    }

    private int findTemporaryStashContainerSlotId(AbstractContainerMenu handler, List<Integer> containerSlotIds, ItemStack stack) {
        int fallback = -1;
        for (int i = 0; i < containerSlotIds.size() && i < recordedTemplate.slotTemplates.size(); i++) {
            int slotId = containerSlotIds.get(i);
            Slot slot = handler.getSlot(slotId);
            if (!slot.isActive() || slot.hasItem() || !slot.mayPlace(stack)) {
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

    private int findTemporaryStashPlayerSlotId(AbstractContainerMenu handler, Minecraft client) {
        int shulkerFallback = -1;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || !slot.mayPickup(client.player)) {
                continue;
            }

            ItemStack stack = slot.getItem();
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

    private boolean restoreTemporaryContainerStash(AbstractContainerMenu handler,
                                                   List<Integer> containerSlotIds,
                                                   Minecraft client) {
        if (continuousTask == null || continuousTask.temporaryStash == null) {
            return true;
        }

        TemporaryContainerStash stash = continuousTask.temporaryStash;
        if (stash.templateIndex() < 0 || stash.templateIndex() >= containerSlotIds.size()) {
            return false;
        }

        int containerSlotId = containerSlotIds.get(stash.templateIndex());
        Slot slot = handler.getSlot(containerSlotId);
        if (!slot.hasItem()) {
            continuousTask.temporaryStash = null;
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(slot.getItem(), stash.stack())) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                containerSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            continuousTask.temporaryStash = null;
            return true;
        }

        returnCursorStack(handler, -1, client);
        if (!handler.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    containerSlotId,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            return false;
        }

        continuousTask.temporaryStash = null;
        return true;
    }

    private boolean canStoreStackInQuickShulkers(AbstractContainerMenu handler, ItemStack stack) {
        return canStoreStackInQuickShulkersExcept(handler, stack, -1);
    }

    private boolean canStoreStackInQuickShulkersExcept(AbstractContainerMenu handler, ItemStack stack, int excludedSlotId) {
        if (stack.isEmpty() || isShulkerBox(stack)) {
            return false;
        }

        int remaining = stack.getCount();
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || slot.getItem().getCount() != 1 || !isShulkerBox(slot.getItem())) {
                continue;
            }

            remaining -= getShulkerCapacityFor(slot.getItem(), stack);
            if (remaining <= 0) {
                return true;
            }
        }

        return false;
    }

    private boolean freePlayerStorageSlotIntoQuickShulker(AbstractContainerMenu handler, Minecraft client) {
        if (!handler.getCarried().isEmpty()) {
            return false;
        }

        boolean keptFireworkRocketStack = false;
        for (int sourceSlotId : getPlayerStorageSlotIds(handler)) {
            Slot sourceSlot = handler.getSlot(sourceSlotId);
            if (!sourceSlot.hasItem()) {
                continue;
            }

            ItemStack sourceStack = sourceSlot.getItem();
            if (sourceStack.is(Items.FIREWORK_ROCKET) && !keptFireworkRocketStack) {
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
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String fullId = itemId.toString();
        String pathId = itemId.getPath();
        String displayName = stack.getHoverName().getString();

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
        if (!stack.isEnchanted()) {
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

    private boolean packSlotIntoQuickShulker(AbstractContainerMenu handler, int sourceSlotId, Minecraft client) {
        return packSlotIntoQuickShulkerExcept(handler, sourceSlotId, -1, client);
    }

    private boolean packSlotIntoQuickShulkerExcept(AbstractContainerMenu handler,
                                                   int sourceSlotId,
                                                   int excludedShulkerSlotId,
                                                   Minecraft client) {
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!sourceSlot.hasItem()
                || !sourceSlot.mayPickup(client.player)
                || isShulkerBox(sourceSlot.getItem())
                || !canStoreStackInQuickShulkersExcept(handler, sourceSlot.getItem(), excludedShulkerSlotId)) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlotId,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        int moved = packCursorIntoQuickShulkerExcept(handler, excludedShulkerSlotId, client);
        returnCursorStack(handler, sourceSlotId, client);
        return moved > 0 && !sourceSlot.hasItem() && handler.getCarried().isEmpty();
    }

    private int packCursorIntoQuickShulkerExcept(AbstractContainerMenu handler, int excludedSlotId, Minecraft client) {
        if (handler.getCarried().isEmpty() || isShulkerBox(handler.getCarried())) {
            return 0;
        }

        int moved = 0;
        while (!handler.getCarried().isEmpty()) {
            int shulkerSlotId = findQuickShulkerDestinationSlotId(handler, handler.getCarried(), excludedSlotId);
            if (shulkerSlotId == -1) {
                break;
            }

            int before = handler.getCarried().getCount();
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    shulkerSlotId,
                    1,
                    ContainerInput.PICKUP,
                    client.player
            );
            int after = handler.getCarried().isEmpty() ? 0 : handler.getCarried().getCount();
            if (after >= before) {
                break;
            }
            moved += before - after;
        }

        return moved;
    }

    private int findQuickShulkerDestinationSlotId(AbstractContainerMenu handler, ItemStack insertStack, int excludedSlotId) {
        if (insertStack.isEmpty() || isShulkerBox(insertStack)) {
            return -1;
        }

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            if (slotId == excludedSlotId) {
                continue;
            }

            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || slot.getItem().getCount() != 1 || !isShulkerBox(slot.getItem())) {
                continue;
            }

            if (getShulkerCapacityFor(slot.getItem(), insertStack) > 0) {
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
            if (ItemStack.isSameItemSameComponents(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }

        return capacity + Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots) * insertStack.getMaxStackSize();
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
            ClientPlayNetworking.send((CustomPacketPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return false;
        }
    }

    private static SupportedContainerType getSupportedContainerType(Minecraft client, BlockHitResult blockHitResult) {
        if (client.level == null) {
            return null;
        }

        var blockState = client.level.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        if (block instanceof HopperBlock) {
            return SupportedContainerType.HOPPER;
        }
        if (block instanceof ChestBlock) {
            ChestType chestType = blockState.getValue(ChestBlock.TYPE);
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

    public static PublicContainerType getPublicContainerType(Minecraft client, BlockHitResult blockHitResult) {
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

    private static SupportedContainerType getSupportedContainerType(Minecraft client, EntityHitResult entityHitResult) {
        return entityHitResult.getEntity() instanceof MinecartHopper ? SupportedContainerType.HOPPER : null;
    }

    private static SupportedContainerType getSupportedContainerType(Minecraft client, HitResult hitResult) {
        if (hitResult instanceof BlockHitResult blockHitResult) {
            return getSupportedContainerType(client, blockHitResult);
        }
        if (hitResult instanceof EntityHitResult entityHitResult) {
            return getSupportedContainerType(client, entityHitResult);
        }
        return null;
    }

    private static boolean isSupportedHandlerForType(AbstractContainerMenu handler, SupportedContainerType type) {
        return switch (type) {
            case HOPPER -> handler instanceof HopperMenu;
            case SMALL_CHEST, BARREL -> handler instanceof ChestMenu genericHandler
                    && genericHandler.getRowCount() == 3;
            case LARGE_CHEST -> handler instanceof ChestMenu genericHandler
                    && genericHandler.getRowCount() == 6;
            case SHULKER_BOX -> handler instanceof ShulkerBoxMenu;
            case DISPENSER, DROPPER -> handler instanceof DispenserMenu;
            case CRAFTER -> handler instanceof CrafterMenu;
            case FURNACE -> handler instanceof FurnaceMenu;
            case BLAST_FURNACE -> handler instanceof BlastFurnaceMenu;
            case SMOKER -> handler instanceof SmokerMenu;
            case BREWING_STAND -> handler instanceof BrewingStandMenu;
        };
    }

    private List<Integer> getContainerSlotIds(AbstractContainerMenu handler) {
        if (handler instanceof AbstractFurnaceMenu
                || handler instanceof BrewingStandMenu) {
            return getContainerSlotIdsByInventoryIndex(handler);
        }
        if (handler instanceof CrafterMenu crafterHandler) {
            List<Slot> crafterSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (!isVisibleSlot(slot) || slot.container != crafterHandler.getContainer()) {
                    continue;
                }
                crafterSlots.add(slot);
            }

            crafterSlots.sort(Comparator
                    .comparingInt((Slot slot) -> slot.y)
                    .thenComparingInt(slot -> slot.x)
                    .thenComparingInt(slot -> slot.index));

            return crafterSlots.stream()
                    .map(slot -> slot.index)
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
                .thenComparingInt(slot -> slot.index));

        return containerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private List<Integer> getContainerSlotIdsByInventoryIndex(AbstractContainerMenu handler) {
        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return containerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private List<Integer> getPlayerStorageSlotIds(AbstractContainerMenu handler) {
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || !isPlayerStorageSlot(slot)) {
                continue;
            }
            playerSlots.add(slot);
        }

        playerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return playerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory
                && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private boolean isCrafterInputSlotDisabled(AbstractContainerMenu handler, int slotId) {
        return handler instanceof CrafterMenu crafterHandler && crafterHandler.isSlotDisabled(slotId);
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static void clearRecordedTemplate(Minecraft client) {
        recordedTemplate = null;
        sendStatusMessage(client, Component.translatable("quickcraft.message.container_copy.cache_cleared"));
    }

    private void closeCurrentScreen(Minecraft client) {
        clearLitematicaHandledScreenBinding();
        if (client.player != null) {
            client.player.closeContainer();
        }
    }

    private static void sendStatusMessage(Minecraft client, Component message) {
        if (client != null && client.player != null) {
            client.player.sendOverlayMessage(message);
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
                hitResult = blockHitResult.withPosition(blockHitResult.getBlockPos().immutable());
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

    private record FillResult(List<Component> missingMessages,
                              List<Component> blockedMessages,
                              List<MissingDemand> missingDemands) {
        private static FillResult empty() {
            return new FillResult(List.of(), List.of(), List.of());
        }

        private static FillResult blocked(Component message) {
            return new FillResult(List.of(), List.of(message), List.of());
        }

        private boolean isComplete() {
            return missingMessages.isEmpty() && blockedMessages.isEmpty();
        }

        private Component message(SuccessMessage successMessage) {
            if (isComplete()) {
                return successMessage.text();
            }
            if (!missingMessages.isEmpty() && blockedMessages.isEmpty()) {
                return Component.translatable(
                        "quickcraft.message.container_copy.material_shortage_prefix",
                        joinTexts(missingMessages)
                );
            }
            if (missingMessages.isEmpty()) {
                return Component.translatable(
                        "quickcraft.message.container_copy.inventory_shortage_prefix",
                        joinTexts(blockedMessages)
                );
            }
            return Component.translatable(
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

        private Component displayName() {
            return Component.translatable(displayNameKey);
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

        private Component text() {
            return Component.translatable(translationKey, args);
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
