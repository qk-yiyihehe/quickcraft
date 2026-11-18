package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** 只负责整理区域的空盒预算；布局与搬运规则均由共享规划器维护。 */
public final class QuickCrossBoxSortSpares {
    private QuickCrossBoxSortSpares() {}

    public record Spare(int sourceSlot, int targetSlot) {}

    public static int needed(List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> boxes) {
        Map<QuickCrossBoxSortStackKey, Integer> totals = new LinkedHashMap<>();
        for (var box : boxes) {
            for (var stack : box.slots()) {
                if (stack != null) { totals.merge(stack.key(), stack.count(), Integer::sum); }
            }
        }
        int needed = 0;
        for (var entry : totals.entrySet()) {
            int capacity = entry.getKey().template().getMaxStackSize() * 27;
            needed += (entry.getValue() + capacity - 1) / capacity;
        }
        return needed;
    }

    public static List<Integer> singles(Inventory inventory) {
        return singles(inventory, QuickCrossBoxSortProtection::isLocked);
    }

    public static List<Integer> singles(net.minecraft.world.Container inventory, java.util.function.IntPredicate locked) {
        List<Integer> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!locked.test(slot) && stack.getCount() == 1 && QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(stack)) { result.add(slot); }
        }
        return result;
    }

    public static List<Spare> splits(Inventory inventory, int missing, boolean reserveWorkspace) {
        return splits(inventory, QuickCrossBoxSortProtection::isLocked, missing, reserveWorkspace);
    }

    public static List<Spare> splits(net.minecraft.world.Container inventory, java.util.function.IntPredicate locked, int missing, boolean reserveWorkspace) {
        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!locked.test(slot) && inventory.getItem(slot).isEmpty()) { free.add(slot); }
        }
        if (reserveWorkspace && !free.isEmpty()) { free.removeLast(); }
        List<Spare> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize() && result.size() < missing; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (locked.test(slot) || stack.getCount() <= 1 || !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(stack)
                    || reserveWorkspace && !stack.has(DataComponents.CONTAINER)) { continue; }
            for (int count = 0; count < stack.getCount() && result.size() < missing && result.size() < free.size(); count++) {
                result.add(new Spare(slot, free.get(result.size())));
            }
        }
        return result.size() == missing ? result : List.of();
    }

    public static List<int[]> planSplits() {
        Inventory inventory = Minecraft.getInstance().player.getInventory();
        var boxes = QuickCrossBoxSortInventory.scan(inventory).boxes();
        int missing = Math.max(0, needed(boxes) - boxes.size() - singles(inventory).size());
        return splits(inventory, missing, true).stream().map(s -> new int[]{s.sourceSlot(), s.targetSlot()}).toList();
    }

}
