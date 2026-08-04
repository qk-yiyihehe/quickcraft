package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 自动激活信标：
 * - 开启待命后，对着信标右键
 * - 按配置顺序寻找玩家当前缺少的 II 级信标效果
 * - 自动使用背包矿物支付，不够时尝试用背包 2x2 合成栏拆一块矿物块
 * - 发送原版更新信标数据包后自动关闭界面
 */
public final class QuickBeacon implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int BEACON_PAYMENT_SLOT_ID = 0;
    private static final int PLAYER_CRAFT_RESULT_SLOT_ID = 0;
    private static final int PLAYER_CRAFT_INPUT_SLOT_ID = 1;
    private static final int MAX_EFFECT_ORDER_SIZE = 6;
    private static final int CRAFT_RESULT_WAIT_TICKS = 5;
    private static final int CRAFT_RESULT_TAKE_ATTEMPTS = 2;
    private static final int RECENT_ASSIGNMENT_TTL_TICKS = 100;
    private static final int MIN_ACTIVE_EFFECT_DURATION_TICKS = 13 * 20;
    private static final Map<BlockPos, RecentBeaconAssignment> RECENT_BEACON_ASSIGNMENTS = new HashMap<>();

    private static boolean lastUseDown;
    private static int pendingTicks;
    private static BlockPos pendingBeaconPos;
    private static BlockHitResult pendingBeaconHitResult;
    private static BeaconEffectTarget pendingTarget;
    private static PendingFailureReason pendingFailureReason = PendingFailureReason.NONE;
    private static PendingStage pendingStage = PendingStage.NONE;
    private static Item pendingDecomposePaymentItem;
    private static int pendingDecomposeTakeAttempts;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isQuickBeaconEnabled()) {
            clearPendingState();
        }

        pruneRecentAssignments(client);
        handleUseAttempt(client);
        processPendingState(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (!QuickCraftConfigs.isQuickBeaconEnabled() || client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.keyUse);
        BlockHitResult beaconHitResult = getLookedAtBeaconHitResult(client);
        if (useDown && !lastUseDown && client.gui.screen() == null && beaconHitResult != null) {
            pendingBeaconPos = beaconHitResult.getBlockPos().immutable();
            pendingBeaconHitResult = beaconHitResult;
            pendingTarget = null;
            pendingFailureReason = PendingFailureReason.NONE;
            pendingStage = PendingStage.WAIT_INITIAL_OPEN;
            pendingTicks = 0;
        }

        lastUseDown = useDown;
    }

    private void processPendingState(Minecraft client) {
        if (pendingStage == PendingStage.NONE) {
            return;
        }

        pendingTicks++;
        switch (pendingStage) {
            case WAIT_INITIAL_OPEN, WAIT_REOPEN -> processBeaconOpenState(client);
            case DECOMPOSE_START -> processDecomposeStart(client);
            case DECOMPOSE_WAIT_RESULT -> processDecomposeWaitResult(client);
            case DECOMPOSE_TAKE_RESULT -> processDecomposeTakeResult(client);
            case NONE -> {
            }
        }
    }

    private void processBeaconOpenState(Minecraft client) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        if (!(screen.getMenu() instanceof BeaconMenu handler)) {
            clearPendingState();
            return;
        }

        pendingTicks = 0;

        BeaconConfiguredState configuredState = getConfirmedConfiguredState(handler);
        if (configuredState != null) {
            sendConfiguredStateMessage(client, configuredState);
            closeCurrentScreen(client);
            clearPendingState();
            return;
        }

        if (pendingTarget == null) {
            preparePendingTarget(client);
        }

        if (pendingFailureReason != PendingFailureReason.NONE || pendingTarget == null) {
            sendStatusMessage(client, Component.translatable(pendingFailureReason.translationKey));
            closeCurrentScreen(client);
            clearPendingState();
            return;
        }

        if (!insertPaymentIntoBeacon(handler, client)) {
            if (client.player != null && hasPaymentBlock(client.player.inventoryMenu)) {
                closeCurrentScreen(client);
                pendingStage = PendingStage.DECOMPOSE_START;
                pendingTicks = 0;
                return;
            }

            sendStatusMessage(client, Component.translatable("quickcraft.message.beacon.no_payment"));
            closeCurrentScreen(client);
            clearPendingState();
            return;
        }

        sendBeaconPacket(client, pendingTarget);
        closeCurrentScreen(client);
        clearPendingState();
    }

    private void processDecomposeStart(Minecraft client) {
        if (!isPlayerCraftingHandlerReady(client)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        String translationKey = beginPendingDecompose(client);
        if (translationKey != null) {
            sendStatusMessage(client, Component.translatable(translationKey));
            clearPendingState();
            return;
        }

        pendingStage = PendingStage.DECOMPOSE_WAIT_RESULT;
        pendingTicks = 0;
    }

    private void processDecomposeWaitResult(Minecraft client) {
        if (!isPlayerCraftingHandlerReady(client) || client.player == null) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        InventoryMenu handler = client.player.inventoryMenu;
        ItemStack resultStack = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getItem();
        if (pendingDecomposePaymentItem != null && resultStack.is(pendingDecomposePaymentItem)) {
            pendingDecomposeTakeAttempts = 0;
            pendingStage = PendingStage.DECOMPOSE_TAKE_RESULT;
            pendingTicks = 0;
            return;
        }

        if (pendingTicks > CRAFT_RESULT_WAIT_TICKS) {
            clearCraftingGrid(handler, client);
            sendStatusMessage(client, Component.translatable("quickcraft.message.beacon.no_payment"));
            clearPendingState();
        }
    }

    private void processDecomposeTakeResult(Minecraft client) {
        if (!isPlayerCraftingHandlerReady(client) || client.player == null) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        InventoryMenu handler = client.player.inventoryMenu;
        if (tryTakeCraftResult(handler, client)) {
            if (client.gameMode == null || pendingBeaconHitResult == null) {
                clearPendingState();
                return;
            }

            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, pendingBeaconHitResult);
            pendingStage = PendingStage.WAIT_REOPEN;
            pendingTicks = 0;
            return;
        }

        pendingDecomposeTakeAttempts++;
        if (pendingDecomposeTakeAttempts >= CRAFT_RESULT_TAKE_ATTEMPTS) {
            clearCraftingGrid(handler, client);
            sendStatusMessage(client, Component.translatable("quickcraft.message.beacon.no_room_for_decompose"));
            clearPendingState();
            return;
        }

        pendingTicks = 0;
    }

    private String beginPendingDecompose(Minecraft client) {
        if (client.player == null || client.gameMode == null) {
            return "quickcraft.message.beacon.no_payment";
        }

        InventoryMenu handler = client.player.inventoryMenu;
        if (isPlayerCraftingBusy(handler)) {
            return "quickcraft.message.beacon.crafting_busy";
        }

        for (PaymentMaterial material : PaymentMaterial.values()) {
            int sourceSlotId = findPlayerItemSlotId(handler, material.blockItem);
            if (sourceSlotId == -1) {
                continue;
            }

            clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP, client);
            if (handler.getCarried().isEmpty()) {
                return "quickcraft.message.beacon.no_payment";
            }

            clickSlot(handler, PLAYER_CRAFT_INPUT_SLOT_ID, 1, ContainerInput.PICKUP, client);
            returnCursorToSourceOrDrop(handler, sourceSlotId, client);

            pendingDecomposePaymentItem = material.paymentItem;
            pendingDecomposeTakeAttempts = 0;
            return null;
        }

        return "quickcraft.message.beacon.no_payment";
    }

    private boolean tryTakeCraftResult(InventoryMenu handler, Minecraft client) {
        ItemStack before = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getItem().copy();
        if (before.isEmpty()) {
            return false;
        }

        clickSlot(handler, PLAYER_CRAFT_RESULT_SLOT_ID, 0, ContainerInput.QUICK_MOVE, client);

        ItemStack after = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getItem();
        return after.isEmpty()
                || after.getCount() != before.getCount()
                || !ItemStack.isSameItemSameComponents(before, after);
    }

    private boolean isPlayerCraftingHandlerReady(Minecraft client) {
        return client.player != null
                && client.gui.screen() == null
                && client.player.containerMenu == client.player.inventoryMenu;
    }

    private void preparePendingTarget(Minecraft client) {
        TargetSelectionResult selection = selectNextTarget(client);
        pendingTarget = selection.target();
        pendingFailureReason = selection.failureReason();
    }

    private TargetSelectionResult selectNextTarget(Minecraft client) {
        if (client.player == null) {
            return new TargetSelectionResult(null, PendingFailureReason.NO_VALID_ORDER);
        }

        List<BeaconEffectTarget> targets = parseConfiguredTargets();
        if (targets.isEmpty()) {
            return new TargetSelectionResult(null, PendingFailureReason.NO_VALID_ORDER);
        }

        Set<BeaconEffectTarget> reservedTargets = getReservedTargets(client);
        for (BeaconEffectTarget target : targets) {
            if (playerHasTargetEffect(client, target) || reservedTargets.contains(target)) {
                continue;
            }

            return new TargetSelectionResult(target, PendingFailureReason.NONE);
        }

        return new TargetSelectionResult(null, PendingFailureReason.ALL_ACTIVE);
    }

    private List<BeaconEffectTarget> parseConfiguredTargets() {
        List<BeaconEffectTarget> targets = new ArrayList<>();

        for (String raw : QuickCraftConfigs.getBeaconEffectOrderStrings()) {
            if (targets.size() >= MAX_EFFECT_ORDER_SIZE) {
                break;
            }

            BeaconEffectTarget target = parseTarget(raw);
            if (target != null) {
                targets.add(target);
            }
        }

        return targets;
    }

    private BeaconEffectTarget parseTarget(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = normalizeEffectName(raw);
        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.endsWith("ii")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("2")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return switch (normalized) {
            case "haste", "急迫", "挖掘急迫" -> BeaconEffectTarget.levelTwo(MobEffects.HASTE);
            case "strength", "力量" -> BeaconEffectTarget.levelTwo(MobEffects.STRENGTH);
            case "regeneration", "regen", "生命恢复", "恢复" -> BeaconEffectTarget.levelTwo(MobEffects.REGENERATION);
            case "jumpboost", "jump", "跳跃提升", "跳跃" -> BeaconEffectTarget.levelTwo(MobEffects.JUMP_BOOST);
            case "speed", "迅捷" -> BeaconEffectTarget.levelTwo(MobEffects.SPEED);
            case "resistance", "抗性", "抗性提升" -> BeaconEffectTarget.levelTwo(MobEffects.RESISTANCE);
            default -> null;
        };
    }

    private String normalizeEffectName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
    }

    private boolean playerHasTargetEffect(Minecraft client, BeaconEffectTarget target) {
        if (client.player == null) {
            return false;
        }

        for (MobEffectInstance instance : client.player.getActiveEffects()) {
            if (!instance.getEffect().equals(target.primary())) {
                continue;
            }

            if (instance.getAmplifier() >= 1 && hasEnoughRemainingDuration(instance)) {
                return true;
            }
        }

        return false;
    }

    private boolean insertPaymentIntoBeacon(BeaconMenu handler, Minecraft client) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (!handler.getCarried().isEmpty()) {
            return false;
        }

        if (handler.getSlot(BEACON_PAYMENT_SLOT_ID).hasItem()) {
            return true;
        }

        int sourceSlotId = findPaymentSourceSlotId(handler);
        if (sourceSlotId == -1) {
            return false;
        }

        clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP, client);
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        clickSlot(handler, BEACON_PAYMENT_SLOT_ID, 1, ContainerInput.PICKUP, client);

        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP, client);
        }

        return handler.getSlot(BEACON_PAYMENT_SLOT_ID).hasItem();
    }

    private int findPaymentSourceSlotId(AbstractContainerMenu handler) {
        for (PaymentMaterial material : PaymentMaterial.values()) {
            for (int slotId : getPlayerStorageSlotIds(handler)) {
                Slot slot = handler.getSlot(slotId);
                if (slot.hasItem() && slot.getItem().is(material.paymentItem)) {
                    return slotId;
                }
            }
        }

        return -1;
    }

    private boolean hasPaymentBlock(InventoryMenu handler) {
        for (PaymentMaterial material : PaymentMaterial.values()) {
            if (findPlayerItemSlotId(handler, material.blockItem) != -1) {
                return true;
            }
        }

        return false;
    }

    private boolean isPlayerCraftingBusy(InventoryMenu handler) {
        if (!handler.getCarried().isEmpty() || handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).hasItem()) {
            return true;
        }

        for (int slotId = 1; slotId <= 4; slotId++) {
            if (handler.getSlot(slotId).hasItem()) {
                return true;
            }
        }

        return false;
    }

    private void returnCursorToSourceOrDrop(InventoryMenu handler, int sourceSlotId, Minecraft client) {
        if (handler.getCarried().isEmpty()) {
            return;
        }

        clickSlot(handler, sourceSlotId, 0, ContainerInput.PICKUP, client);
        if (!handler.getCarried().isEmpty()) {
            clickSlot(handler, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, ContainerInput.PICKUP, client);
        }
    }

    private void clearCraftingGrid(InventoryMenu handler, Minecraft client) {
        if (!handler.getCarried().isEmpty()) {
            int returnSlotId = findFirstAcceptingPlayerSlotId(handler, handler.getCarried());
            if (returnSlotId != -1) {
                clickSlot(handler, returnSlotId, 0, ContainerInput.PICKUP, client);
            }
            if (!handler.getCarried().isEmpty()) {
                clickSlot(handler, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, ContainerInput.PICKUP, client);
            }
        }

        for (int slotId = 1; slotId <= 4; slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasItem()) {
                clickSlot(handler, slotId, 0, ContainerInput.QUICK_MOVE, client);
            }
        }
    }

    private int findPlayerItemSlotId(InventoryMenu handler, Item item) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasItem() && slot.getItem().is(item)) {
                return slotId;
            }
        }

        return -1;
    }

    private int findFirstAcceptingPlayerSlotId(AbstractContainerMenu handler, ItemStack stack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem()) {
                return slotId;
            }
            if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)
                    && slot.getItem().getCount() + stack.getCount() <= slot.getItem().getMaxStackSize()) {
                return slotId;
            }
        }

        return -1;
    }

    private List<Integer> getPlayerStorageSlotIds(AbstractContainerMenu handler) {
        List<Slot> playerSlots = new ArrayList<>();
        boolean requireVisibleSlots = !(handler instanceof InventoryMenu);

        for (Slot slot : handler.slots) {
            if (!isPlayerStorageSlot(slot)) {
                continue;
            }
            if (requireVisibleSlots && !isVisibleSlot(slot)) {
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

    private void sendBeaconPacket(Minecraft client, BeaconEffectTarget target) {
        if (client.getConnection() == null) {
            return;
        }

        if (pendingBeaconPos != null) {
            RECENT_BEACON_ASSIGNMENTS.put(
                    pendingBeaconPos.immutable(),
                    new RecentBeaconAssignment(
                            target,
                            getCurrentWorldTime(client) + RECENT_ASSIGNMENT_TTL_TICKS,
                            getCurrentBeaconIdentity(client, pendingBeaconPos)
                    )
            );
        }

        client.getConnection().send(new ServerboundSetBeaconPacket(
                Optional.ofNullable(target.primary()),
                Optional.ofNullable(target.secondary())
        ));
    }

    private void clickSlot(AbstractContainerMenu handler, int slotId, int button, ContainerInput actionType, Minecraft client) {
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private BlockHitResult getLookedAtBeaconHitResult(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.level == null) {
            return null;
        }

        return client.level.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof BeaconBlock
                ? blockHitResult
                : null;
    }

    private BeaconConfiguredState getConfirmedConfiguredState(BeaconMenu handler) {
        Holder<MobEffect> primary = handler.getPrimaryEffect();
        Holder<MobEffect> secondary = handler.getSecondaryEffect();
        if (primary == null || secondary == null) {
            return null;
        }

        BeaconEffectTarget currentTarget = new BeaconEffectTarget(primary, secondary);
        // 以打开信标后同步到界面的数据为准，只保护当前配置列表内的已选效果。
        if (!parseConfiguredTargets().contains(currentTarget)) {
            return null;
        }

        return new BeaconConfiguredState(
                getLevelTwoEffectName(primary),
                "quickcraft.message.beacon.already_selected"
        );
    }

    private Set<BeaconEffectTarget> getReservedTargets(Minecraft client) {
        Set<BeaconEffectTarget> reservedTargets = new HashSet<>();

        for (Map.Entry<BlockPos, RecentBeaconAssignment> entry : RECENT_BEACON_ASSIGNMENTS.entrySet()) {
            if (isRecentAssignmentValid(client, entry.getKey(), entry.getValue())) {
                reservedTargets.add(entry.getValue().target());
            }
        }

        return reservedTargets;
    }

    private void pruneRecentAssignments(Minecraft client) {
        RECENT_BEACON_ASSIGNMENTS.entrySet().removeIf(entry -> !isRecentAssignmentValid(client, entry.getKey(), entry.getValue()));
    }

    private boolean isRecentAssignmentValid(Minecraft client, BlockPos beaconPos, RecentBeaconAssignment assignment) {
        if (client.level == null) {
            return false;
        }

        if (getCurrentWorldTime(client) > assignment.expiresAt()) {
            return false;
        }

        if (!(client.level.getBlockState(beaconPos).getBlock() instanceof BeaconBlock)) {
            return false;
        }

        int currentIdentity = getCurrentBeaconIdentity(client, beaconPos);
        return currentIdentity != -1 && currentIdentity == assignment.blockEntityIdentity();
    }

    private long getCurrentWorldTime(Minecraft client) {
        return client.level != null ? client.level.getGameTime() : 0L;
    }

    private int getCurrentBeaconIdentity(Minecraft client, BlockPos beaconPos) {
        if (client.level == null) {
            return -1;
        }

        if (!(client.level.getBlockEntity(beaconPos) instanceof BeaconBlockEntity beaconBlockEntity)) {
            return -1;
        }

        return System.identityHashCode(beaconBlockEntity);
    }

    private void sendConfiguredStateMessage(Minecraft client, BeaconConfiguredState configuredState) {
        if (configuredState.displayName() != null) {
            sendStatusMessage(client, Component.translatable(configuredState.translationKey(), configuredState.displayName()));
            return;
        }

        sendStatusMessage(client, Component.translatable(configuredState.translationKey()));
    }

    private Component getLevelTwoEffectName(Holder<MobEffect> effect) {
        return Component.translatable(getLevelTwoEffectTranslationKey(effect));
    }

    private String getLevelTwoEffectTranslationKey(Holder<MobEffect> effect) {
        if (effect.equals(MobEffects.HASTE)) {
            return "quickcraft.beacon.effect.haste2";
        }
        if (effect.equals(MobEffects.STRENGTH)) {
            return "quickcraft.beacon.effect.strength2";
        }
        if (effect.equals(MobEffects.REGENERATION)) {
            return "quickcraft.beacon.effect.regeneration2";
        }
        if (effect.equals(MobEffects.JUMP_BOOST)) {
            return "quickcraft.beacon.effect.jump_boost2";
        }
        if (effect.equals(MobEffects.SPEED)) {
            return "quickcraft.beacon.effect.speed2";
        }
        if (effect.equals(MobEffects.RESISTANCE)) {
            return "quickcraft.beacon.effect.resistance2";
        }
        return "quickcraft.beacon.effect.unknown";
    }

    private boolean hasEnoughRemainingDuration(MobEffectInstance instance) {
        return instance.getDuration() >= MIN_ACTIVE_EFFECT_DURATION_TICKS;
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private void closeCurrentScreen(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
        }
    }

    private static void clearPendingState() {
        pendingStage = PendingStage.NONE;
        pendingTicks = 0;
        pendingBeaconPos = null;
        pendingBeaconHitResult = null;
        pendingTarget = null;
        pendingFailureReason = PendingFailureReason.NONE;
        pendingDecomposePaymentItem = null;
        pendingDecomposeTakeAttempts = 0;
    }

    private enum PendingFailureReason {
        NONE(""),
        NO_VALID_ORDER("quickcraft.message.beacon.invalid_order"),
        ALL_ACTIVE("quickcraft.message.beacon.all_active");

        private final String translationKey;

        PendingFailureReason(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private enum PaymentMaterial {
        IRON(Items.IRON_INGOT, Items.IRON_BLOCK),
        EMERALD(Items.EMERALD, Items.EMERALD_BLOCK),
        GOLD(Items.GOLD_INGOT, Items.GOLD_BLOCK),
        DIAMOND(Items.DIAMOND, Items.DIAMOND_BLOCK);

        private final Item paymentItem;
        private final Item blockItem;

        PaymentMaterial(Item paymentItem, Item blockItem) {
            this.paymentItem = paymentItem;
            this.blockItem = blockItem;
        }
    }

    private enum PendingStage {
        NONE,
        WAIT_INITIAL_OPEN,
        DECOMPOSE_START,
        DECOMPOSE_WAIT_RESULT,
        DECOMPOSE_TAKE_RESULT,
        WAIT_REOPEN
    }

    private record BeaconEffectTarget(
            Holder<MobEffect> primary,
            Holder<MobEffect> secondary
    ) {
        private static BeaconEffectTarget levelTwo(Holder<MobEffect> effect) {
            return new BeaconEffectTarget(effect, effect);
        }
    }

    private record BeaconConfiguredState(Component displayName, String translationKey) {
    }

    private record RecentBeaconAssignment(
            BeaconEffectTarget target,
            long expiresAt,
            int blockEntityIdentity
    ) {
    }

    private record TargetSelectionResult(BeaconEffectTarget target, PendingFailureReason failureReason) {
    }
}
