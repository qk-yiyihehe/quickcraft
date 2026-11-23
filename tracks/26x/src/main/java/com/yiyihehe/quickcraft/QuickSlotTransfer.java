package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
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

    public static int moveAmount(Minecraft client, AbstractContainerMenu menu, Slot source, Slot target, int amount) {
        ItemStack stack = source.getItem();
        if (amount <= 0 || source == target || stack.isEmpty() || !menu.getCarried().isEmpty()
                || !source.mayPickup(client.player) || QuickContainerLock.isLockedSlot(menu, source)
                || !accepts(menu, target.index, stack)) {
            return 0;
        }
        int before = target.getItem().getCount();
        int capacity = target.getMaxStackSize(stack) - before;
        int moved = Math.min(Math.min(stack.getCount(), amount), capacity);
        if (QuickShulkerClickSafety.isBundlingConflict(stack) && moved < capacity && moved < stack.getCount()) {
            return QuickShulkerClickSafety.moveExactAmountToSlot(menu, source.index, target.index, moved, client);
        }
        ItemStack original = stack.copy();
        click(client, menu, source, 0);
        if (!menu.getCarried().isEmpty()) {
            placeCursorAmount(client, menu, target, moved == capacity ? original.getCount() : moved);
        }
        if (!menu.getCarried().isEmpty()) {
            click(client, menu, source, 0);
        }
        return Math.max(0, target.getItem().getCount() - before);
    }

    static void placeCursorAmount(Minecraft client, AbstractContainerMenu menu, Slot target, int amount) {
        if (menu.getCarried().getCount() <= amount) {
            click(client, menu, target, 0);
        } else {
            for (int i = 0; i < amount; i++) {
                click(client, menu, target, 1);
            }
        }
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, Slot slot, int button) {
        client.gameMode.handleContainerInput(menu.containerId, slot.index, button, ContainerInput.PICKUP, client.player);
    }

    public static void quickMove(Minecraft client, AbstractContainerMenu handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse) {
        quickMove(client, handler, sourceId, destination, reverse, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    public static void quickMove(Minecraft client, AbstractContainerMenu handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse, Predicate<Slot> locked) {
        if (client.player == null || client.gameMode == null || !handler.getCarried().isEmpty()) return;
        List<Integer> targets = new ArrayList<>();
        boolean hasLock = false;
        for (Slot slot : handler.slots) {
            if (slot.index != sourceId && destination.test(slot)) {
                targets.add(slot.index);
                hasLock |= locked.test(slot);
            }
        }
        if (!hasLock) {
            client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.QUICK_MOVE, client.player);
            return;
        }
        if (reverse) Collections.reverse(targets);
        moveWithPickup(client, handler, sourceId, targets, locked);
    }

    static boolean moveResultWithPickup(Minecraft client, AbstractContainerMenu handler, int sourceId) {
        if (!handler.getCarried().isEmpty()) return false;
        List<Integer> targets = new ArrayList<>();
        for (int i = handler.slots.size() - 1; i >= 0; i--) {
            Slot target = handler.getSlot(i);
            if (i != sourceId && isPlayerStorageSlot(target)) {
                targets.add(i);
            }
        }
        return moveWithPickup(client, handler, sourceId, targets, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    static boolean tryMoveToWorkbenchGrid(Minecraft client, AbstractContainerMenu handler, Slot source) {
        var layout = QuickCraftMouseCraftLayout.WORKBENCH;
        ItemStack stack = source.getItem();
        List<Integer> targets = new ArrayList<>();
        // 原版 QUICK_MOVE 循环先合并同类堆叠，再按合成格顺序填空格。
        for (int i = 0; i < layout.gridSize(); i++) {
            int id = layout.gridSlotId(i);
            if (accepts(handler, id, stack)) {
                targets.add(id);
            }
        }
        if (targets.isEmpty()) return false;
        moveWithPickup(client, handler, source.index, targets, slot -> QuickContainerLock.isLockedSlot(handler, slot));
        return true;
    }

    private static boolean moveWithPickup(Minecraft client, AbstractContainerMenu handler, int sourceId,
                                          List<Integer> targets, Predicate<Slot> locked) {
        Slot source = handler.getSlot(sourceId);
        if (locked.test(source) || !source.hasItem() || !source.mayPickup(client.player)) return false;
        ItemStack template = source.getItem().copy();
        if (handler instanceof CartographyTableMenu && sourceId == 2
                && template.remove(DataComponents.MAP_POST_PROCESSING) != null) {
            // 缩放和锁定地图会在领取时更换服务端地图 ID，预留空格避免误合并。
            targets = targets.stream().filter(id -> !handler.getSlot(id).hasItem()).toList();
        }
        int capacity = 0;
        for (int id : targets) {
            if (accepts(handler, id, template, locked)) {
                Slot target = handler.getSlot(id);
                capacity += target.getMaxStackSize(template) - target.getItem().getCount();
            }
        }
        if (capacity <= 0 || (!source.mayPlace(template) && capacity < template.getCount())) return false;
        client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.PICKUP, client.player);
        if (!ItemStack.isSameItemSameComponents(handler.getCarried(), template)) return false;
        fillCursor(client, handler, targets, locked);
        if (handler.getCarried().isEmpty()) return true;
        if (source.mayPlace(handler.getCarried()) && !locked.test(source)) {
            client.gameMode.handleContainerInput(handler.containerId, sourceId, 0, ContainerInput.PICKUP, client.player);
        }
        return false;
    }

    private static boolean accepts(AbstractContainerMenu handler, int id, ItemStack stack) {
        return accepts(handler, id, stack, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    private static boolean accepts(AbstractContainerMenu handler, int id, ItemStack stack, Predicate<Slot> locked) {
        Slot slot = handler.getSlot(id);
        ItemStack existing = slot.getItem();
        return slot.isActive() && !locked.test(slot) && slot.mayPlace(stack)
                && (existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() < slot.getMaxStackSize(stack)));
    }

    private static void fillCursor(Minecraft client, AbstractContainerMenu handler, List<Integer> targets,
                                   Predicate<Slot> locked) {
        for (boolean emptyOnly : new boolean[]{false, true}) {
            for (int id : targets) {
                ItemStack cursor = handler.getCarried();
                if (cursor.isEmpty()) return;
                Slot target = handler.getSlot(id);
                if (target.getItem().isEmpty() != emptyOnly || !accepts(handler, id, cursor, locked)) continue;
                client.gameMode.handleContainerInput(handler.containerId, id, 0, ContainerInput.PICKUP, client.player);
            }
        }
    }
}
