package com.yiyihehe.quickcraft;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.yiyihehe.quickcraft.QuickMaterialCollector.Demand;
import com.yiyihehe.quickcraft.QuickMaterialCollector.StoredCount;

/** 材料收集的潜影盒候选、容量和槽位查询；需求状态由收集任务维护，不执行搬运。 */
final class QuickMaterialCollectorShulkerSelection {
    private QuickMaterialCollectorShulkerSelection() {
    }

    private static final int VANILLA_SHULKER_SLOTS = 27;

    static Slot findDestinationShulkerSlot(ScreenHandler handler, ItemStack insertStack, List<ItemStack> targetTemplates, int excludedPlayerIndex) {
        DestinationShulkerCandidate bestCandidate = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getIndex() == excludedPlayerIndex) {
                continue;
            }
            DestinationShulkerCandidate candidate = createDestinationShulkerCandidate(slot, insertStack, targetTemplates);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.isBetterThan(bestCandidate)) {
                bestCandidate = candidate;
            }
        }

        return bestCandidate != null ? bestCandidate.slot() : null;
    }

    static DestinationShulkerCandidate createDestinationShulkerCandidate(Slot slot,
                                                                          ItemStack insertStack,
                                                                          List<ItemStack> targetTemplates) {
        if (!slot.hasStack() || !isShulkerBox(slot.getStack()) || isShulkerBox(insertStack)) {
            return null;
        }

        int usedSlots = 0;
        int matchingCapacity = 0;
        boolean hasMatchingMaterial = false;
        boolean hasStoredTargetMaterial = false;
        boolean targetOnly = true;
        for (ItemStack stored : getStoredStacks(slot.getStack())) {
            usedSlots++;
            hasMatchingMaterial |= stacksMatch(stored, insertStack);
            boolean target = containsTarget(targetTemplates, stored);
            hasStoredTargetMaterial |= target;
            targetOnly &= target;
            if (stacksExactlyMatch(stored, insertStack)) {
                matchingCapacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }
        int totalCapacity = matchingCapacity + Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots) * insertStack.getMaxCount();
        if (totalCapacity <= 0) {
            return null;
        }
        return new DestinationShulkerCandidate(
                slot, hasMatchingMaterial, hasStoredTargetMaterial, matchingCapacity, targetOnly, totalCapacity);
    }

    static boolean containsOnlyTargetMaterials(ItemStack shulker, List<ItemStack> targetTemplates) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!containsTarget(targetTemplates, stored)) {
                return false;
            }
        }
        return true;
    }

    static List<StoredCount> getStoredTargetCounts(ItemStack shulker, List<Demand> demands) {
        List<StoredCount> counts = new ArrayList<>();
        for (ItemStack stored : getStoredStacks(shulker)) {
            Demand demand = findDemand(demands, stored);
            if (demand == null || demand.remaining() <= 0) {
                // 已满足或不在本轮需求中的材料也会随整盒搬走，不能忽略。
                return List.of();
            }

            StoredCount count = findStoredCount(counts, demand);
            if (count == null) {
                counts.add(new StoredCount(demand, stored.getCount()));
            } else {
                count.add(stored.getCount());
            }
        }
        return counts;
    }

    static WholeShulkerCandidate findBestWholeShulkerCandidate(ScreenHandler handler,
                                                                List<Demand> demands,
                                                                List<ItemStack> targetTemplates) {
        WholeShulkerCandidate bestCandidate = null;
        for (Slot slot : getContainerSlots(handler)) {
            WholeShulkerCandidate candidate = createWholeShulkerCandidate(handler, slot, demands, targetTemplates);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.isBetterThan(bestCandidate)) {
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    static WholeShulkerCandidate createWholeShulkerCandidate(ScreenHandler handler,
                                                              Slot source,
                                                              List<Demand> demands,
                                                              List<ItemStack> targetTemplates) {
        if (!source.hasStack() || !isShulkerBox(source.getStack())) {
            return null;
        }

        ItemStack shulker = source.getStack();
        if (!containsOnlyTargetMaterials(shulker, targetTemplates) || !hasPlayerCapacity(handler, shulker, shulker.getCount())) {
            return null;
        }

        List<StoredCount> contents = getStoredTargetCounts(shulker, demands);
        if (contents.isEmpty()) {
            return null;
        }

        int contribution = 0;
        for (StoredCount content : contents) {
            if (content.count() > content.demand().remaining()) {
                return null;
            }
            contribution += content.count();
        }

        return new WholeShulkerCandidate(source, contribution, contents.size());
    }

    static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            stacks.add(stack);
        }
        return stacks;
    }

    static boolean hasStoredItems(ItemStack shulker) {
        return getStoredStacks(shulker).isEmpty() == false;
    }

    static boolean hasPlayerCapacity(ScreenHandler handler, ItemStack template, int amount) {
        return getPlayerCapacity(handler, template, amount) >= amount;
    }

    static int getPlayerCapacity(ScreenHandler handler, ItemStack template, int maxAmount) {
        int capacity = 0;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.canInsert(template)) {
                continue;
            }
            if (!slot.hasStack()) {
                capacity += template.getMaxCount();
            } else if (stacksExactlyMatch(slot.getStack(), template)) {
                capacity += Math.max(0, slot.getStack().getMaxCount() - slot.getStack().getCount());
            }
            if (capacity >= maxAmount) {
                return maxAmount;
            }
        }
        return capacity;
    }

    static List<Slot> getContainerSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && !isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id));
        return slots;
    }

    static List<Slot> getPlayerStorageSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));
        return slots;
    }

    static Demand findDemand(List<Demand> demands, ItemStack stack) {
        for (Demand demand : demands) {
            if (stacksMatch(stack, demand.template())) {
                return demand;
            }
        }
        return null;
    }

    static boolean containsTarget(List<ItemStack> targetTemplates, ItemStack stack) {
        for (ItemStack target : targetTemplates) {
            if (stacksMatch(stack, target)) {
                return true;
            }
        }
        return false;
    }

    static StoredCount findStoredCount(List<StoredCount> counts, Demand demand) {
        for (StoredCount count : counts) {
            if (count.demand() == demand) {
                return count;
            }
        }
        return null;
    }

    static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    static boolean stacksMatch(ItemStack a, ItemStack b) {
        // Litematica 的材料表按 ItemType(stack, true, false) 统计，这里同样只按物品类型匹配。
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsEqual(a, b);
    }

    static boolean stacksExactlyMatch(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }

    static boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    static boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    record WholeShulkerCandidate(Slot slot, int contribution, int matchedDemandTypes) {
        private boolean isBetterThan(WholeShulkerCandidate other) {
            return contribution > other.contribution
                    || (contribution == other.contribution && matchedDemandTypes > other.matchedDemandTypes)
                    || (contribution == other.contribution
                    && matchedDemandTypes == other.matchedDemandTypes
                    && slot.id < other.slot.id);
        }
    }

    record DestinationShulkerCandidate(Slot slot,
                                               boolean hasMatchingMaterial,
                                               boolean hasStoredTargetMaterial,
                                               int matchingCapacity,
                                               boolean targetOnly,
                                               int totalCapacity) {
        private boolean isBetterThan(DestinationShulkerCandidate other) {
            // 先续装同类，再复用已经承担本次材料任务的盒子，最后才启用空盒，避免材料散落。
            return compareTrueFirst(hasMatchingMaterial, other.hasMatchingMaterial)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && compareTrueFirst(hasStoredTargetMaterial, other.hasStoredTargetMaterial))
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity > other.matchingCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && compareTrueFirst(targetOnly, other.targetOnly))
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity > other.totalCapacity)
                    || (hasMatchingMaterial == other.hasMatchingMaterial
                    && hasStoredTargetMaterial == other.hasStoredTargetMaterial
                    && matchingCapacity == other.matchingCapacity
                    && targetOnly == other.targetOnly
                    && totalCapacity == other.totalCapacity
                    && slot.id < other.slot.id);
        }
    }

    private static boolean compareTrueFirst(boolean current, boolean other) {
        return current && !other;
    }
}
