package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** 有目的锁格时用明确的取放点击，避免服务端重新选择 QUICK_MOVE 落点。 */
public final class QuickSlotTransfer {
    private QuickSlotTransfer() {}

    public static boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory && slot.getIndex() >= 0 && slot.getIndex() < 36;
    }

    public static int moveAmount(MinecraftClient client, ScreenHandler menu, Slot source, Slot target, int amount) {
        ItemStack stack = source.getStack();
        if (amount <= 0 || source == target || stack.isEmpty() || !menu.getCursorStack().isEmpty()
                || !source.canTakeItems(client.player) || QuickContainerLock.isLockedSlot(menu, source)
                || !accepts(menu, target.id, stack)) {
            return 0;
        }
        int before = target.getStack().getCount();
        int capacity = target.getMaxItemCount(stack) - before;
        int moved = Math.min(Math.min(stack.getCount(), amount), capacity);
        if (QuickShulkerClickSafety.isBundlingConflict(stack) && moved < capacity && moved < stack.getCount()) {
            return QuickShulkerClickSafety.moveExactAmountToSlot(menu, source.id, target.id, moved, client);
        }
        ItemStack original = stack.copy();
        click(client, menu, source, 0);
        if (!menu.getCursorStack().isEmpty()) {
            placeCursorAmount(client, menu, target, moved == capacity ? original.getCount() : moved);
        }
        if (!menu.getCursorStack().isEmpty()) {
            click(client, menu, source, 0);
        }
        return Math.max(0, target.getStack().getCount() - before);
    }

    static void placeCursorAmount(MinecraftClient client, ScreenHandler menu, Slot target, int amount) {
        if (menu.getCursorStack().getCount() <= amount) {
            click(client, menu, target, 0);
        } else {
            for (int i = 0; i < amount; i++) {
                click(client, menu, target, 1);
            }
        }
    }

    private static void click(MinecraftClient client, ScreenHandler menu, Slot slot, int button) {
        client.interactionManager.clickSlot(menu.syncId, slot.id, button, SlotActionType.PICKUP, client.player);
    }

    public static void quickMove(MinecraftClient client, ScreenHandler handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse) {
        quickMove(client, handler, sourceId, destination, reverse, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    public static void quickMove(MinecraftClient client, ScreenHandler handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse, Predicate<Slot> locked) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) return;
        List<Integer> targets = new ArrayList<>();
        boolean hasLock = false;
        for (Slot slot : handler.slots) {
            if (slot.id != sourceId && destination.test(slot)) {
                targets.add(slot.id);
                hasLock |= locked.test(slot);
            }
        }
        if (!hasLock) {
            client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.QUICK_MOVE, client.player);
            return;
        }
        if (reverse) Collections.reverse(targets);
        moveWithPickup(client, handler, sourceId, targets, locked);
    }

    static boolean moveResultWithPickup(MinecraftClient client, ScreenHandler handler, int sourceId) {
        if (!handler.getCursorStack().isEmpty()) return false;
        List<Integer> targets = new ArrayList<>();
        for (int i = handler.slots.size() - 1; i >= 0; i--) {
            Slot target = handler.getSlot(i);
            if (i != sourceId && isPlayerStorageSlot(target)) {
                targets.add(i);
            }
        }
        return moveWithPickup(client, handler, sourceId, targets, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    static boolean tryMoveToWorkbenchGrid(MinecraftClient client, ScreenHandler handler, Slot source) {
        var layout = QuickCraftMouseCraftLayout.WORKBENCH;
        ItemStack stack = source.getStack();
        List<Integer> targets = new ArrayList<>();
        // 原版 QUICK_MOVE 循环先合并同类堆叠，再按合成格顺序填空格。
        for (int i = 0; i < layout.gridSize(); i++) {
            int id = layout.gridSlotId(i);
            if (accepts(handler, id, stack)) {
                targets.add(id);
            }
        }
        if (targets.isEmpty()) return false;
        moveWithPickup(client, handler, source.id, targets, slot -> QuickContainerLock.isLockedSlot(handler, slot));
        return true;
    }

    private static boolean moveWithPickup(MinecraftClient client, ScreenHandler handler, int sourceId,
                                          List<Integer> targets, Predicate<Slot> locked) {
        Slot source = handler.getSlot(sourceId);
        if (locked.test(source) || !source.hasStack() || !source.canTakeItems(client.player)) return false;
        ItemStack template = source.getStack().copy();
        if (handler instanceof CartographyTableScreenHandler && sourceId == 2
                && template.remove(DataComponentTypes.MAP_POST_PROCESSING) != null) {
            // 缩放和锁定地图会在领取时更换服务端地图 ID，预留空格避免误合并。
            targets = targets.stream().filter(id -> !handler.getSlot(id).hasStack()).toList();
        }
        int capacity = 0;
        for (int id : targets) {
            if (accepts(handler, id, template, locked)) {
                Slot target = handler.getSlot(id);
                capacity += target.getMaxItemCount(template) - target.getStack().getCount();
            }
        }
        if (capacity <= 0 || (!source.canInsert(template) && capacity < template.getCount())) return false;
        client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, client.player);
        if (!ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) return false;
        fillCursor(client, handler, targets, locked);
        if (handler.getCursorStack().isEmpty()) return true;
        if (source.canInsert(handler.getCursorStack()) && !locked.test(source)) {
            client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, client.player);
        }
        return false;
    }

    private static boolean accepts(ScreenHandler handler, int id, ItemStack stack) {
        return accepts(handler, id, stack, slot -> QuickContainerLock.isLockedSlot(handler, slot));
    }

    private static boolean accepts(ScreenHandler handler, int id, ItemStack stack, Predicate<Slot> locked) {
        Slot slot = handler.getSlot(id);
        ItemStack existing = slot.getStack();
        return slot.isEnabled() && !locked.test(slot) && slot.canInsert(stack)
                && (existing.isEmpty() || (ItemStack.areItemsAndComponentsEqual(existing, stack)
                && existing.getCount() < slot.getMaxItemCount(stack)));
    }

    private static void fillCursor(MinecraftClient client, ScreenHandler handler, List<Integer> targets,
                                   Predicate<Slot> locked) {
        for (boolean emptyOnly : new boolean[]{false, true}) {
            for (int id : targets) {
                ItemStack cursor = handler.getCursorStack();
                if (cursor.isEmpty()) return;
                Slot target = handler.getSlot(id);
                if (target.getStack().isEmpty() != emptyOnly || !accepts(handler, id, cursor, locked)) continue;
                client.interactionManager.clickSlot(handler.syncId, id, 0, SlotActionType.PICKUP, client.player);
            }
        }
    }
}
