package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BeaconBlock;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateBeaconC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

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

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickBeaconEnabled()) {
            clearPendingState();
        }

        pruneRecentAssignments(client);
        handleUseAttempt(client);
        processPendingState(client);
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickBeaconEnabled() || client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.useKey);
        BlockHitResult beaconHitResult = getLookedAtBeaconHitResult(client);
        if (useDown && !lastUseDown && client.currentScreen == null && beaconHitResult != null) {
            pendingBeaconPos = beaconHitResult.getBlockPos().toImmutable();
            pendingBeaconHitResult = beaconHitResult;
            pendingTarget = null;
            pendingFailureReason = PendingFailureReason.NONE;
            pendingStage = PendingStage.WAIT_INITIAL_OPEN;
            pendingTicks = 0;
        }

        lastUseDown = useDown;
    }

    private void processPendingState(MinecraftClient client) {
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

    private void processBeaconOpenState(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        if (!(screen.getScreenHandler() instanceof BeaconScreenHandler handler)) {
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
            sendStatusMessage(client, Text.translatable(pendingFailureReason.translationKey));
            closeCurrentScreen(client);
            clearPendingState();
            return;
        }

        if (!insertPaymentIntoBeacon(handler, client)) {
            if (client.player != null && hasPaymentBlock(client.player.playerScreenHandler)) {
                closeCurrentScreen(client);
                pendingStage = PendingStage.DECOMPOSE_START;
                pendingTicks = 0;
                return;
            }

            sendStatusMessage(client, Text.translatable("quickcraft.message.beacon.no_payment"));
            closeCurrentScreen(client);
            clearPendingState();
            return;
        }

        sendBeaconPacket(client, pendingTarget);
        closeCurrentScreen(client);
        clearPendingState();
    }

    private void processDecomposeStart(MinecraftClient client) {
        if (!isPlayerCraftingHandlerReady(client)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        String translationKey = beginPendingDecompose(client);
        if (translationKey != null) {
            sendStatusMessage(client, Text.translatable(translationKey));
            clearPendingState();
            return;
        }

        pendingStage = PendingStage.DECOMPOSE_WAIT_RESULT;
        pendingTicks = 0;
    }

    private void processDecomposeWaitResult(MinecraftClient client) {
        if (!isPlayerCraftingHandlerReady(client) || client.player == null) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        PlayerScreenHandler handler = client.player.playerScreenHandler;
        ItemStack resultStack = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getStack();
        if (pendingDecomposePaymentItem != null && resultStack.isOf(pendingDecomposePaymentItem)) {
            pendingDecomposeTakeAttempts = 0;
            pendingStage = PendingStage.DECOMPOSE_TAKE_RESULT;
            pendingTicks = 0;
            return;
        }

        if (pendingTicks > CRAFT_RESULT_WAIT_TICKS) {
            clearCraftingGrid(handler, client);
            sendStatusMessage(client, Text.translatable("quickcraft.message.beacon.no_payment"));
            clearPendingState();
        }
    }

    private void processDecomposeTakeResult(MinecraftClient client) {
        if (!isPlayerCraftingHandlerReady(client) || client.player == null) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPendingState();
            }
            return;
        }

        PlayerScreenHandler handler = client.player.playerScreenHandler;
        if (tryTakeCraftResult(handler, client)) {
            if (client.interactionManager == null || pendingBeaconHitResult == null) {
                clearPendingState();
                return;
            }

            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, pendingBeaconHitResult);
            pendingStage = PendingStage.WAIT_REOPEN;
            pendingTicks = 0;
            return;
        }

        pendingDecomposeTakeAttempts++;
        if (pendingDecomposeTakeAttempts >= CRAFT_RESULT_TAKE_ATTEMPTS) {
            clearCraftingGrid(handler, client);
            sendStatusMessage(client, Text.translatable("quickcraft.message.beacon.no_room_for_decompose"));
            clearPendingState();
            return;
        }

        pendingTicks = 0;
    }

    private String beginPendingDecompose(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return "quickcraft.message.beacon.no_payment";
        }

        PlayerScreenHandler handler = client.player.playerScreenHandler;
        if (isPlayerCraftingBusy(handler)) {
            return "quickcraft.message.beacon.crafting_busy";
        }

        for (PaymentMaterial material : PaymentMaterial.values()) {
            int sourceSlotId = findPlayerItemSlotId(handler, material.blockItem);
            if (sourceSlotId == -1) {
                continue;
            }

            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP, client);
            if (handler.getCursorStack().isEmpty()) {
                return "quickcraft.message.beacon.no_payment";
            }

            clickSlot(handler, PLAYER_CRAFT_INPUT_SLOT_ID, 1, SlotActionType.PICKUP, client);
            returnCursorToSourceOrDrop(handler, sourceSlotId, client);

            pendingDecomposePaymentItem = material.paymentItem;
            pendingDecomposeTakeAttempts = 0;
            return null;
        }

        return "quickcraft.message.beacon.no_payment";
    }

    private boolean tryTakeCraftResult(PlayerScreenHandler handler, MinecraftClient client) {
        ItemStack before = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getStack().copy();
        if (before.isEmpty()) {
            return false;
        }

        clickSlot(handler, PLAYER_CRAFT_RESULT_SLOT_ID, 0, SlotActionType.QUICK_MOVE, client);

        ItemStack after = handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).getStack();
        return after.isEmpty()
                || after.getCount() != before.getCount()
                || !ItemStack.areItemsAndComponentsEqual(before, after);
    }

    private boolean isPlayerCraftingHandlerReady(MinecraftClient client) {
        return client.player != null
                && client.currentScreen == null
                && client.player.currentScreenHandler == client.player.playerScreenHandler;
    }

    private void preparePendingTarget(MinecraftClient client) {
        TargetSelectionResult selection = selectNextTarget(client);
        pendingTarget = selection.target();
        pendingFailureReason = selection.failureReason();
    }

    private TargetSelectionResult selectNextTarget(MinecraftClient client) {
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
            case "haste", "急迫", "挖掘急迫" -> BeaconEffectTarget.levelTwo(StatusEffects.HASTE);
            case "strength", "力量" -> BeaconEffectTarget.levelTwo(StatusEffects.STRENGTH);
            case "regeneration", "regen", "生命恢复", "恢复" -> BeaconEffectTarget.levelTwo(StatusEffects.REGENERATION);
            case "jumpboost", "jump", "跳跃提升", "跳跃" -> BeaconEffectTarget.levelTwo(StatusEffects.JUMP_BOOST);
            case "speed", "迅捷" -> BeaconEffectTarget.levelTwo(StatusEffects.SPEED);
            case "resistance", "抗性", "抗性提升" -> BeaconEffectTarget.levelTwo(StatusEffects.RESISTANCE);
            default -> null;
        };
    }

    private String normalizeEffectName(String raw) {
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
    }

    private boolean playerHasTargetEffect(MinecraftClient client, BeaconEffectTarget target) {
        if (client.player == null) {
            return false;
        }

        for (StatusEffectInstance instance : client.player.getStatusEffects()) {
            if (!instance.getEffectType().equals(target.primary())) {
                continue;
            }

            if (instance.getAmplifier() >= 1 && hasEnoughRemainingDuration(instance)) {
                return true;
            }
        }

        return false;
    }

    private boolean insertPaymentIntoBeacon(BeaconScreenHandler handler, MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }

        if (handler.getSlot(BEACON_PAYMENT_SLOT_ID).hasStack()) {
            return true;
        }

        int sourceSlotId = findPaymentSourceSlotId(handler);
        if (sourceSlotId == -1) {
            return false;
        }

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP, client);
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        clickSlot(handler, BEACON_PAYMENT_SLOT_ID, 1, SlotActionType.PICKUP, client);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP, client);
        }

        return handler.getSlot(BEACON_PAYMENT_SLOT_ID).hasStack();
    }

    private int findPaymentSourceSlotId(ScreenHandler handler) {
        for (PaymentMaterial material : PaymentMaterial.values()) {
            for (int slotId : getPlayerStorageSlotIds(handler)) {
                Slot slot = handler.getSlot(slotId);
                if (slot.hasStack() && slot.getStack().isOf(material.paymentItem)) {
                    return slotId;
                }
            }
        }

        return -1;
    }

    private boolean hasPaymentBlock(PlayerScreenHandler handler) {
        for (PaymentMaterial material : PaymentMaterial.values()) {
            if (findPlayerItemSlotId(handler, material.blockItem) != -1) {
                return true;
            }
        }

        return false;
    }

    private boolean isPlayerCraftingBusy(PlayerScreenHandler handler) {
        if (!handler.getCursorStack().isEmpty() || handler.getSlot(PLAYER_CRAFT_RESULT_SLOT_ID).hasStack()) {
            return true;
        }

        for (int slotId = 1; slotId <= 4; slotId++) {
            if (handler.getSlot(slotId).hasStack()) {
                return true;
            }
        }

        return false;
    }

    private void returnCursorToSourceOrDrop(PlayerScreenHandler handler, int sourceSlotId, MinecraftClient client) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP, client);
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, ScreenHandler.EMPTY_SPACE_SLOT_INDEX, 0, SlotActionType.PICKUP, client);
        }
    }

    private void clearCraftingGrid(PlayerScreenHandler handler, MinecraftClient client) {
        if (!handler.getCursorStack().isEmpty()) {
            int returnSlotId = findFirstAcceptingPlayerSlotId(handler, handler.getCursorStack());
            if (returnSlotId != -1) {
                clickSlot(handler, returnSlotId, 0, SlotActionType.PICKUP, client);
            }
            if (!handler.getCursorStack().isEmpty()) {
                clickSlot(handler, ScreenHandler.EMPTY_SPACE_SLOT_INDEX, 0, SlotActionType.PICKUP, client);
            }
        }

        for (int slotId = 1; slotId <= 4; slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasStack()) {
                clickSlot(handler, slotId, 0, SlotActionType.QUICK_MOVE, client);
            }
        }
    }

    private int findPlayerItemSlotId(PlayerScreenHandler handler, Item item) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasStack() && slot.getStack().isOf(item)) {
                return slotId;
            }
        }

        return -1;
    }

    private int findFirstAcceptingPlayerSlotId(ScreenHandler handler, ItemStack stack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()) {
                return slotId;
            }
            if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)
                    && slot.getStack().getCount() + stack.getCount() <= slot.getStack().getMaxCount()) {
                return slotId;
            }
        }

        return -1;
    }

    private List<Integer> getPlayerStorageSlotIds(ScreenHandler handler) {
        List<Slot> playerSlots = new ArrayList<>();
        boolean requireVisibleSlots = !(handler instanceof PlayerScreenHandler);

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

    private void sendBeaconPacket(MinecraftClient client, BeaconEffectTarget target) {
        if (client.getNetworkHandler() == null) {
            return;
        }

        if (pendingBeaconPos != null) {
            RECENT_BEACON_ASSIGNMENTS.put(
                    pendingBeaconPos.toImmutable(),
                    new RecentBeaconAssignment(
                            target,
                            getCurrentWorldTime(client) + RECENT_ASSIGNMENT_TTL_TICKS,
                            getCurrentBeaconIdentity(client, pendingBeaconPos)
                    )
            );
        }

        client.getNetworkHandler().sendPacket(new UpdateBeaconC2SPacket(
                Optional.ofNullable(target.primary()),
                Optional.ofNullable(target.secondary())
        ));
    }

    private void clickSlot(ScreenHandler handler, int slotId, int button, SlotActionType actionType, MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private BlockHitResult getLookedAtBeaconHitResult(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return null;
        }

        return client.world.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof BeaconBlock
                ? blockHitResult
                : null;
    }

    private BeaconConfiguredState getConfirmedConfiguredState(BeaconScreenHandler handler) {
        RegistryEntry<StatusEffect> primary = handler.getPrimaryEffect();
        RegistryEntry<StatusEffect> secondary = handler.getSecondaryEffect();
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

    private Set<BeaconEffectTarget> getReservedTargets(MinecraftClient client) {
        Set<BeaconEffectTarget> reservedTargets = new HashSet<>();

        for (Map.Entry<BlockPos, RecentBeaconAssignment> entry : RECENT_BEACON_ASSIGNMENTS.entrySet()) {
            if (isRecentAssignmentValid(client, entry.getKey(), entry.getValue())) {
                reservedTargets.add(entry.getValue().target());
            }
        }

        return reservedTargets;
    }

    private void pruneRecentAssignments(MinecraftClient client) {
        RECENT_BEACON_ASSIGNMENTS.entrySet().removeIf(entry -> !isRecentAssignmentValid(client, entry.getKey(), entry.getValue()));
    }

    private boolean isRecentAssignmentValid(MinecraftClient client, BlockPos beaconPos, RecentBeaconAssignment assignment) {
        if (client.world == null) {
            return false;
        }

        if (getCurrentWorldTime(client) > assignment.expiresAt()) {
            return false;
        }

        if (!(client.world.getBlockState(beaconPos).getBlock() instanceof BeaconBlock)) {
            return false;
        }

        int currentIdentity = getCurrentBeaconIdentity(client, beaconPos);
        return currentIdentity != -1 && currentIdentity == assignment.blockEntityIdentity();
    }

    private long getCurrentWorldTime(MinecraftClient client) {
        return client.world != null ? client.world.getTime() : 0L;
    }

    private int getCurrentBeaconIdentity(MinecraftClient client, BlockPos beaconPos) {
        if (client.world == null) {
            return -1;
        }

        if (!(client.world.getBlockEntity(beaconPos) instanceof BeaconBlockEntity beaconBlockEntity)) {
            return -1;
        }

        return System.identityHashCode(beaconBlockEntity);
    }

    private void sendConfiguredStateMessage(MinecraftClient client, BeaconConfiguredState configuredState) {
        if (configuredState.displayName() != null) {
            sendStatusMessage(client, Text.translatable(configuredState.translationKey(), configuredState.displayName()));
            return;
        }

        sendStatusMessage(client, Text.translatable(configuredState.translationKey()));
    }

    private Text getLevelTwoEffectName(RegistryEntry<StatusEffect> effect) {
        return Text.translatable(getLevelTwoEffectTranslationKey(effect));
    }

    private String getLevelTwoEffectTranslationKey(RegistryEntry<StatusEffect> effect) {
        if (effect.equals(StatusEffects.HASTE)) {
            return "quickcraft.beacon.effect.haste2";
        }
        if (effect.equals(StatusEffects.STRENGTH)) {
            return "quickcraft.beacon.effect.strength2";
        }
        if (effect.equals(StatusEffects.REGENERATION)) {
            return "quickcraft.beacon.effect.regeneration2";
        }
        if (effect.equals(StatusEffects.JUMP_BOOST)) {
            return "quickcraft.beacon.effect.jump_boost2";
        }
        if (effect.equals(StatusEffects.SPEED)) {
            return "quickcraft.beacon.effect.speed2";
        }
        if (effect.equals(StatusEffects.RESISTANCE)) {
            return "quickcraft.beacon.effect.resistance2";
        }
        return "quickcraft.beacon.effect.unknown";
    }

    private boolean hasEnoughRemainingDuration(StatusEffectInstance instance) {
        return instance.getDuration() >= MIN_ACTIVE_EFFECT_DURATION_TICKS;
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void closeCurrentScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
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
            RegistryEntry<StatusEffect> primary,
            RegistryEntry<StatusEffect> secondary
    ) {
        private static BeaconEffectTarget levelTwo(RegistryEntry<StatusEffect> effect) {
            return new BeaconEffectTarget(effect, effect);
        }
    }

    private record BeaconConfiguredState(Text displayName, String translationKey) {
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
