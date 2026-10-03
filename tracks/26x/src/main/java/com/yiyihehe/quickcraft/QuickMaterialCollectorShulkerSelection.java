package com.yiyihehe.quickcraft;

import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
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

    static Slot findDestinationShulkerSlot(AbstractContainerMenu handler, ItemStack insertStack, List<ItemStack> targetTemplates) {
        DestinationShulkerCandidate bestCandidate = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
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

    static boolean isUsableDestinationShulker(ItemStack stack, List<ItemStack> targetTemplates) {
        return isShulkerBox(stack) && containsOnlyTargetMaterials(stack, targetTemplates);
    }

    static DestinationShulkerCandidate createDestinationShulkerCandidate(Slot slot,
                                                                          ItemStack insertStack,
                                                                          List<ItemStack> targetTemplates) {
        if (!slot.hasItem() || !isShulkerBox(slot.getItem())) {
            return null;
        }

        ItemStack shulker = slot.getItem();
        int totalCapacity = getShulkerCapacityFor(shulker, insertStack);
        if (totalCapacity <= 0) {
            return null;
        }

        return new DestinationShulkerCandidate(
                slot,
                containsStoredMaterial(shulker, insertStack),
                containsAnyTargetMaterial(shulker, targetTemplates),
                getShulkerMatchingCapacity(shulker, insertStack),
                isUsableDestinationShulker(shulker, targetTemplates),
                totalCapacity
        );
    }

    static boolean containsOnlyTargetMaterials(ItemStack shulker, List<ItemStack> targetTemplates) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!containsTarget(targetTemplates, stored)) {
                return false;
            }
        }
        return true;
    }

    static boolean containsAnyTargetMaterial(ItemStack shulker, List<ItemStack> targetTemplates) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (containsTarget(targetTemplates, stored)) {
                return true;
            }
        }
        return false;
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

    static WholeShulkerCandidate findBestWholeShulkerCandidate(AbstractContainerMenu handler,
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

    static WholeShulkerCandidate createWholeShulkerCandidate(AbstractContainerMenu handler,
                                                              Slot source,
                                                              List<Demand> demands,
                                                              List<ItemStack> targetTemplates) {
        if (!source.hasItem() || !isShulkerBox(source.getItem())) {
            return null;
        }

        ItemStack shulker = source.getItem();
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

    static boolean containsStoredMaterial(ItemStack shulker, ItemStack template) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (stacksMatch(stored, template)) {
                return true;
            }
        }
        return false;
    }

    static int getShulkerMatchingCapacity(ItemStack shulker, ItemStack insertStack) {
        int capacity = 0;
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (stacksExactlyMatch(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }
        return capacity;
    }

    static int getShulkerCapacityFor(ItemStack shulker, ItemStack insertStack) {
        if (!isShulkerBox(shulker) || isShulkerBox(insertStack)) {
            return 0;
        }

        int usedSlots = 0;
        int capacity = 0;
        for (ItemStack stored : getStoredStacks(shulker)) {
            usedSlots++;
            if (stacksExactlyMatch(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }

        int emptySlots = Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots);
        capacity += emptySlots * insertStack.getMaxStackSize();
        return capacity;
    }

    static int countStoredInPlayerShulkers(Inventory inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isShulkerBox(stack) || !hasStoredItems(stack)) {
                continue;
            }

            for (ItemStack stored : getStoredStacks(stack)) {
                if (stacksMatch(stored, template)) {
                    count += stored.getCount();
                }
            }
        }
        return count;
    }

    static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.nonEmptyItemCopyStream().toList()) {
            stacks.add(stack);
        }
        return stacks;
    }

    static boolean hasStoredItems(ItemStack shulker) {
        return getStoredStacks(shulker).isEmpty() == false;
    }

    static boolean hasPlayerCapacity(AbstractContainerMenu handler, ItemStack template, int amount) {
        return getPlayerCapacity(handler, template, amount) >= amount;
    }

    static int getPlayerCapacity(AbstractContainerMenu handler, ItemStack template, int maxAmount) {
        int capacity = 0;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.mayPlace(template)) {
                continue;
            }
            if (!slot.hasItem()) {
                capacity += template.getMaxStackSize();
            } else if (stacksExactlyMatch(slot.getItem(), template)) {
                capacity += Math.max(0, slot.getItem().getMaxStackSize() - slot.getItem().getCount());
            }
            if (capacity >= maxAmount) {
                return maxAmount;
            }
        }
        return capacity;
    }

    static List<Slot> getContainerSlots(AbstractContainerMenu handler) {
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
                .thenComparingInt(slot -> slot.index));
        return slots;
    }

    static List<Slot> getPlayerStorageSlots(AbstractContainerMenu handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot)
                    && isPlayerStorageSlot(slot)
                    && !QuickContainerLock.isLockedSlot(handler, slot)) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));
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
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItem(a, b);
    }

    static boolean stacksExactlyMatch(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    static boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory
                && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < 36;
    }

    static boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    record WholeShulkerCandidate(Slot slot, int contribution, int matchedDemandTypes) {
        private boolean isBetterThan(WholeShulkerCandidate other) {
            return contribution > other.contribution
                    || (contribution == other.contribution && matchedDemandTypes > other.matchedDemandTypes)
                    || (contribution == other.contribution
                    && matchedDemandTypes == other.matchedDemandTypes
                    && slot.index < other.slot.index);
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
                    && slot.index < other.slot.index);
        }
    }

    private static boolean compareTrueFirst(boolean current, boolean other) {
        return current && !other;
    }
}
