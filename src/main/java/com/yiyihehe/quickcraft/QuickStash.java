package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

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

    private void onClientTick(MinecraftClient client) {
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

    private void handleUseAttempt(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickStashEnabled() || client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (useDown
                && !lastUseDown
                && pendingOpen == null
                && client.currentScreen == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedBlock(client)) {
            pendingOpen = new QuickContainerToolSync.PendingOpen(client);
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (pendingOpen == null) {
            return;
        }
        if (!pendingOpen.tick(client)) {
            pendingOpen = null;
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            return;
        }
        if (!isSupportedHandler(screen.getScreenHandler())) {
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

    private boolean isLookingAtSupportedBlock(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return false;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    /** 保持当前容器界面打开，将物品存入容器。 */
    public static void stashFromButton(HandledScreen<?> screen) {
        if (!QuickStashShulkerTransfer.start(screen, -1, false)) {
            new QuickStash().stashMatchingPlayerItems(screen);
        }
    }

    /** 保持当前容器界面打开，将物品反向转回玩家物品栏。 */
    public static void retrieveFromButton(HandledScreen<?> screen) {
        new QuickStash().retrieveContainerItems(screen);
    }

    void stashMatchingPlayerItems(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        List<ItemStack> containerTemplates = snapshotContainerTemplates(handler);
        if (containerTemplates.isEmpty()) {
            return;
        }

        for (int playerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(playerSlotId);
            if (!isPlayerStorageSlot(slot) || QuickContainerLock.isLockedSlot(handler, slot) || !slot.hasStack()
                    || !slot.canTakeItems(client.player)) {
                continue;
            }
            if (!matchesAnyTemplate(slot.getStack(), containerTemplates)) {
                continue;
            }

            QuickSlotTransfer.quickMove(
                    client,
                    handler,
                    playerSlotId,
                    target -> !isPlayerStorageSlot(target) && isVisibleSlot(target),
                    false
            );
        }
    }

    private void retrieveContainerItems(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
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
                    || !slot.hasStack()
                    || !slot.canTakeItems(client.player)
                    || !matchesAnyTemplate(slot.getStack(), playerTemplates)) {
                continue;
            }

            QuickSlotTransfer.quickMove(client, handler, slot.id, QuickSlotTransfer::isPlayerStorageSlot, true);
        }
    }

    private List<ItemStack> snapshotPlayerTemplates(ScreenHandler handler) {
        List<ItemStack> templates = new ArrayList<>();
        for (int playerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(playerSlotId);
            if (slot.hasStack()) {
                templates.add(slot.getStack().copyWithCount(1));
            }
        }
        return templates;
    }

    private List<ItemStack> snapshotContainerTemplates(ScreenHandler handler) {
        List<ItemStack> templates = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot) || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasStack()) {
                continue;
            }
            // 锁住的容器格不参与“已有种类”判定，避免它间接决定回存结果。
            templates.add(slot.getStack().copyWithCount(1));
        }
        return templates;
    }

    private List<Integer> getPlayerStorageSlotIds(ScreenHandler handler) {
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || !isPlayerStorageSlot(slot) || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            playerSlots.add(slot);
        }

        playerSlots.sort(
                Comparator.comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1).thenComparingInt(Slot::getIndex)
                        .thenComparingInt(slot -> slot.id)
        );

        return playerSlots.stream().map(slot -> slot.id).toList();
    }

    private boolean matchesAnyTemplate(ItemStack stack, List<ItemStack> templates) {
        for (ItemStack template : templates) {
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof net.minecraft.entity.player.PlayerInventory && slot.getIndex() >= 0 && slot
                .getIndex() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private void closeCurrentScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }
}
