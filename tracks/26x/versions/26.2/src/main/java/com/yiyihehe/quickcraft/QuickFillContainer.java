package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fills the clicked container with the most abundant transferable item in the player inventory. */
public final class QuickFillContainer implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static boolean lastUseDown;
    private static boolean pendingOpen;
    private static int pendingTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled()) {
            pendingOpen = false;
            pendingTicks = 0;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled() || client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown
                && !lastUseDown
                && client.gui.screen() == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedContainer(client)) {
            pendingOpen = true;
            pendingTicks = 0;
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (!pendingOpen) {
            return;
        }

        pendingTicks++;
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                pendingOpen = false;
                pendingTicks = 0;
            }
            return;
        }

        pendingOpen = false;
        pendingTicks = 0;
        if (!hasVisibleContainerSlots(screen.getMenu())) {
            return;
        }
        fillContainer(screen);
        closeCurrentScreen(client);
    }

    private boolean isLookingAtSupportedContainer(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.level == null) {
            return false;
        }

        BlockEntity blockEntity = client.level.getBlockEntity(blockHitResult.getBlockPos());
        if (blockEntity instanceof Container) {
            return true;
        }

        return client.level.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof EnderChestBlock;
    }

    private void fillContainer(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        List<Integer> playerSlotIds = getPlayerStorageSlotIds(handler);
        ItemStack mostAbundant = findMostAbundantStack(handler, playerSlotIds);
        if (mostAbundant.isEmpty()) {
            return;
        }

        for (int playerSlotId : playerSlotIds) {
            Slot slot = handler.getSlot(playerSlotId);
            if (!slot.hasItem()
                    || !slot.mayPickup(client.player)
                    || !ItemStack.isSameItemSameComponents(slot.getItem(), mostAbundant)) {
                continue;
            }

            client.gameMode.handleContainerInput(
                    handler.containerId,
                    playerSlotId,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
        }
    }

    private ItemStack findMostAbundantStack(AbstractContainerMenu handler, List<Integer> playerSlotIds) {
        ItemStack mostAbundant = ItemStack.EMPTY;
        int largestTotal = 0;

        for (int candidateSlotId : playerSlotIds) {
            ItemStack candidate = handler.getSlot(candidateSlotId).getItem();
            if (candidate.isEmpty()) {
                continue;
            }

            int total = 0;
            for (int playerSlotId : playerSlotIds) {
                ItemStack stack = handler.getSlot(playerSlotId).getItem();
                if (ItemStack.isSameItemSameComponents(stack, candidate)) {
                    total += stack.getCount();
                }
            }

            if (total > largestTotal) {
                largestTotal = total;
                mostAbundant = candidate.copyWithCount(1);
            }
        }

        return mostAbundant;
    }

    private boolean hasVisibleContainerSlots(AbstractContainerMenu handler) {
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot) && !isPlayerStorageSlot(slot)) {
                return true;
            }
        }
        return false;
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

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory
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
