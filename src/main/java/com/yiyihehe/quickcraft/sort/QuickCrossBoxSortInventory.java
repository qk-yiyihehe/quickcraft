package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.component.type.ContainerComponent;

public final class QuickCrossBoxSortInventory {
    private QuickCrossBoxSortInventory() {
    }

    public static ScanResult scan(PlayerInventory inventory) {
        return scan(inventory, com.yiyihehe.quickcraft.sort.QuickCrossBoxSortProtection::isLocked);
    }

    public static ScanResult scan(PlayerInventory inventory, java.util.function.IntPredicate locked) {
        return scan(36, inventory::getStack, locked);
    }

    public static ScanResult scan(net.minecraft.inventory.Inventory inventory) {
        return scan(inventory.size(), inventory::getStack, slot -> false);
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
                contents.add(item.isEmpty() ? null : new QuickCrossBoxSortStacks.Stack<>(new QuickCrossBoxSortStackKey(item), item.getCount(), item.getMaxCount()));
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
        return stack.getItem() instanceof net.minecraft.item.BlockItem item
        && item.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;
    }

    public static boolean isNamedOrSpecial(ItemStack stack) {
        if (stack.contains(DataComponentTypes.CUSTOM_NAME) || stack.contains(DataComponentTypes.CONTAINER_LOOT)) { return true; }
        var name = stack.get(DataComponentTypes.ITEM_NAME);
        return name != null && !name.equals(net.minecraft.text.Text.translatable(stack.getItem().getTranslationKey()));
    }

    public static boolean isOrdinaryEmptyShulker(ItemStack stack) {
        return !stack.isEmpty() && isVanillaShulkerBox(stack) && !isNamedOrSpecial(stack)
        && storedItems(stack).stream().allMatch(ItemStack::isEmpty);
    }

    private static DefaultedList<ItemStack> storedItems(ItemStack stack) {
        DefaultedList<ItemStack> items = DefaultedList.ofSize(27, ItemStack.EMPTY);
        stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).copyTo(items);
        return items;
    }

    public record ScanResult(List<Integer> stagingInventorySlots, List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> boxes) {
    }
}
