package com.yiyihehe.quickcraft;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.collection.DefaultedList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 容器复制的来源选择、需求计算和槽位布局；不发送点击或维护填充会话。 */
final class QuickContainerCopySources {
    private QuickContainerCopySources() {
    }

    private static final int VANILLA_SHULKER_SLOTS = 27;

    static int findBestReplacementSourceSlotId(ScreenHandler handler, ItemStack template) {
        int neededCount = template.getCount();
        int bestUnderSlotId = -1;
        int bestUnderCount = 0;
        int bestOverSlotId = -1;
        int bestOverCount = Integer.MAX_VALUE;

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                continue;
            }

            int count = slot.getStack().getCount();
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

    static SourceShulker findSourceShulkerForDemandsExcept(ScreenHandler handler,
                                                            List<MissingDemand> demands,
                                                            int excludedPlayerIndex) {
        if (demands.isEmpty()) {
            return null;
        }

        SourceShulker bestSource = null;
        SourceShulkerScore bestScore = null;
        for (int shulkerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot shulkerSlot = handler.getSlot(shulkerSlotId);
            if (shulkerSlot.getIndex() == excludedPlayerIndex) {
                continue;
            }

            if (!shulkerSlot.hasStack()
                    || shulkerSlot.getStack().getCount() != 1
                    || !isShulkerBox(shulkerSlot.getStack())) {
                continue;
            }

            SourceShulkerScore score = getSourceShulkerScore(shulkerSlot.getStack(), demands);
            if (score.usefulItemCount() <= 0
                    || bestScore != null && !score.isBetterThan(bestScore)) {
                continue;
            }

            bestSource = new SourceShulker(shulkerSlotId, shulkerSlot.getIndex());
            bestScore = score;
        }

        return bestSource;
    }

    static SourceShulkerScore getSourceShulkerScore(ItemStack shulker, List<MissingDemand> demands) {
        int usefulItemCount = 0;
        int coveredDemandCount = 0;
        long coverageScore = 0;
        DefaultedList<ItemStack> storedStacks = getStoredStacksBySlot(shulker);

        for (MissingDemand demand : demands) {
            int available = 0;
            for (ItemStack stack : storedStacks) {
                if (ItemStack.areItemsAndComponentsEqual(stack, demand.template())) {
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

    static int findBestSourceContainerSlotId(ShulkerBoxScreenHandler handler,
                                               List<Integer> sourceSlotIds,
                                               List<MissingDemand> demands) {
        int bestSlotId = -1;
        SourceStackScore bestScore = null;

        for (int slotId : sourceSlotIds) {
            Slot slot = handler.getSlot(slotId);
            MissingDemand demand = slot.hasStack() ? findDemandForStack(demands, slot.getStack()) : null;
            if (demand == null
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.canTakeItems(MinecraftClient.getInstance().player)
                    || !canStoreAnyStackInPlayerStorage(handler, slot.getStack())) {
                continue;
            }

            int usefulCount = Math.min(slot.getStack().getCount(), demand.count());
            int excessCount = Math.max(0, slot.getStack().getCount() - demand.count());
            SourceStackScore score = new SourceStackScore(
                    hasMatchingPlayerStorageCapacity(handler, slot.getStack()),
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

    static boolean hasMatchingPlayerStorageCapacity(ScreenHandler handler, ItemStack stack) {
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack() || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)) {
                continue;
            }

            int maxCount = Math.min(slot.getStack().getMaxCount(), slot.getMaxItemCount(stack));
            if (slot.getStack().getCount() < maxCount) {
                return true;
            }
        }

        return false;
    }

    static MissingDemand findDemandForStack(List<MissingDemand> demands, ItemStack stack) {
        for (MissingDemand demand : demands) {
            if (demand.count() > 0 && ItemStack.areItemsAndComponentsEqual(stack, demand.template())) {
                return demand;
            }
        }

        return null;
    }

    static int countMatchingPlayerStorage(ScreenHandler handler, ItemStack template) {
        int count = 0;
        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasStack() && ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
                count += slot.getStack().getCount();
            }
        }
        return count;
    }

    static List<MissingDemand> subtractMissingDemand(List<MissingDemand> demands, ItemStack template, int moved) {
        List<MissingDemand> remaining = new ArrayList<>(demands.size());
        for (MissingDemand demand : demands) {
            if (!ItemStack.areItemsAndComponentsEqual(demand.template(), template)) {
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

    static DefaultedList<ItemStack> getStoredStacksBySlot(ItemStack shulker) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(VANILLA_SHULKER_SLOTS, ItemStack.EMPTY);
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        container.copyTo(stacks);
        return stacks;
    }

    static boolean canStoreAnyStackInPlayerStorage(ScreenHandler handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int slotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.isEnabled() || !slot.canInsert(stack)) {
                continue;
            }

            if (!slot.hasStack()) {
                return true;
            }

            ItemStack existing = slot.getStack();
            if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxCount(), slot.getMaxItemCount(stack))) {
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
            if (ItemStack.areItemsAndComponentsEqual(stored, insertStack)) {
                capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }

        return capacity + Math.max(0, VANILLA_SHULKER_SLOTS - usedSlots) * insertStack.getMaxCount();
    }

    static List<Integer> getContainerSlotIds(ScreenHandler handler) {
        if (handler instanceof AbstractFurnaceScreenHandler
                || handler instanceof BrewingStandScreenHandler) {
            return getContainerSlotIdsByInventoryIndex(handler);
        }
        if (handler instanceof CrafterScreenHandler crafterHandler) {
            List<Slot> crafterSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (!isVisibleSlot(slot) || slot.inventory != crafterHandler.getInputInventory()) {
                    continue;
                }
                crafterSlots.add(slot);
            }

            crafterSlots.sort(Comparator
                    .comparingInt((Slot slot) -> slot.y)
                    .thenComparingInt(slot -> slot.x)
                    .thenComparingInt(slot -> slot.id));

            return crafterSlots.stream()
                    .map(slot -> slot.id)
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
                .thenComparingInt(slot -> slot.id));

        return containerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    static List<Integer> getContainerSlotIdsByInventoryIndex(ScreenHandler handler) {
        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));

        return containerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    static List<Integer> getPlayerStorageSlotIds(ScreenHandler handler) {
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
                .comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));

        return playerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    static boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    static boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
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
