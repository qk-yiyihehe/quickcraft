package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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

    public static void quickMove(MinecraftClient client, ScreenHandler handler, int sourceId,
                                 Predicate<Slot> destination, boolean reverse) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) return;
        List<Integer> targets = new ArrayList<>();
        boolean hasLock = false;
        for (Slot slot : handler.slots) {
            if (slot.id != sourceId && destination.test(slot)) {
                targets.add(slot.id);
                hasLock |= QuickContainerLock.isLockedSlot(handler, slot);
            }
        }
        if (!hasLock) {
            client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.QUICK_MOVE, client.player);
            return;
        }
        if (reverse) Collections.reverse(targets);
        Slot source = handler.getSlot(sourceId);
        if (QuickContainerLock.isLockedSlot(handler, source) || !source.hasStack() || !source.canTakeItems(client.player)) return;
        ItemStack template = source.getStack().copy();
        int capacity = 0;
        for (int id : targets) {
            if (accepts(handler, id, template)) {
                Slot target = handler.getSlot(id);
                capacity += target.getMaxItemCount(template) - target.getStack().getCount();
            }
        }
        if (capacity <= 0 || (!source.canInsert(template) && capacity < template.getCount())) return;
        client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, client.player);
        if (!ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) return;
        fillCursor(client, handler, targets);
        if (!handler.getCursorStack().isEmpty() && source.canInsert(handler.getCursorStack())
                && !QuickContainerLock.isLockedSlot(handler, source)) {
            client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, client.player);
        }
    }

    private static boolean accepts(ScreenHandler handler, int id, ItemStack stack) {
        Slot slot = handler.getSlot(id);
        ItemStack existing = slot.getStack();
        return slot.isEnabled() && !QuickContainerLock.isLockedSlot(handler, slot) && slot.canInsert(stack)
                && (existing.isEmpty() || (ItemStack.areItemsAndComponentsEqual(existing, stack)
                && existing.getCount() < slot.getMaxItemCount(stack)));
    }

    private static void fillCursor(MinecraftClient client, ScreenHandler handler, List<Integer> targets) {
        for (boolean emptyOnly : new boolean[]{false, true}) {
            for (int id : targets) {
                ItemStack cursor = handler.getCursorStack();
                if (cursor.isEmpty()) return;
                Slot target = handler.getSlot(id);
                if (target.getStack().isEmpty() != emptyOnly || !accepts(handler, id, cursor)) continue;
                client.interactionManager.clickSlot(handler.syncId, id, 0, SlotActionType.PICKUP, client.player);
            }
        }
    }
}
