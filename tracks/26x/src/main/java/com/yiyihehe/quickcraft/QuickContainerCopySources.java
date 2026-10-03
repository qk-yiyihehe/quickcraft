package com.yiyihehe.quickcraft;

import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.NonNullList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 容器复制的来源选择、需求计算和槽位布局；不发送点击或维护填充会话。 */
final class QuickContainerCopySources {
    private QuickContainerCopySources() {
    }

    private static final int VANILLA_SHULKER_SLOTS = 27;

    static int findBestReplacementSourceSlotId(AbstractContainerMenu handler, ItemStack template) {
        int neededCount = template.getCount();
        int bestUnderSlotId = -1;
        int bestUnderCount = 0;
        int bestOverSlotId = -1;
        int bestOverCount = Integer.MAX_VALUE;

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || !ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                continue;
            }

            int count = slot.getItem().getCount();
            if (count == neededCount) {
                return slotId;
            }
            if (count < neededCount && count > bestUnderCount) {
                bestUnderSlotId = slotId;
                bestUnderCount = count;
            }
            if (count > neededCount && count < bestOverCount) {
                bestOverSlotId = slotId;
                bestOverCount = count;
            }
        }

        return bestUnderSlotId != -1 ? bestUnderSlotId : bestOverSlotId;
    }

    static SourceShulker findSourceShulkerForDemandsExcept(AbstractContainerMenu handler,
                                                            List<MissingDemand> demands,
                                                            int excludedPlayerIndex) {
        if (demands.isEmpty()) {
            return null;
        }

        SourceShulker bestSource = null;
        SourceShulkerScore bestScore = null;
        for (int shulkerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot shulkerSlot = handler.getSlot(shulkerSlotId);
            if (shulkerSlot.getContainerSlot() == excludedPlayerIndex) {
                continue;
            }

            if (!shulkerSlot.hasItem()
                    || shulkerSlot.getItem().getCount() != 1
                    || !isShulkerBox(shulkerSlot.getItem())) {
                continue;
            }

            SourceShulkerScore score = getSourceShulkerScore(shulkerSlot.getItem(), demands);
            if (score.usefulItemCount() <= 0
                    || bestScore != null && !score.isBetterThan(bestScore)) {
                continue;
            }

            bestSource = new SourceShulker(shulkerSlotId, shulkerSlot.getContainerSlot());
            bestScore = score;
        }

        return bestSource;
    }

    static SourceShulkerScore getSourceShulkerScore(ItemStack shulker, List<MissingDemand> demands) {
        int usefulItemCount = 0;
        int coveredDemandCount = 0;
        long coverageScore = 0;
        NonNullList<ItemStack> storedStacks = getStoredStacksBySlot(shulker);

        for (MissingDemand demand : demands) {
            int available = 0;
            for (ItemStack stack : storedStacks) {
                if (ItemStack.isSameItemSameComponents(stack, demand.template())) {
                    available += stack.getCount();
                }
            }

            int useful = Math.min(available, demand.count());
            if (useful <= 0) {
                continue;
            }

            usefulItemCount += useful;
            coveredDemandCount++;
            // 各物品按缺口比例等权计分，优先选择一次覆盖更多种需求的盒子。
            coverageScore += (long) useful * 1_000L / demand.count();
        }

        return new SourceShulkerScore(coverageScore, usefulItemCount, coveredDemandCount);
    }

    static int findBestSourceContainerSlotId(ShulkerBoxMenu handler,
                                               List<Integer> sourceSlotIds,
                                               List<MissingDemand> demands) {
        int bestSlotId = -1;
        SourceStackScore bestScore = null;

        for (int slotId : sourceSlotIds) {
            Slot slot = handler.getSlot(slotId);
            MissingDemand demand = slot.hasItem() ? findDemandForStack(demands, slot.getItem()) : null;
            if (demand == null
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.mayPickup(Minecraft.getInstance().player)
                    || !canStoreAnyStackInPlayerStorage(handler, slot.getItem())) {
                continue;
            }

            int usefulCount = Math.min(slot.getItem().getCount(), demand.count());
            int excessCount = Math.max(0, slot.getItem().getCount() - demand.count());
            SourceStackScore score = new SourceStackScore(
                    hasMatchingPlayerStorageCapacity(handler, slot.getItem()),
                    excessCount,
                    usefulCount
            );
            if (bestScore == null || score.isBetterThan(bestScore)) {
                bestSlotId = slotId;
                bestScore = score;
            }
        }

        return bestSlotId;
    }

    static boolean hasMatchingPlayerStorageCapacity(AbstractContainerMenu handler, ItemStack stack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasItem() || !ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
                continue;
            }

            int maxCount = Math.min(slot.getItem().getMaxStackSize(), slot.getMaxStackSize(stack));
            if (slot.getItem().getCount() < maxCount) {
                return true;
            }
        }

        return false;
    }

    static MissingDemand findDemandForStack(List<MissingDemand> demands, ItemStack stack) {
        for (MissingDemand demand : demands) {
            if (demand.count() > 0 && ItemStack.isSameItemSameComponents(stack, demand.template())) {
                return demand;
            }
        }

        return null;
    }

    static int countMatchingPlayerStorage(AbstractContainerMenu handler, ItemStack template) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), template)) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    static List<MissingDemand> subtractMissingDemand(List<MissingDemand> demands, ItemStack template, int moved) {
        List<MissingDemand> remaining = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            if (!ItemStack.isSameItemSameComponents(demand.template(), template)) {
                remaining.add(demand);
                continue;
            }

            int count = Math.max(0, demand.count() - moved);
            if (count > 0) {
                remaining.add(new MissingDemand(demand.template(), count));
            }
        }
        return remaining;
    }

    static List<MissingDemand> copyMissingDemands(List<MissingDemand> demands) {
        List<MissingDemand> copies = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            copies.add(new MissingDemand(demand.template().copy(), demand.count()));
        }
        return copies;
    }

    static NonNullList<ItemStack> getStoredStacksBySlot(ItemStack shulker) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(VANILLA_SHULKER_SLOTS, ItemStack.EMPTY);
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        container.copyInto(stacks);
        return stacks;
    }

    static boolean canStoreAnyStackInPlayerStorage(AbstractContainerMenu handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isActive() || !slot.mayPlace(stack)) {
                continue;
            }

            if (!slot.hasItem()) {
                return true;
            }

            ItemStack existing = slot.getItem();
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), slot.getMaxStackSize(stack))) {
                return true;
            }
        }

        return false;
    }

    static int getShulkerCapacityFor(ItemStack shulker, ItemStack insertStack) {
        if (!isShulkerBox(shulker) || insertStack.isEmpty() || isShulkerBox(insertStack)) {
            return 0;
        }

        int usedSlots = 0;
        int capacity = 0;
        for (ItemStack stored : getStoredStacksBySlot(shulker)) {
            if (stored.isEmpty()) {
                continue;
            }

            usedSlots++;
            if (ItemStack.isSameItemSameComponents(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }

        return capacity + Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots) * insertStack.getMaxStackSize();
    }

    static List<Integer> getContainerSlotIds(AbstractContainerMenu handler) {
        if (handler instanceof AbstractFurnaceMenu
                || handler instanceof BrewingStandMenu) {
            return getContainerSlotIdsByInventoryIndex(handler);
        }
        if (handler instanceof CrafterMenu crafterHandler) {
            List<Slot> crafterSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (!isVisibleSlot(slot) || slot.container != crafterHandler.getContainer()) {
                    continue;
                }
                crafterSlots.add(slot);
            }

            crafterSlots.sort(Comparator
                    .comparingInt((Slot slot) -> slot.y)
                    .thenComparingInt(slot -> slot.x)
                    .thenComparingInt(slot -> slot.index));

            return crafterSlots.stream()
                    .map(slot -> slot.index)
                    .toList();
        }

        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.index));

        return containerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    static List<Integer> getContainerSlotIdsByInventoryIndex(AbstractContainerMenu handler) {
        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return containerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    static List<Integer> getPlayerStorageSlotIds(AbstractContainerMenu handler) {
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || !isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            playerSlots.add(slot);
        }

        playerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return playerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    static boolean isPlayerStorageSlot(Slot slot) {
        return slot.container instanceof Inventory
                && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < 36;
    }

    static boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    record SourceShulker(int slotId, int playerIndex) {
    }

    record SourceShulkerScore(long coverageScore, int usefulItemCount, int coveredDemandCount) {
        private boolean isBetterThan(SourceShulkerScore other) {
            return coverageScore > other.coverageScore
                    || coverageScore == other.coverageScore && usefulItemCount > other.usefulItemCount
                    || coverageScore == other.coverageScore
                    && usefulItemCount == other.usefulItemCount
                    && coveredDemandCount > other.coveredDemandCount;
        }
    }

    record SourceStackScore(boolean canMerge, int excessCount, int usefulCount) {
        private boolean isBetterThan(SourceStackScore other) {
            return canMerge && !other.canMerge
                    || canMerge == other.canMerge && excessCount < other.excessCount
                    || canMerge == other.canMerge
                    && excessCount == other.excessCount
                    && usefulCount > other.usefulCount;
        }
    }

    record MissingDemand(ItemStack template, int count) {
    }
}
