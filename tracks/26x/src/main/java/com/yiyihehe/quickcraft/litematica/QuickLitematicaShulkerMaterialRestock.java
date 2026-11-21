package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.util.EquipmentUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

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

    private static final Deque<RetrievedMaterial> retrievedMaterials = new ArrayDeque<>();
    private static PendingRequest pendingRequest;
    private static ActiveAction activeAction;
    private static Operation operation = Operation.IDLE;
    private static int operationTicks;
    private static int closeCursorRetries;
    private static boolean closeSent;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaShulkerMaterialRestock::onClientTick);
    }

    /**
     * @return true 表示已经接管本次缺料选取，调用方不应继续尝试普通换手。
     */
    public static boolean requestMissingMaterial(ItemStack requiredStack) {
        Minecraft client = Minecraft.getInstance();
        if (!canHandleRequest(client, requiredStack)) {
            return false;
        }

        if (hasPlayerMaterial(client.player.getInventory(), requiredStack)) {
            return false;
        }

        if (operation != Operation.IDLE || pendingRequest != null) {
            return true;
        }

        Inventory inventory = client.player.getInventory();
        if (QuickPortableStorageTransfer.isRunning()) {
            if (findShulkerWithMaterial(inventory, requiredStack) == -1
                    || !QuickPortableStorageTransfer.yieldHandRestockToEasyPlace()) {
                return false;
            }
            pendingRequest = new PendingRequest(copyTemplate(requiredStack));
            return true;
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
    public static boolean requestMaterialForEasyPlaceTarget(Minecraft client) {
        return QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()
                && requestMissingMaterial(getEasyPlaceTargetMaterial(client));
    }

    public static boolean isRestockInProgress() {
        return pendingRequest != null || operation != Operation.IDLE;
    }

    public static boolean takesPriorityOverHandRestock(Minecraft client) {
        return isRestockInProgress()
                || QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()
                && Configs.Generic.EASY_PLACE_MODE.getBooleanValue()
                && !getEasyPlaceTargetMaterial(client).isEmpty()
                && !QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(client);
    }

    private static ItemStack getEasyPlaceTargetMaterial(Minecraft client) {
        if (client == null
                || client.player == null
                || client.level == null
                || !(client.hitResult instanceof BlockHitResult hitResult)
                || hitResult.getType() != HitResult.Type.BLOCK) {
            return ItemStack.EMPTY;
        }

        BlockPlaceContext placementContext = new BlockPlaceContext(
                new UseOnContext(client.player, InteractionHand.MAIN_HAND, hitResult)
        );
        BlockPos position = placementContext.getClickedPos();
        Level schematicLevel = SchematicWorldHandler.getSchematicWorld();
        if (schematicLevel == null) {
            return ItemStack.EMPTY;
        }
        BlockState schematicState = schematicLevel.getBlockState(position);

        if (schematicState.isAir()
                || !DataManager.getRenderLayerRange().isPositionWithinRange(position)
                || !client.level.getBlockState(position).canBeReplaced(placementContext)) {
            return ItemStack.EMPTY;
        }

        return MaterialCache.getInstance().getRequiredBuildItemForState(schematicState);
    }

    private static void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()
                || client.player == null
                || client.level == null) {
            resetState(client);
            return;
        }

        switch (operation) {
            case IDLE -> {
                if (pendingRequest != null
                        && !QuickPortableStorageTransfer.isRunning()
                        && !hasOpenScreen(client)
                        && !startPendingRequest(client)) {
                    pendingRequest = null;
                }
            }
            case WAITING_FOR_OPEN -> waitForOpenedShulker(client);
            case WAITING_FOR_CLOSE -> processClosingShulker(client);
        }
    }

    /**
     * Quick Shulker 打开的是后台 AbstractContainerMenu；容器内容由网络包到达后再执行取放。
     */
    public static void onShulkerContentsReceived(int syncId) {
        Minecraft client = Minecraft.getInstance();
        if (operation != Operation.WAITING_FOR_OPEN
                || activeAction == null
                || client.player == null
                || hasOpenScreen(client)
                || !(client.player.containerMenu instanceof ShulkerBoxMenu handler)
                || handler.containerId != syncId) {
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

    private static boolean canHandleRequest(Minecraft client, ItemStack requiredStack) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()) {
            return false;
        }
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()) {
            return false;
        }
        if (client == null || client.player == null || client.level == null) {
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

    private static boolean hasOpenScreen(Minecraft client) {
        return QuickClientScreenAccess.currentScreen(client) != null;
    }

    private static boolean startPendingRequest(Minecraft client) {
        if (pendingRequest == null || client.player == null || hasOpenScreen(client)) {
            return false;
        }

        Inventory inventory = client.player.getInventory();
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

            TrackedShulker source = TrackedShulker.from(inventory.getItem(sourceShulkerSlot), sourceShulkerSlot);
            if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
                RetrievedMaterial retrievedMaterial = new RetrievedMaterial(copyTemplate(pendingRequest.template()), source);
                if (hasEmptyContainerSlot(inventory.getItem(sourceShulkerSlot))) {
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
                    new RetrievedMaterial(copyTemplate(pendingRequest.template()), source),
                    targetHotbarSlot
            );
        }

        int shulkerSlot = findShulkerWithMaterial(inventory, pendingRequest.template());
        int targetHotbarSlot = findLitematicaPickBlockTarget(inventory);
        if (shulkerSlot == -1 || targetHotbarSlot == -1) {
            return false;
        }

        TrackedShulker source = TrackedShulker.from(inventory.getItem(shulkerSlot), shulkerSlot);
        return openShulker(
                client,
                shulkerSlot,
                ActionType.EXTRACT,
                new RetrievedMaterial(copyTemplate(pendingRequest.template()), source),
                targetHotbarSlot
        );
    }

    private static void waitForOpenedShulker(Minecraft client) {
        if (activeAction == null || client.player == null) {
            resetState(client);
            return;
        }

        if (hasOpenScreen(client)) {
            resetState(client);
            return;
        }

        if (++operationTicks > OPEN_TIMEOUT_TICKS) {
            resetState(client);
        }
    }

    private static void extractMaterial(Minecraft client,
                                        ShulkerBoxMenu handler,
                                        ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.gameMode == null
                || !handler.getCarried().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findEmptyPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.mayPickup(client.player)) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, sourceSlot.index);
        clickSlot(client, handler, destinationSlot.index);
        if (!destinationSlot.hasItem() || !destinationSlot.getItem().is(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        ItemStack extractedStack = destinationSlot.getItem();
        if (destinationSlot.getContainerSlot() != action.targetHotbarSlot()) {
            if (!moveExtractedMaterialToHotbar(client, handler, destinationSlot, action.targetHotbarSlot())) {
                pendingRequest = null;
                return;
            }
            Slot targetSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
            if (targetSlot == null || !targetSlot.hasItem()) {
                pendingRequest = null;
                return;
            }
            extractedStack = targetSlot.getItem();
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material().withIdentity(extractedStack));
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    private static boolean moveExtractedMaterialToHotbar(Minecraft client,
                                                          ShulkerBoxMenu handler,
                                                          Slot sourceSlot,
                                                          int targetHotbarSlot) {
        Slot targetSlot = findPlayerStorageSlot(handler, targetHotbarSlot);
        if (targetSlot == null) {
            return false;
        }

        clickSlot(client, handler, sourceSlot.index);
        if (!handler.getCarried().is(pendingRequest.template().getItem())) {
            return false;
        }

        clickSlot(client, handler, targetSlot.index);
        if (!targetSlot.hasItem() || !targetSlot.getItem().is(pendingRequest.template().getItem())) {
            return false;
        }

        if (!handler.getCarried().isEmpty()) {
            clickSlot(client, handler, sourceSlot.index);
        }
        return handler.getCarried().isEmpty();
    }

    /**
     * 背包没有空格时，把目标材料直接换入 Litematica 允许的快捷栏格，
     * 同一后台容器操作把被换下的物品放进刚腾空的潜影盒格，避免原版 pick block 选到空手。
     */
    private static void replaceHotbarMaterial(Minecraft client,
                                              ShulkerBoxMenu handler,
                                              ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.gameMode == null
                || !handler.getCarried().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.mayPickup(client.player)) {
            pendingRequest = null;
            return;
        }
        boolean replacedExistingStack = destinationSlot.hasItem();
        clickSlot(client, handler, sourceSlot.index);
        if (!handler.getCarried().is(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, destinationSlot.index);
        if (!destinationSlot.hasItem() || !destinationSlot.getItem().is(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        if (replacedExistingStack) {
            clickSlot(client, handler, sourceSlot.index);
            if (!handler.getCarried().isEmpty()) {
                pendingRequest = null;
                return;
            }
        } else {
            action.material().source().updateContents(getContainerContents(handler));
            retrievedMaterials.addLast(action.material().withIdentity(destinationSlot.getItem()));
        }

        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    /**
     * 目标来源盒满时，先腾出目标快捷栏格；下一次后台开箱会从原来源盒取料。
     */
    private static void stashHotbarMaterial(Minecraft client,
                                            ShulkerBoxMenu handler,
                                            ActiveAction action) {
        if (client.player == null
                || client.gameMode == null
                || !handler.getCarried().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            return;
        }

        Slot sourceSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.hasItem() || !sourceSlot.mayPickup(client.player)) {
            return;
        }

        clickSlot(client, handler, sourceSlot.index);
        clickSlot(client, handler, destinationSlot.index);
    }

    /**
     * 来源盒原本有空位时，先存旧物、再取材料，保留材料原槽位给后续有序回塞。
     */
    private static void stashHotbarAndExtractMaterial(Minecraft client,
                                                       ShulkerBoxMenu handler,
                                                       ActiveAction action) {
        if (pendingRequest == null
                || action.material() == null
                || client.player == null
                || client.gameMode == null
                || !handler.getCarried().isEmpty()
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
                || !playerSlot.hasItem()
                || !playerSlot.mayPickup(client.player)
                || !materialSlot.mayPickup(client.player)) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, playerSlot.index);
        clickSlot(client, handler, stashSlot.index);
        if (!handler.getCarried().isEmpty()) {
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, materialSlot.index);
        clickSlot(client, handler, playerSlot.index);
        if (!playerSlot.hasItem() || !playerSlot.getItem().is(pendingRequest.template().getItem())) {
            pendingRequest = null;
            return;
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material().withIdentity(playerSlot.getItem()));
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        pendingRequest = null;
    }

    private static void returnMaterial(Minecraft client,
                                       ShulkerBoxMenu handler,
                                       RetrievedMaterial material) {
        if (client.player == null || client.gameMode == null || !handler.getCarried().isEmpty()) {
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        Slot sourceSlot = findPlayerMaterialSlot(handler, material.identity());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.mayPickup(client.player)) {
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        clickSlot(client, handler, sourceSlot.index);
        clickSlot(client, handler, destinationSlot.index);
        material.source().updateContents(getContainerContents(handler));
        retrievedMaterials.removeFirstOccurrence(material);
    }

    private static void processClosingShulker(Minecraft client) {
        if (activeAction == null || client.player == null) {
            resetState(client);
            return;
        }

        if (client.player.containerMenu.containerId != activeAction.syncId()) {
            finishActiveAction();
            return;
        }

        if (!closeSent) {
            if (++operationTicks <= 2) {
                return;
            }

            if (!client.player.containerMenu.getCarried().isEmpty()
                    && ++closeCursorRetries < CLOSE_CURSOR_RETRIES) {
                return;
            }

            client.player.closeContainer();
            closeSent = true;
            operationTicks = 0;
            return;
        }

        if (++operationTicks > CLOSE_TIMEOUT_TICKS) {
            finishActiveAction();
        }
    }

    private static boolean openShulker(Minecraft client,
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

        return true;
    }

    private static void beginClosing(AbstractContainerMenu handler) {
        activeAction = new ActiveAction(
                activeAction.type(),
                activeAction.material(),
                activeAction.targetHotbarSlot(),
                handler.containerId
        );
        operation = Operation.WAITING_FOR_CLOSE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
    }

    private static void finishActiveAction() {
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
    }

    private static void resetState(Minecraft client) {
        if (activeAction != null
                && activeAction.syncId() >= 0
                && client != null
                && client.player != null
                && client.player.containerMenu.containerId == activeAction.syncId()) {
            client.player.closeContainer();
        }

        pendingRequest = null;
        retrievedMaterials.clear();
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
    }

    private static boolean canUseQuickShulker() {
        return QuickShulkerConnection.canOpen();
    }

    private static boolean sendOpenQuickShulkerPacket(int slotId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }

        int handlerSlotId = findCurrentHandlerSlotId(client.player.containerMenu, slotId);
        if (handlerSlotId == -1) {
            return false;
        }

        return QuickShulkerConnection.sendOpen(handlerSlotId);
    }

    /**
     * Quick Shulker 服务端按 PlayerAbstractContainerMenu 的槽位 ID 解析开箱包：主背包是 9-35，
     * 快捷栏则是 36-44，不能直接把 Inventory 的 0-8 发过去。
     */
    private static int findCurrentHandlerSlotId(AbstractContainerMenu handler, int playerInventorySlot) {
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == playerInventorySlot) {
                return slot.index;
            }
        }
        return -1;
    }

    private static int findShulkerWithMaterial(Inventory inventory, ItemStack template) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack shulker = inventory.getItem(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && containsMaterial(shulker, template)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findShulkerWithSpace(Inventory inventory, int excludedSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludedSlot) {
                continue;
            }

            ItemStack shulker = inventory.getItem(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && hasEmptyContainerSlot(shulker)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findReturnShulker(Inventory inventory, RetrievedMaterial material) {
        if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
            int trackedSlot = material.source().findIn(inventory);
            if (trackedSlot != -1 && hasEmptyContainerSlot(inventory.getItem(trackedSlot))) {
                return trackedSlot;
            }

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (isShulkerBox(stack)
                        && stack.getCount() == 1
                        && getStoredStacks(stack).size() < 27
                        && material.source().isSameBox(stack)) {
                    return slot;
                }
            }

            return -1;
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isShulkerBox(stack) && stack.getCount() == 1 && getStoredStacks(stack).size() < 27) {
                return slot;
            }
        }
        return -1;
    }

    private static RetrievedMaterial getReturnCandidate(Inventory inventory) {
        while (!retrievedMaterials.isEmpty()) {
            RetrievedMaterial candidate = retrievedMaterials.peekFirst();
            if (hasPlayerStack(inventory, candidate.identity())) {
                return candidate;
            }
            retrievedMaterials.removeFirst();
        }
        return null;
    }

    private static boolean hasPlayerMaterial(Inventory inventory, ItemStack template) {
        return hasPlayerItem(inventory, template.getItem());
    }

    private static boolean hasPlayerItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < 36; slot++) {
            if (!QuickContainerLock.isLockedPlayerInventorySlot(slot) && inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlayerStack(Inventory inventory, ItemStack identity) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!QuickContainerLock.isLockedPlayerInventorySlot(slot)
                    && !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, identity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventoryFull(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (!QuickContainerLock.isLockedPlayerInventorySlot(slot) && inventory.getItem(slot).isEmpty()) {
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
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack stack : container.nonEmptyItemCopyStream().toList()) {
            contents.add(stack.copy());
        }
        return contents;
    }

    private static boolean hasEmptyContainerSlot(ItemStack shulker) {
        return getStoredStacks(shulker).size() < 27;
    }

    private static List<ItemStack> getContainerContents(AbstractContainerMenu handler) {
        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasItem()) {
                contents.add(slot.getItem().copy());
            }
        }
        return contents;
    }

    private static Slot findContainerMaterialSlot(ShulkerBoxMenu handler, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasItem() && slot.getItem().is(template.getItem())) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyContainerSlot(ShulkerBoxMenu handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasItem() && slot.isActive()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyPlayerStorageSlot(ShulkerBoxMenu handler, int preferredHotbarSlot) {
        Slot preferredSlot = findPlayerStorageSlot(handler, preferredHotbarSlot);
        if (preferredSlot != null && !preferredSlot.hasItem() && preferredSlot.isActive()) {
            return preferredSlot;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() && slot.isActive()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findPlayerStorageSlot(ShulkerBoxMenu handler, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) {
            return null;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getContainerSlot() == inventorySlot) {
                return slot;
            }
        }
        return null;
    }

    private static int findLitematicaPickBlockTarget(Inventory inventory) {
        List<Integer> configuredSlots = getLitematicaPickBlockSlots();
        return selectLitematicaPickBlockTarget(
                inventory,
                configuredSlots,
                inventory.getSelectedSlot(),
                Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue(),
                Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue()
        );
    }

    static int selectLitematicaPickBlockTarget(Inventory inventory,
                                               List<Integer> configuredSlots,
                                               int selectedSlot,
                                               boolean avoidDamageable,
                                               boolean avoidTools) {
        if (configuredSlots.isEmpty()) {
            return -1;
        }

        if (configuredSlots.contains(selectedSlot)
                && !QuickContainerLock.isLockedPlayerHotbarSlot(selectedSlot)
                && isLitematicaPickBlockTarget(inventory.getItem(selectedSlot), avoidDamageable, avoidTools)) {
            return selectedSlot;
        }

        for (int slot : configuredSlots) {
            if (!QuickContainerLock.isLockedPlayerHotbarSlot(slot)
                    && isLitematicaPickBlockTarget(inventory.getItem(slot), avoidDamageable, avoidTools)) {
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

    private static boolean isLitematicaPickBlockTarget(Inventory inventory, int slot) {
        return !QuickContainerLock.isLockedPlayerHotbarSlot(slot)
                && isLitematicaPickBlockTarget(
                inventory.getItem(slot),
                Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue(),
                Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue()
        );
    }

    private static boolean isLitematicaPickBlockTarget(ItemStack stack,
                                                       boolean avoidDamageable,
                                                       boolean avoidTools) {
        return !isShulkerBox(stack)
                && (!avoidDamageable || !stack.isDamageableItem())
                && (!avoidTools || !EquipmentUtils.isRegularTool(stack));
    }

    private static Slot findPlayerMaterialSlot(ShulkerBoxMenu handler, ItemStack identity) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), identity)) {
                return slot;
            }
        }
        return null;
    }

    private static List<Slot> getContainerSlots(AbstractContainerMenu handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!(slot.container instanceof Inventory) && slot.isActive()
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt((Slot slot) -> slot.getContainerSlot()).thenComparingInt(slot -> slot.index));
        return slots;
    }

    private static List<Slot> getPlayerStorageSlots(AbstractContainerMenu handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory
                    && slot.getContainerSlot() >= 0
                    && slot.getContainerSlot() < 36
                    && slot.isActive()
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt(Slot::getContainerSlot).thenComparingInt(slot -> slot.index));
        return slots;
    }

    private static void clickSlot(Minecraft client, AbstractContainerMenu handler, int slotId) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                0,
                ContainerInput.PICKUP,
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

    private static ItemStack boxIdentity(ItemStack stack) {
        ItemStack identity = copyTemplate(stack);
        identity.remove(DataComponents.CONTAINER);
        return identity;
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

    private record RetrievedMaterial(ItemStack identity, TrackedShulker source) {
        private RetrievedMaterial withIdentity(ItemStack stack) {
            return new RetrievedMaterial(copyTemplate(stack), source);
        }
    }

    private static final class TrackedShulker {
        private final ItemStack identity;
        private List<ItemStack> contents;
        private int lastKnownSlot;

        private TrackedShulker(ItemStack identity, List<ItemStack> contents, int lastKnownSlot) {
            this.identity = identity;
            this.contents = contents;
            this.lastKnownSlot = lastKnownSlot;
        }

        private static TrackedShulker from(ItemStack shulker, int slot) {
            return new TrackedShulker(boxIdentity(shulker), getStoredStacks(shulker), slot);
        }

        private void updateContents(List<ItemStack> contents) {
            this.contents = copyContents(contents);
        }

        private int findIn(Inventory inventory) {
            if (lastKnownSlot >= 0
                    && lastKnownSlot < 36
                    && isMatch(inventory.getItem(lastKnownSlot))) {
                return lastKnownSlot;
            }

            for (int slot = 0; slot < 36; slot++) {
                if (slot != lastKnownSlot && isMatch(inventory.getItem(slot))) {
                    lastKnownSlot = slot;
                    return slot;
                }
            }
            // 先找盒体和内容都吻合的来源，再保留原位置内容变化后的既有回退。
            if (lastKnownSlot >= 0 && lastKnownSlot < 36
                    && isSameBox(inventory.getItem(lastKnownSlot))) {
                return lastKnownSlot;
            }
            return -1;
        }

        private boolean isMatch(ItemStack stack) {
            return isSameBox(stack)
                    && areContentsEqual(contents, getStoredStacks(stack));
        }

        private boolean isSameBox(ItemStack stack) {
            return stack.getCount() == 1 && ItemStack.isSameItemSameComponents(identity, boxIdentity(stack));
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
                if (!matched[index] && ItemStack.matches(stack, second.get(index))) {
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
