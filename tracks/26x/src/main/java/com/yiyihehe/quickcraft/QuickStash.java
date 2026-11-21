package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一键回存：
 * - 通过工具页里的单项开关切换启用状态
 * - 对着箱子 / 末影箱 / 潜影盒右键后，把玩家背包内“容器已有种类”的同类物品尽可能放回去
 * - 完成后自动关闭界面
 */
public final class QuickStash implements ClientModInitializer {

    private static boolean lastUseDown;
    private static QuickContainerToolSync.PendingOpen pendingOpen;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        QuickInventoryShulkerTools.onClientTick(client);
        QuickStashShulkerTransfer.onClientTick(client);
        if (QuickStashShulkerTransfer.isRunning()) {
            return;
        }
        if (!QuickCraftConfigs.isQuickStashEnabled()) {
            pendingOpen = null;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (!QuickCraftConfigs.isQuickStashEnabled() || client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown
                && !lastUseDown
                && pendingOpen == null
                && QuickClientScreenAccess.currentScreen(client) == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedBlock(client)) {
            pendingOpen = new QuickContainerToolSync.PendingOpen(client);
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (pendingOpen == null) {
            return;
        }
        if (!pendingOpen.tick(client)) {
            pendingOpen = null;
            return;
        }
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!isSupportedHandler(screen.getMenu())) {
            pendingOpen = null;
            return;
        }
        if (!pendingOpen.inventoryReady(screen)) {
            return;
        }

        pendingOpen = null;
        if (!QuickStashShulkerTransfer.start(screen, -1, true)) {
            stashMatchingPlayerItems(screen);
            closeCurrentScreen(client);
        }
    }

    private boolean isLookingAtSupportedBlock(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.level == null) {
            return false;
        }

        Block block = client.level.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(AbstractContainerMenu handler) {
        return handler instanceof ChestMenu || handler instanceof ShulkerBoxMenu;
    }

    /** Runs the stash transfer without closing the current container screen. */
    public static void stashFromButton(AbstractContainerScreen<?> screen) {
        if (!QuickStashShulkerTransfer.start(screen, -1, false)) {
            new QuickStash().stashMatchingPlayerItems(screen);
        }
    }

    /** Runs the reverse transfer without closing the current container screen. */
    public static void retrieveFromButton(AbstractContainerScreen<?> screen) {
        new QuickStash().retrieveContainerItems(screen);
    }

    void stashMatchingPlayerItems(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        List<ItemStack> containerTemplates = snapshotContainerTemplates(handler);
        if (containerTemplates.isEmpty()) {
            return;
        }

        for (int playerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(playerSlotId);
            if (!isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasItem()
                    || !slot.mayPickup(client.player)) {
                continue;
            }
            if (!matchesAnyTemplate(slot.getItem(), containerTemplates)) {
                continue;
            }

            QuickSlotTransfer.quickMove(client, handler, playerSlotId,
                    target -> !isPlayerStorageSlot(target) && isVisibleSlot(target), false);
        }
    }

    private void retrieveContainerItems(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        List<ItemStack> playerTemplates = snapshotPlayerTemplates(handler);
        if (playerTemplates.isEmpty()) {
            return;
        }

        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasItem()
                    || !slot.mayPickup(client.player)
                    || !matchesAnyTemplate(slot.getItem(), playerTemplates)) {
                continue;
            }

            QuickSlotTransfer.quickMove(client, handler, slot.index, QuickSlotTransfer::isPlayerStorageSlot, true);
        }
    }

    private List<ItemStack> snapshotPlayerTemplates(AbstractContainerMenu handler) {
        List<ItemStack> templates = new ArrayList<>();
        for (int playerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(playerSlotId);
            if (slot.hasItem()) {
                templates.add(slot.getItem().copyWithCount(1));
            }
        }
        return templates;
    }

    private List<ItemStack> snapshotContainerTemplates(AbstractContainerMenu handler) {
        List<ItemStack> templates = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasItem()) {
                continue;
            }
            // 锁住的容器格不参与“已有种类”判定，避免它间接决定回存结果。
            templates.add(slot.getItem().copyWithCount(1));
        }
        return templates;
    }

    private List<Integer> getPlayerStorageSlotIds(AbstractContainerMenu handler) {
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
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return playerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private boolean matchesAnyTemplate(ItemStack stack, List<ItemStack> templates) {
        for (ItemStack template : templates) {
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof net.minecraft.world.entity.player.Inventory
                && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private void closeCurrentScreen(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
        }
    }
}
