package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** 沿用原作者的来源索引校验，所有来源恰好耗尽后才一次性写入库存。 */
public final class QuickCrossBoxSortIntegratedServer {
    private QuickCrossBoxSortIntegratedServer() {}

    public static QuickCrossBoxSortResult apply(QuickCrossBoxSortRequest request, ServerPlayerEntity player) {
        try { return applyChecked(request, player); }
        catch (RuntimeException exception) {
            QuickCrossBoxSort.LOGGER.warn("Integrated cross-box sorting failed", exception);
            return QuickCrossBoxSortResult.FAILED;
        }
    }

    private static QuickCrossBoxSortResult applyChecked(QuickCrossBoxSortRequest request, ServerPlayerEntity player) {
        if (player.isDead() || player.isDisconnected()
                || !player.currentScreenHandler.getCursorStack().isEmpty()) { return QuickCrossBoxSortResult.MISMATCH; }
        var menu = player.currentScreenHandler;
        int size = request.menuSlots().size();
        if (menu.syncId != request.menuId() || !menu.canUse(player) || size == 0 || size > menu.slots.size()
                || new HashSet<>(request.menuSlots()).size() != size) { return QuickCrossBoxSortResult.MISMATCH; }
        ItemStack[] replacement = new ItemStack[size];
        for (int index = 0; index < size; index++) {
            int slot = request.menuSlots().get(index);
            if (slot < 0 || slot >= menu.slots.size()) { return QuickCrossBoxSortResult.MISMATCH; }
            replacement[index] = menu.getSlot(slot).getStack().copy();
        }
        Set<Integer> splitTargets = new HashSet<>();
        for (var split : request.splits()) {
            int source = split.sourceSlot();
            int target = split.targetSlot();
            if (source < 0 || source >= size || target < 0 || target >= size
                    || !replacement[target].isEmpty() || !splitTargets.add(target)
                    || !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(replacement[source])) { return QuickCrossBoxSortResult.MISMATCH; }
            replacement[target] = replacement[source].copyWithCount(1);
            replacement[source].decrement(1);
        }
        int[] available = new int[size * 27];
        int[] used = new int[available.length];
        ItemStack[] templates = new ItemStack[available.length];
        Set<Integer> expectedSlots = new HashSet<>();
        for (var box : QuickCrossBoxSortInventory.scan(new net.minecraft.inventory.SimpleInventory(replacement)).boxes()) {
            expectedSlots.add(box.inventorySlot());
            for (int slot = 0; slot < 27; slot++) {
                var stack = box.slots().get(slot);
                if (stack != null) {
                    int index = box.inventorySlot() * 27 + slot;
                    available[index] = stack.count();
                    templates[index] = stack.key().template();
                }
            }
        }
        Set<Integer> seen = new HashSet<>();
        int emptied = 0;
        int remaining = 0;
        int moves = 0;
        for (var box : request.targets()) {
            int slot = box.inventorySlot();
            if (slot < 0 || slot >= size || !seen.add(slot) || box.slots().size() != 27) {
                return QuickCrossBoxSortResult.MISMATCH;
            }
            ItemStack shell = replacement[slot];
            if (shell.getCount() != 1 || !QuickCrossBoxSortInventory.isVanillaShulkerBox(shell) || QuickCrossBoxSortInventory.isNamedOrSpecial(shell)
                    || !expectedSlots.contains(slot) && !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(shell)) {
                return QuickCrossBoxSortResult.MISMATCH;
            }
            List<ItemStack> contents = new ArrayList<>(27);
            for (var parts : box.slots()) {
                ItemStack first = ItemStack.EMPTY;
                int count = 0;
                for (var part : parts) {
                    int index = part.index();
                    int amount = part.amount();
                    if (index < 0 || index >= available.length || amount <= 0 || templates[index] == null
                            || amount > available[index] - used[index]) { return QuickCrossBoxSortResult.MISMATCH; }
                    ItemStack source = templates[index];
                    if (first.isEmpty()) { first = source; }
                    else if (!ItemStack.areItemsAndComponentsEqual(first, source)) { return QuickCrossBoxSortResult.MISMATCH; }
                    if (amount > first.getMaxCount() - count) { return QuickCrossBoxSortResult.MISMATCH; }
                    count += amount;
                    used[index] += amount;
                    if (index / 27 != slot) { moves++; }
                }
                contents.add(count == 0 ? ItemStack.EMPTY : first.copyWithCount(count));
            }
            boolean empty = contents.stream().allMatch(ItemStack::isEmpty);
            if (empty && expectedSlots.contains(slot)) { emptied++; }
            if (!empty) { remaining++; }
            shell.set(DataComponentTypes.CONTAINER, empty ? ContainerComponent.DEFAULT : ContainerComponent.fromStacks(contents));
        }
        if (!seen.containsAll(expectedSlots) || !seen.containsAll(splitTargets)) { return QuickCrossBoxSortResult.MISMATCH; }
        for (int index = 0; index < available.length; index++) {
            if (used[index] != available[index]) { return QuickCrossBoxSortResult.MISMATCH; }
        }
        for (int target = 0; target < size; target++) {
            ItemStack stack = replacement[target];
            if (!QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(stack)) { continue; }
            for (int source = target + 1; source < size && stack.getCount() < stack.getMaxCount(); source++) {
                ItemStack other = replacement[source];
                if (!QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(other)
                        || !ItemStack.areItemsAndComponentsEqual(stack, other)) { continue; }
                int amount = Math.min(stack.getMaxCount() - stack.getCount(), other.getCount());
                stack.increment(amount);
                other.decrement(amount);
            }
        }
        for (int index = 0; index < size; index++) {
            var slot = menu.getSlot(request.menuSlots().get(index));
            ItemStack stack = replacement[index];
            if (ItemStack.areEqual(slot.getStack(), stack)) { continue; }
            if (!slot.canTakeItems(player) || !stack.isEmpty() && (!slot.canInsert(stack) || stack.getCount() > slot.getMaxItemCount(stack))) {
                return QuickCrossBoxSortResult.MISMATCH;
            }
        }
        for (int index = 0; index < size; index++) {
            var slot = menu.getSlot(request.menuSlots().get(index));
            if (!ItemStack.areEqual(slot.getStack(), replacement[index])) { slot.setStack(replacement[index]); }
        }
        menu.sendContentUpdates();
        return new QuickCrossBoxSortResult(true, emptied, remaining, moves, null);
    }
}
