package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class QuickCrossBoxSortInventory {
    private QuickCrossBoxSortInventory() {
    }

    public static ScanResult scan(Inventory inventory) {
        return scan(inventory, com.yiyihehe.quickcraft.sort.QuickCrossBoxSortProtection::isLocked);
    }

    public static ScanResult scan(Inventory inventory, java.util.function.IntPredicate locked) {
        return scan(36, inventory::getItem, locked);
    }

    public static ScanResult scan(net.minecraft.world.Container inventory) {
        return scan(inventory.getContainerSize(), inventory::getItem, slot -> false);
    }

    private static ScanResult scan(int size, java.util.function.IntFunction<ItemStack> items, java.util.function.IntPredicate locked) {
        List<Integer> stagingSlots = new ArrayList<>();
        List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> boxes = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            if (locked.test(slot)) { continue; }
            ItemStack shell = items.apply(slot);
            if (shell.isEmpty()) { stagingSlots.add(slot); continue; }
            if (shell.getCount() != 1 || !isVanillaShulkerBox(shell) || isNamedOrSpecial(shell)) { continue; }
            List<QuickCrossBoxSortStacks.Stack<QuickCrossBoxSortStackKey>> contents = new ArrayList<>(27);
            for (ItemStack item : storedItems(shell)) {
                contents.add(item.isEmpty() ? null : new QuickCrossBoxSortStacks.Stack<>(new QuickCrossBoxSortStackKey(item), item.getCount(), item.getMaxStackSize()));
            }
            // 已装满的同类整盒不再参与搬运，保留原项目的整盒保护。
            if (contents.stream().anyMatch(java.util.Objects::nonNull) && !isHomogeneousCapacityFull(contents)) {
                boxes.add(new QuickCrossBoxSortStacks.Box<>(slot, contents));
            }
        }
        return new ScanResult(List.copyOf(stagingSlots), List.copyOf(boxes));
    }

    static <K> boolean isHomogeneousCapacityFull(List<QuickCrossBoxSortStacks.Stack<K>> slots) {
        if (!slots.isEmpty() && slots.getFirst() != null) {
            K firstKey = slots.getFirst().key();
            return slots.stream().allMatch((stack) -> stack != null && stack.count() == stack.maxCount() && firstKey.equals(stack.key()));
        } else {
            return false;
        }
    }

    public static boolean isVanillaShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem item
        && item.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    public static boolean isNamedOrSpecial(ItemStack stack) {
        if (stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.CONTAINER_LOOT)) { return true; }
        var name = stack.get(DataComponents.ITEM_NAME);
        return name != null && !name.equals(net.minecraft.network.chat.Component.translatable(stack.getItem().getDescriptionId()));
    }

    public static boolean isOrdinaryEmptyShulker(ItemStack stack) {
        return !stack.isEmpty() && isVanillaShulkerBox(stack) && !isNamedOrSpecial(stack)
        && storedItems(stack).stream().allMatch(ItemStack::isEmpty);
    }

    private static NonNullList<ItemStack> storedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        return items;
    }

    public record ScanResult(List<Integer> stagingInventorySlots, List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> boxes) {
    }
}
