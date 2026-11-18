package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** 有目的锁格时用明确的取放点击，避免服务端重新选择 QUICK_MOVE 落点。 */
public final class QuickSlotTransfer {
    private QuickSlotTransfer() {}

    public static boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 36;
    }

    public static void quickMove(Minecraft client, AbstractContainerMenu handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse) {
        if (client.player == null || client.gameMode == null || !handler.getCarried().isEmpty()) return;
        List<Integer> targets = new ArrayList<>();
        boolean hasLock = false;
        for (Slot slot : handler.slots) {
            if (slot.index != sourceId && destination.test(slot)) {
                targets.add(slot.index);
                hasLock |= QuickContainerLock.isLockedSlot(handler, slot);
            }
        }
        if (!hasLock) {
            client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.QUICK_MOVE, client.player);
            return;
        }
        if (reverse) Collections.reverse(targets);
        Slot source = handler.getSlot(sourceId);
        if (QuickContainerLock.isLockedSlot(handler, source) || !source.hasItem() || !source.mayPickup(client.player)) return;
        ItemStack template = source.getItem().copy();
        int capacity = 0;
        for (int id : targets) {
            if (accepts(handler, id, template)) {
                Slot target = handler.getSlot(id);
                capacity += target.getMaxStackSize(template) - target.getItem().getCount();
            }
        }
        if (capacity <= 0 || (!source.mayPlace(template) && capacity < template.getCount())) return;
        client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.PICKUP, client.player);
        if (!ItemStack.isSameItemSameComponents(handler.getCarried(), template)) return;
        fillCursor(client, handler, targets);
        if (!handler.getCarried().isEmpty() && source.mayPlace(handler.getCarried())
                && !QuickContainerLock.isLockedSlot(handler, source)) {
            client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.PICKUP, client.player);
        }
    }

    private static boolean accepts(AbstractContainerMenu handler, int id, ItemStack stack) {
        Slot slot = handler.getSlot(id);
        ItemStack existing = slot.getItem();
        return slot.isActive() && !QuickContainerLock.isLockedSlot(handler, slot) && slot.mayPlace(stack)
                && (existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() < slot.getMaxStackSize(stack)));
    }

    private static void fillCursor(Minecraft client, AbstractContainerMenu handler, List<Integer> targets) {
        for (boolean emptyOnly : new boolean[]{false, true}) {
            for (int id : targets) {
                ItemStack cursor = handler.getCarried();
                if (cursor.isEmpty()) return;
                Slot target = handler.getSlot(id);
                if (target.getItem().isEmpty() != emptyOnly || !accepts(handler, id, cursor)) continue;
                client.gameMode.handleContainerInput(handler.containerId, id, 0, ContainerInput.PICKUP, client.player);
            }
        }
    }
}
