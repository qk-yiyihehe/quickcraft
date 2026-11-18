package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/** 沿用原作者的来源索引校验，所有来源恰好耗尽后才一次性写入库存。 */
public final class QuickCrossBoxSortIntegratedServer {
    private QuickCrossBoxSortIntegratedServer() {}

    public static QuickCrossBoxSortResult apply(QuickCrossBoxSortRequest request, ServerPlayer player) {
        try { return applyChecked(request, player); }
        catch (RuntimeException exception) {
            QuickCrossBoxSort.LOGGER.warn("Integrated cross-box sorting failed", exception);
            return QuickCrossBoxSortResult.FAILED;
        }
    }

    private static QuickCrossBoxSortResult applyChecked(QuickCrossBoxSortRequest request, ServerPlayer player) {
        if (player.isDeadOrDying() || player.hasDisconnected()
                || !player.containerMenu.getCarried().isEmpty()) { return QuickCrossBoxSortResult.MISMATCH; }
        var menu = player.containerMenu;
        int size = request.menuSlots().size();
        if (menu.containerId != request.menuId() || !menu.stillValid(player) || size == 0 || size > menu.slots.size()
                || new HashSet<>(request.menuSlots()).size() != size) { return QuickCrossBoxSortResult.MISMATCH; }
        ItemStack[] replacement = new ItemStack[size];
        for (int index = 0; index < size; index++) {
            int slot = request.menuSlots().get(index);
            if (slot < 0 || slot >= menu.slots.size()) { return QuickCrossBoxSortResult.MISMATCH; }
            replacement[index] = menu.getSlot(slot).getItem().copy();
        }
        Set<Integer> splitTargets = new HashSet<>();
        for (var split : request.splits()) {
            int source = split.sourceSlot();
            int target = split.targetSlot();
            if (source < 0 || source >= size || target < 0 || target >= size
                    || !replacement[target].isEmpty() || !splitTargets.add(target)
                    || !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(replacement[source])) { return QuickCrossBoxSortResult.MISMATCH; }
            replacement[target] = replacement[source].copyWithCount(1);
            replacement[source].shrink(1);
        }
        int[] available = new int[size * 27];
        int[] used = new int[available.length];
        ItemStack[] templates = new ItemStack[available.length];
        Set<Integer> expectedSlots = new HashSet<>();
        for (var box : QuickCrossBoxSortInventory.scan(new net.minecraft.world.SimpleContainer(replacement)).boxes()) {
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
                    else if (!ItemStack.isSameItemSameComponents(first, source)) { return QuickCrossBoxSortResult.MISMATCH; }
                    if (amount > first.getMaxStackSize() - count) { return QuickCrossBoxSortResult.MISMATCH; }
                    count += amount;
                    used[index] += amount;
                    if (index / 27 != slot) { moves++; }
                }
                contents.add(count == 0 ? ItemStack.EMPTY : first.copyWithCount(count));
            }
            boolean empty = contents.stream().allMatch(ItemStack::isEmpty);
            if (empty && expectedSlots.contains(slot)) { emptied++; }
            if (!empty) { remaining++; }
            shell.set(DataComponents.CONTAINER, empty ? ItemContainerContents.EMPTY : ItemContainerContents.fromItems(contents));
        }
        if (!seen.containsAll(expectedSlots) || !seen.containsAll(splitTargets)) { return QuickCrossBoxSortResult.MISMATCH; }
        for (int index = 0; index < available.length; index++) {
            if (used[index] != available[index]) { return QuickCrossBoxSortResult.MISMATCH; }
        }
        for (int target = 0; target < size; target++) {
            ItemStack stack = replacement[target];
            if (!QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(stack)) { continue; }
            for (int source = target + 1; source < size && stack.getCount() < stack.getMaxStackSize(); source++) {
                ItemStack other = replacement[source];
                if (!QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(other)
                        || !ItemStack.isSameItemSameComponents(stack, other)) { continue; }
                int amount = Math.min(stack.getMaxStackSize() - stack.getCount(), other.getCount());
                stack.grow(amount);
                other.shrink(amount);
            }
        }
        for (int index = 0; index < size; index++) {
            var slot = menu.getSlot(request.menuSlots().get(index));
            ItemStack stack = replacement[index];
            if (ItemStack.matches(slot.getItem(), stack)) { continue; }
            if (!slot.mayPickup(player) || !stack.isEmpty() && (!slot.mayPlace(stack) || stack.getCount() > slot.getMaxStackSize(stack))) {
                return QuickCrossBoxSortResult.MISMATCH;
            }
        }
        for (int index = 0; index < size; index++) {
            var slot = menu.getSlot(request.menuSlots().get(index));
            if (!ItemStack.matches(slot.getItem(), replacement[index])) { slot.set(replacement[index]); }
        }
        menu.broadcastChanges();
        return new QuickCrossBoxSortResult(true, emptied, remaining, moves, null);
    }
}
