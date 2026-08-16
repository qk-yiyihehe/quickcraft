package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.MiningToolItem;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 在 Litematica 轻松放置缺少材料时，通过 Quick Shulker 取出背包内潜影盒中的材料。
 */
public final class QuickLitematicaShulkerMaterialRestock implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int CLOSE_TIMEOUT_TICKS = 20;
    private static final int CLOSE_CURSOR_RETRIES = 10;
    private static final Identifier QUICK_SHULKER_OPEN_PACKET = Identifier.of("quickshulker", "open_shulker_packet");

    private static final Deque<RetrievedMaterial> retrievedMaterials = new ArrayDeque<>();
    private static PendingRequest pendingRequest;
    private static ActiveAction activeAction;
    private static Operation operation = Operation.IDLE;
    private static int operationTicks;
    private static int closeCursorRetries;
    private static int restockCooldownTicks;
    private static boolean closeSent;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaShulkerMaterialRestock::onClientTick);
    }

    /**
     * @return true 表示已经接管本次缺料选取，调用方不应继续尝试普通换手。
     */
    public static boolean requestMissingMaterial(ItemStack requiredStack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!canHandleRequest(client, requiredStack)) {
            return false;
        }

        if (hasPlayerMaterial(client.player.getInventory(), requiredStack)) {
            return false;
        }

        if (operation != Operation.IDLE || pendingRequest != null) {
            return true;
        }

        PlayerInventory inventory = client.player.getInventory();
        if (restockCooldownTicks > 0) {
            boolean materialInShulker = findShulkerWithMaterial(inventory, requiredStack) != -1;
            return materialInShulker;
        }

        pendingRequest = new PendingRequest(copyTemplate(requiredStack));
        if (startPendingRequest(client)) {
            return true;
        }

        boolean materialIsInShulker = findShulkerWithMaterial(inventory, pendingRequest.template()) != -1;
        pendingRequest = null;
        return materialIsInShulker;
    }

    /**
     * 处理 Litematica 在真正执行 easy place 前的放置限制检查。
     * 此时主手可能为空，不能依赖 Litematica 后续的 pick block 回调来发现缺料。
     */
    public static boolean requestMaterialForEasyPlaceTarget(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()
                || client == null
                || client.player == null
                || client.world == null
                || !(client.crosshairTarget instanceof BlockHitResult hitResult)
                || hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        ItemPlacementContext placementContext = new ItemPlacementContext(
                new ItemUsageContext(client.player, Hand.MAIN_HAND, hitResult)
        );
        BlockPos position = placementContext.getBlockPos();
        World schematicWorld = SchematicWorldHandler.getSchematicWorld();
        BlockState schematicState = schematicWorld.getBlockState(position);

        if (schematicState.isAir()
                || !DataManager.getRenderLayerRange().isPositionWithinRange(position)
                || !client.world.getBlockState(position).canReplace(placementContext)) {
            return false;
        }

        ItemStack requiredStack = MaterialCache.getInstance().getRequiredBuildItemForState(schematicState);
        return requestMissingMaterial(requiredStack);
    }

    private static void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()
                || client.player == null
                || client.world == null) {
            resetState(client);
            return;
        }

        if (restockCooldownTicks > 0) {
            restockCooldownTicks--;
        }

        switch (operation) {
            case IDLE -> {
                if (pendingRequest != null
                        && client.currentScreen == null
                        && !startPendingRequest(client)) {
                    pendingRequest = null;
                }
            }
            case WAITING_FOR_OPEN -> waitForOpenedShulker(client);
            case WAITING_FOR_CLOSE -> processClosingShulker(client);
        }
    }

    /**
     * Quick Shulker 打开的是后台 ScreenHandler；容器内容由网络包到达后再执行取放。
     */
    public static void onShulkerContentsReceived(int syncId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (operation != Operation.WAITING_FOR_OPEN
                || activeAction == null
                || client.player == null
                || client.currentScreen != null
                || !(client.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)
                || handler.syncId != syncId) {
            return;
        }

        operationTicks = 0;
        switch (activeAction.type()) {
            case EXTRACT -> extractMaterial(client, handler, activeAction);
            case REPLACE -> replaceHotbarMaterial(client, handler, activeAction);
            case STASH -> stashHotbarMaterial(client, handler, activeAction);
            case STASH_AND_EXTRACT -> stashHotbarAndExtractMaterial(client, handler, activeAction);
            case RETURN -> returnMaterial(client, handler, activeAction.material());
        }
        beginClosing(handler);
    }

    /**
     * 保留 Quick Shulker 的后台容器，避免自动补料时把潜影盒界面切到前台。
     */
    public static boolean shouldSuppressShulkerScreenOpen() {
        return operation == Operation.WAITING_FOR_OPEN && activeAction != null;
    }

    private static boolean canHandleRequest(MinecraftClient client, ItemStack requiredStack) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()) {
            return false;
        }
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()) {
            return false;
        }
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (client.player.isCreative()) {
            return false;
        }
        if (requiredStack == null || requiredStack.isEmpty()) {
            return false;
        }
        if (!canUseQuickShulker()) {
            return false;
        }
        return true;
    }

    private static boolean startPendingRequest(MinecraftClient client) {
        if (pendingRequest == null || client.player == null || client.currentScreen != null) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        if (hasPlayerMaterial(inventory, pendingRequest.template())) {
            pendingRequest = null;
            return true;
        }

        if (isInventoryFull(inventory)) {
            int sourceShulkerSlot = findShulkerWithMaterial(inventory, pendingRequest.template());
            int targetHotbarSlot = findLitematicaPickBlockTarget(inventory);
            if (sourceShulkerSlot == -1 || targetHotbarSlot == -1) {
                return false;
            }

            RetrievedMaterial returnCandidate = getReturnCandidate(inventory);
            if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled() && returnCandidate != null) {
                int returnShulkerSlot = findReturnShulker(inventory, returnCandidate);
                if (returnShulkerSlot != -1) {
                    return openShulker(client, returnShulkerSlot, ActionType.RETURN, returnCandidate, -1);
                }

                return false;
            }

            TrackedShulker source = TrackedShulker.from(inventory.getStack(sourceShulkerSlot), sourceShulkerSlot);
            if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
                RetrievedMaterial retrievedMaterial = new RetrievedMaterial(pendingRequest.template().getItem(), source);
                if (hasEmptyContainerSlot(inventory.getStack(sourceShulkerSlot))) {
                    return openShulker(
                            client,
                            sourceShulkerSlot,
                            ActionType.STASH_AND_EXTRACT,
                            retrievedMaterial,
                            targetHotbarSlot
                    );
                }

                int stashShulkerSlot = findShulkerWithSpace(inventory, sourceShulkerSlot);
                if (stashShulkerSlot != -1) {
                    return openShulker(client, stashShulkerSlot, ActionType.STASH, null, targetHotbarSlot);
                }
                return false;
            }

            return openShulker(
                    client,
                    sourceShulkerSlot,
                    ActionType.REPLACE,
                    new RetrievedMaterial(pendingRequest.template().getItem(), source),
                    targetHotbarSlot
            );
        }

        int shulkerSlot = findShulkerWithMaterial(inventory, pendingRequest.template());
        int targetHotbarSlot = findLitematicaPickBlockTarget(inventory);
        if (shulkerSlot == -1 || targetHotbarSlot == -1) {
            return false;
        }

        TrackedShulker source = TrackedShulker.from(inventory.getStack(shulkerSlot), shulkerSlot);
        return openShulker(
                client,
                shulkerSlot,
                ActionType.EXTRACT,
                new RetrievedMaterial(pendingRequest.template().getItem(), source),
                targetHotbarSlot
        );
    }

    private static void waitForOpenedShulker(MinecraftClient client) {
        if (activeAction == null || client.player == null) {
            resetState(client);
            return;
        }

        if (client.currentScreen != null) {
            resetState(client);
            return;
        }

        if (++operationTicks > OPEN_TIMEOUT_TICKS) {
            resetState(client);
        }
    }

    private static void extractMaterial(MinecraftClient client,
                                        ShulkerBoxScreenHandler handler,
                                        ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findEmptyPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
        if (!destinationSlot.hasStack() || !destinationSlot.getStack().isOf(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        if (destinationSlot.getIndex() != action.targetHotbarSlot()
                && !moveExtractedMaterialToHotbar(client, handler, destinationSlot, action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material());
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    private static boolean moveExtractedMaterialToHotbar(MinecraftClient client,
                                                          ShulkerBoxScreenHandler handler,
                                                          Slot sourceSlot,
                                                          int targetHotbarSlot) {
        Slot targetSlot = findPlayerStorageSlot(handler, targetHotbarSlot);
        if (targetSlot == null) {
            return false;
        }

        clickSlot(client, handler, sourceSlot.id);
        if (!handler.getCursorStack().isOf(pendingRequest.template().getItem())) {
            return false;
        }

        clickSlot(client, handler, targetSlot.id);
        if (!targetSlot.hasStack() || !targetSlot.getStack().isOf(pendingRequest.template().getItem())) {
            return false;
        }

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(client, handler, sourceSlot.id);
        }
        return handler.getCursorStack().isEmpty();
    }

    /**
     * 背包没有空格时，把目标材料直接换入 Litematica 允许的快捷栏格，
     * 同一后台容器操作把被换下的物品放进刚腾空的潜影盒格，避免原版 pick block 选到空手。
     */
    private static void replaceHotbarMaterial(MinecraftClient client,
                                              ShulkerBoxScreenHandler handler,
                                              ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            pendingRequest = null;
            return;
        }
        boolean replacedExistingStack = destinationSlot.hasStack();
        clickSlot(client, handler, sourceSlot.id);
        if (!handler.getCursorStack().isOf(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, destinationSlot.id);
        if (!destinationSlot.hasStack() || !destinationSlot.getStack().isOf(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        if (replacedExistingStack) {
            clickSlot(client, handler, sourceSlot.id);
            if (!handler.getCursorStack().isEmpty()) {
                pendingRequest = null;
                return;
            }
        } else {
            action.material().source().updateContents(getContainerContents(handler));
            retrievedMaterials.addLast(action.material());
        }

        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    /**
     * 目标来源盒满时，先腾出目标快捷栏格；下一次后台开箱会从原来源盒取料。
     */
    private static void stashHotbarMaterial(MinecraftClient client,
                                            ShulkerBoxScreenHandler handler,
                                            ActiveAction action) {
        if (client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            return;
        }

        Slot sourceSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.hasStack() || !sourceSlot.canTakeItems(client.player)) {
            return;
        }

        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
    }

    /**
     * 来源盒原本有空位时，先存旧物、再取材料，保留材料原槽位给后续有序回塞。
     */
    private static void stashHotbarAndExtractMaterial(MinecraftClient client,
                                                       ShulkerBoxScreenHandler handler,
                                                       ActiveAction action) {
        if (pendingRequest == null
                || action.material() == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        Slot playerSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        Slot stashSlot = findEmptyContainerSlot(handler);
        Slot materialSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        if (playerSlot == null
                || stashSlot == null
                || materialSlot == null
                || !playerSlot.hasStack()
                || !playerSlot.canTakeItems(client.player)
                || !materialSlot.canTakeItems(client.player)) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, playerSlot.id);
        clickSlot(client, handler, stashSlot.id);
        if (!handler.getCursorStack().isEmpty()) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, materialSlot.id);
        clickSlot(client, handler, playerSlot.id);
        if (!playerSlot.hasStack() || !playerSlot.getStack().isOf(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material());
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    private static void returnMaterial(MinecraftClient client,
                                       ShulkerBoxScreenHandler handler,
                                       RetrievedMaterial material) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) {
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        Slot sourceSlot = findPlayerMaterialSlot(handler, material.item());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
        material.source().updateContents(getContainerContents(handler));
        retrievedMaterials.removeFirstOccurrence(material);
    }

    private static void processClosingShulker(MinecraftClient client) {
        if (activeAction == null || client.player == null) {
            resetState(client);
            return;
        }

        if (client.player.currentScreenHandler.syncId != activeAction.syncId()) {
            finishActiveAction();
            return;
        }

        if (!closeSent) {
            if (++operationTicks <= 2) {
                return;
            }

            if (!client.player.currentScreenHandler.getCursorStack().isEmpty()
                    && ++closeCursorRetries < CLOSE_CURSOR_RETRIES) {
                return;
            }

            client.player.closeHandledScreen();
            closeSent = true;
            operationTicks = 0;
            return;
        }

        if (++operationTicks > CLOSE_TIMEOUT_TICKS) {
            finishActiveAction();
        }
    }

    private static boolean openShulker(MinecraftClient client,
                                       int playerInventorySlot,
                                       ActionType actionType,
                                       RetrievedMaterial material,
                                       int targetHotbarSlot) {
        activeAction = new ActiveAction(actionType, material, targetHotbarSlot, -1);
        operation = Operation.WAITING_FOR_OPEN;
        operationTicks = 0;
        if (!sendOpenQuickShulkerPacket(playerInventorySlot)) {
            finishActiveAction();
            return false;
        }

        restockCooldownTicks = QuickCraftConfigs.getQuickShulkerActionIntervalTicks();
        return true;
    }

    private static void beginClosing(ScreenHandler handler) {
        activeAction = new ActiveAction(
                activeAction.type(),
                activeAction.material(),
                activeAction.targetHotbarSlot(),
                handler.syncId
        );
        operation = Operation.WAITING_FOR_CLOSE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
    }

    private static void finishActiveAction() {
        boolean completedReturn = activeAction != null && activeAction.type() == ActionType.RETURN;
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
        if (completedReturn) {
            restockCooldownTicks = 0;
        }
    }

    private static void resetState(MinecraftClient client) {
        if (activeAction != null
                && activeAction.syncId() >= 0
                && client != null
                && client.player != null
                && client.player.currentScreenHandler.syncId == activeAction.syncId()) {
            client.player.closeHandledScreen();
        }

        pendingRequest = null;
        retrievedMaterials.clear();
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        restockCooldownTicks = 0;
        closeSent = false;
    }

    private static boolean canUseQuickShulker() {
        if (!FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_OPEN_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean sendOpenQuickShulkerPacket(int slotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }

        int handlerSlotId = findCurrentHandlerSlotId(client.player.currentScreenHandler, slotId);
        if (handlerSlotId == -1) {
            return false;
        }

        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            Object packet = packetClass.getConstructor(int.class).newInstance(handlerSlotId);
            ClientPlayNetworking.send((CustomPayload) packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Quick Shulker 服务端按 PlayerScreenHandler 的槽位 ID 解析开箱包：主背包是 9-35，
     * 快捷栏则是 36-44，不能直接把 PlayerInventory 的 0-8 发过去。
     */
    private static int findCurrentHandlerSlotId(ScreenHandler handler, int playerInventorySlot) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventorySlot) {
                return slot.id;
            }
        }
        return -1;
    }

    private static int findShulkerWithMaterial(PlayerInventory inventory, ItemStack template) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack shulker = inventory.getStack(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && containsMaterial(shulker, template)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findShulkerWithSpace(PlayerInventory inventory, int excludedSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludedSlot) {
                continue;
            }

            ItemStack shulker = inventory.getStack(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && hasEmptyContainerSlot(shulker)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findReturnShulker(PlayerInventory inventory, RetrievedMaterial material) {
        if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
            int trackedSlot = material.source().findIn(inventory);
            if (trackedSlot != -1 && hasEmptyContainerSlot(inventory.getStack(trackedSlot))) {
                return trackedSlot;
            }

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (isShulkerBox(stack)
                        && stack.getCount() == 1
                        && getStoredStacks(stack).size() < 27
                        && containsItem(stack, material.item())) {
                    return slot;
                }
            }

            return -1;
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isShulkerBox(stack) && stack.getCount() == 1 && getStoredStacks(stack).size() < 27) {
                return slot;
            }
        }
        return -1;
    }

    private static RetrievedMaterial getReturnCandidate(PlayerInventory inventory) {
        while (!retrievedMaterials.isEmpty()) {
            RetrievedMaterial candidate = retrievedMaterials.peekFirst();
            if (hasPlayerItem(inventory, candidate.item())) {
                return candidate;
            }
            retrievedMaterials.removeFirst();
        }
        return null;
    }

    private static boolean hasPlayerMaterial(PlayerInventory inventory, ItemStack template) {
        return hasPlayerItem(inventory, template.getItem());
    }

    private static boolean hasPlayerItem(PlayerInventory inventory, Item item) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventoryFull(PlayerInventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsMaterial(ItemStack shulker, ItemStack template) {
        return containsItem(shulker, template.getItem());
    }

    private static boolean containsItem(ItemStack shulker, Item item) {
        for (ItemStack stack : getStoredStacks(shulker)) {
            if (stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            contents.add(stack.copy());
        }
        return contents;
    }

    private static boolean hasEmptyContainerSlot(ItemStack shulker) {
        return getStoredStacks(shulker).size() < 27;
    }

    private static List<ItemStack> getContainerContents(ScreenHandler handler) {
        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack()) {
                contents.add(slot.getStack().copy());
            }
        }
        return contents;
    }

    private static Slot findContainerMaterialSlot(ShulkerBoxScreenHandler handler, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack() && slot.getStack().isOf(template.getItem())) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyContainerSlot(ShulkerBoxScreenHandler handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasStack() && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyPlayerStorageSlot(ShulkerBoxScreenHandler handler, int preferredHotbarSlot) {
        Slot preferredSlot = findPlayerStorageSlot(handler, preferredHotbarSlot);
        if (preferredSlot != null && !preferredSlot.hasStack() && preferredSlot.isEnabled()) {
            return preferredSlot;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findPlayerStorageSlot(ShulkerBoxScreenHandler handler, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) {
            return null;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getIndex() == inventorySlot) {
                return slot;
            }
        }
        return null;
    }

    private static int findLitematicaPickBlockTarget(PlayerInventory inventory) {
        List<Integer> configuredSlots = getLitematicaPickBlockSlots();
        if (configuredSlots.isEmpty()) {
            return -1;
        }

        int selectedSlot = inventory.selectedSlot;
        if (configuredSlots.contains(selectedSlot) && isLitematicaPickBlockTarget(inventory, selectedSlot)) {
            return selectedSlot;
        }

        for (int slot : configuredSlots) {
            if (isLitematicaPickBlockTarget(inventory, slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static List<Integer> getLitematicaPickBlockSlots() {
        List<Integer> slots = new ArrayList<>();
        for (String configuredSlot : Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue().split(",")) {
            try {
                int slot = Integer.parseInt(configuredSlot.trim()) - 1;
                if (slot >= 0 && slot < 9 && !slots.contains(slot)) {
                    slots.add(slot);
                }
            } catch (NumberFormatException ignored) {
                // Litematica 也会忽略格式错误的快捷栏配置。
            }
        }
        return slots;
    }

    private static boolean isLitematicaPickBlockTarget(PlayerInventory inventory, int slot) {
        ItemStack stack = inventory.getStack(slot);
        return !isShulkerBox(stack)
                && (!Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue() || !stack.isDamageable())
                && (!Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue() || !(stack.getItem() instanceof MiningToolItem));
    }

    private static Slot findPlayerMaterialSlot(ShulkerBoxScreenHandler handler, Item item) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.hasStack() && slot.getStack().isOf(item)) {
                return slot;
            }
        }
        return null;
    }

    private static List<Slot> getContainerSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt((Slot slot) -> slot.getIndex()).thenComparingInt(slot -> slot.id));
        return slots;
    }

    private static List<Slot> getPlayerStorageSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory
                    && slot.getIndex() >= 0
                    && slot.getIndex() < 36
                    && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt(Slot::getIndex).thenComparingInt(slot -> slot.id));
        return slots;
    }

    private static void clickSlot(MinecraftClient client, ScreenHandler handler, int slotId) {
        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static ItemStack copyTemplate(ItemStack stack) {
        ItemStack template = stack.copy();
        template.setCount(1);
        return template;
    }

    private enum Operation {
        IDLE,
        WAITING_FOR_OPEN,
        WAITING_FOR_CLOSE
    }

    private enum ActionType {
        EXTRACT,
        REPLACE,
        STASH,
        STASH_AND_EXTRACT,
        RETURN
    }

    private record PendingRequest(ItemStack template) {
    }

    private record ActiveAction(ActionType type, RetrievedMaterial material, int targetHotbarSlot, int syncId) {
    }

    private record RetrievedMaterial(Item item, TrackedShulker source) {
    }

    private static final class TrackedShulker {
        private final Item boxItem;
        private List<ItemStack> contents;
        private int lastKnownSlot;

        private TrackedShulker(Item boxItem, List<ItemStack> contents, int lastKnownSlot) {
            this.boxItem = boxItem;
            this.contents = contents;
            this.lastKnownSlot = lastKnownSlot;
        }

        private static TrackedShulker from(ItemStack shulker, int slot) {
            return new TrackedShulker(shulker.getItem(), getStoredStacks(shulker), slot);
        }

        private void updateContents(List<ItemStack> contents) {
            this.contents = copyContents(contents);
        }

        private int findIn(PlayerInventory inventory) {
            for (int slot = 0; slot < 36; slot++) {
                if (isMatch(inventory.getStack(slot))) {
                    lastKnownSlot = slot;
                    return slot;
                }
            }

            if (lastKnownSlot >= 0
                    && lastKnownSlot < 36
                    && isSameBox(inventory.getStack(lastKnownSlot))) {
                return lastKnownSlot;
            }
            return -1;
        }

        private boolean isMatch(ItemStack stack) {
            return isSameBox(stack)
                    && areContentsEqual(contents, getStoredStacks(stack));
        }

        private boolean isSameBox(ItemStack stack) {
            return stack.getItem() == boxItem && stack.getCount() == 1;
        }
    }

    private static List<ItemStack> copyContents(List<ItemStack> contents) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : contents) {
            copies.add(stack.copy());
        }
        return copies;
    }

    private static boolean areContentsEqual(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }

        boolean[] matched = new boolean[second.size()];
        for (ItemStack stack : first) {
            boolean found = false;
            for (int index = 0; index < second.size(); index++) {
                if (!matched[index] && ItemStack.areEqual(stack, second.get(index))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
