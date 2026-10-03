package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.*;

/** 容器物品、组件与禁用槽位比较；不维护屏幕绑定或发送数据请求。 */
final class QuickLitematicaContainerComparison {
    private QuickLitematicaContainerComparison() {
    }

    static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Inventory expected,
            Inventory found
    ) {
        return findMismatches(
                pos,
                expectedState,
                foundState,
                expectedBlockEntity,
                foundBlockEntity,
                QuickLitematicaContainerInventory.getDisabledSlots(expectedBlockEntity),
                QuickLitematicaContainerInventory.getDisabledSlots(foundBlockEntity),
                expected,
                found
        );
    }

    static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            Inventory expected,
            Inventory found
    ) {
        Inventory expectedCopy = QuickLitematicaContainerInventory.copyInventory(expected);
        Inventory foundCopy = QuickLitematicaContainerInventory.copyInventory(found);
        List<SlotMismatch> slotMismatches = new ArrayList<>();
        boolean hasWrongItem = false;
        boolean hasMissing = false;
        boolean hasStateMismatch = false;
        boolean hasExpectedFilledSlot = false;
        boolean allExpectedFilledSlotsMissing = true;

        for (int slot = 0; slot < expected.size(); slot++) {
            ItemStack expectedStack = expected.getStack(slot);
            ItemStack foundStack = found.getStack(slot);
            SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, foundStack);

            if (!expectedStack.isEmpty()) {
                hasExpectedFilledSlot = true;
                if (!foundStack.isEmpty()) {
                    allExpectedFilledSlotsMissing = false;
                }
            }

            if (status != null) {
                slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));

                if (status == SlotMismatchStatus.WRONG || status == SlotMismatchStatus.EXTRA) {
                    hasWrongItem = true;
                } else if (status == SlotMismatchStatus.MISSING) {
                    hasMissing = true;
                } else if (status == SlotMismatchStatus.COUNT) {
                    hasStateMismatch = true;
                }
            }
        }

        if (!expectedDisabledSlots.equals(foundDisabledSlots)) {
            for (int slot : unionSlots(expectedDisabledSlots, foundDisabledSlots)) {
                if (expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot)) {
                    addSlotStatusIfEmpty(slotMismatches, slot, SlotMismatchStatus.LOCK_STATE, expected.getStack(slot), found.getStack(slot));
                }
            }

            hasStateMismatch = true;
        }

        if (slotMismatches.isEmpty()) {
            return List.of();
        }

        SlotMismatch first = slotMismatches.getFirst();
        MismatchType type;

        if (hasWrongItem) {
            type = WRONG_FILL;
        } else if (hasExpectedFilledSlot && allExpectedFilledSlotsMissing) {
            type = MISSING_FILL;
        } else if (hasStateMismatch) {
            type = WRONG_FILL_STATE;
        } else if (hasMissing) {
            type = MISSING_FILL;
        } else {
            type = WRONG_FILL;
        }

        return List.of(new ContainerMismatch(
                pos,
                expectedState,
                foundState,
                first.slot(),
                type,
                first.expectedStack(),
                first.foundStack(),
                expectedCopy,
                foundCopy,
                expectedDisabledSlots,
                foundDisabledSlots,
                List.copyOf(slotMismatches)
        ));
    }

    static SlotMismatchStatus getSlotMismatchStatus(ItemStack expectedStack, ItemStack foundStack) {
        boolean expectedEmpty = expectedStack.isEmpty();
        boolean foundEmpty = foundStack.isEmpty();

        if (expectedEmpty && foundEmpty) {
            return null;
        }
        if (!expectedEmpty && foundEmpty) {
            return SlotMismatchStatus.MISSING;
        }
        if (expectedEmpty) {
            return SlotMismatchStatus.EXTRA;
        }
        if (!areItemsAndComponentsEqual(expectedStack, foundStack)) {
            return SlotMismatchStatus.WRONG;
        }
        if (expectedStack.getCount() != foundStack.getCount()) {
            return SlotMismatchStatus.COUNT;
        }

        return null;
    }

    static boolean areItemsAndComponentsEqual(ItemStack expectedStack, ItemStack foundStack) {
        if (ItemStack.areItemsAndComponentsEqual(expectedStack, foundStack)) {
            return true;
        }

        if (!expectedStack.isOf(foundStack.getItem())
                || !sameEnchantments(expectedStack, foundStack, DataComponentTypes.ENCHANTMENTS)
                || !sameEnchantments(expectedStack, foundStack, DataComponentTypes.STORED_ENCHANTMENTS)) {
            return false;
        }

        ItemStack expectedWithoutEnchantments = expectedStack.copy();
        ItemStack foundWithoutEnchantments = foundStack.copy();
        expectedWithoutEnchantments.remove(DataComponentTypes.ENCHANTMENTS);
        expectedWithoutEnchantments.remove(DataComponentTypes.STORED_ENCHANTMENTS);
        foundWithoutEnchantments.remove(DataComponentTypes.ENCHANTMENTS);
        foundWithoutEnchantments.remove(DataComponentTypes.STORED_ENCHANTMENTS);
        return ItemStack.areItemsAndComponentsEqual(expectedWithoutEnchantments, foundWithoutEnchantments);
    }

    static boolean sameEnchantments(
            ItemStack expectedStack,
            ItemStack foundStack,
            net.minecraft.component.ComponentType<ItemEnchantmentsComponent> type
    ) {
        ItemEnchantmentsComponent expected = expectedStack.get(type);
        ItemEnchantmentsComponent found = foundStack.get(type);

        if (expected == null || found == null) {
            return expected == found;
        }
        if (!QuickLitematicaVerifierAccess.sameEnchantmentsTooltip(expected, found)) {
            return false;
        }
        if (expected.getSize() != found.getSize()) {
            return false;
        }

        for (var entry : expected.getEnchantmentEntries()) {
            RegistryEntry<net.minecraft.enchantment.Enchantment> expectedEnchantment = entry.getKey();
            int expectedLevel = entry.getIntValue();
            boolean matched = false;

            for (var foundEntry : found.getEnchantmentEntries()) {
                RegistryEntry<net.minecraft.enchantment.Enchantment> foundEnchantment = foundEntry.getKey();
                if (expectedEnchantment.matchesKey(foundEnchantment.getKey().orElse(null))
                        && expectedLevel == foundEntry.getIntValue()) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                return false;
            }
        }

        return true;
    }

    static boolean isSlotLockMismatch(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots, int slot) {
        return expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot);
    }

    static void addSlotStatusIfEmpty(
            List<SlotMismatch> slotMismatches,
            int slot,
            SlotMismatchStatus status,
            ItemStack expectedStack,
            ItemStack foundStack
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.slot() == slot) {
                return;
            }
        }

        slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));
    }

    static Set<Integer> unionSlots(Set<Integer> left, Set<Integer> right) {
        java.util.HashSet<Integer> slots = new java.util.HashSet<>(left);
        slots.addAll(right);
        return slots;
    }
}
