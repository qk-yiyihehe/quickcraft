package com.yiyihehe.quickcraft.crafting;

import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 潜影盒工作台喷射的产物装盒策略。目标盒选择不受格子锁影响。
 */
final class QuickCraftWorkbenchShulkerOutput {
    private QuickCraftWorkbenchShulkerOutput() {
    }

    static int takeBox(Minecraft client,
                       CraftingMenu handler,
                       ItemStack output) {
        if (client.player == null || client.gameMode == null
                || !handler.getCarried().isEmpty() || output.isEmpty() || isShulkerBox(output)) {
            return -1;
        }

        Slot target = findBox(handler, output);
        if (target == null) {
            return -1;
        }

        client.gameMode.handleContainerInput(handler.containerId, target.index, 0,
                ContainerInput.PICKUP, client.player);
        if (isSingleShulker(handler.getCarried())
                && getCapacity(handler.getCarried(), output) >= output.getCount()) {
            return target.index;
        }
        if (!handler.getCarried().isEmpty() && !target.hasItem()) {
            client.gameMode.handleContainerInput(handler.containerId, target.index, 0,
                    ContainerInput.PICKUP, client.player);
        }
        return -1;
    }

    static boolean storeOnce(Minecraft client,
                             CraftingMenu handler,
                             ItemStack output) {
        if (client.player == null || client.gameMode == null
                || !handler.getSlot(0).hasItem()
                || !ItemStack.isSameItemSameComponents(handler.getSlot(0).getItem(), output)
                || getCapacity(handler.getCarried(), output) < output.getCount()) {
            return false;
        }

        int capacityBefore = getCapacity(handler.getCarried(), output);
        client.gameMode.handleContainerInput(handler.containerId, 0, 1,
                ContainerInput.PICKUP, client.player);
        return getCapacity(handler.getCarried(), output) < capacityBefore;
    }

    static boolean returnBox(Minecraft client,
                             CraftingMenu handler,
                             int sourceSlotId) {
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        if (client.player == null || client.gameMode == null
                || sourceSlotId < 0 || sourceSlotId >= handler.slots.size()
                || handler.getSlot(sourceSlotId).hasItem()) {
            return false;
        }
        client.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0,
                ContainerInput.PICKUP, client.player);
        return handler.getCarried().isEmpty();
    }

    static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    static int getCapacity(ItemStack shulker, ItemStack output) {
        if (!isSingleShulker(shulker) || output.isEmpty() || isShulkerBox(output)) {
            return 0;
        }

        int usedSlots = 0;
        int matchingCapacity = 0;
        for (ItemStack stored : getStoredStacks(shulker)) {
            usedSlots++;
            if (ItemStack.isSameItemSameComponents(stored, output)) {
                matchingCapacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }
        return calculateCapacity(output.getMaxStackSize(), usedSlots, matchingCapacity);
    }

    static int calculateCapacity(int maxStackSize,
                                 int usedSlots,
                                 int matchingStackCapacity) {
        return Math.max(0, matchingStackCapacity)
                + Math.max(0, 27 - usedSlots) * Math.max(0, maxStackSize);
    }

    private static Slot findBox(CraftingMenu handler,
                                ItemStack output) {
        Slot emptyCandidate = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasItem() || slot.getItem().getCount() != 1 || !isShulkerBox(slot.getItem())) {
                continue;
            }
            boolean empty = getStoredStacks(slot.getItem()).isEmpty();
            int priority = outputBoxContentPriority(empty,
                    isOutputOnlyBox(slot.getItem(), output),
                    getCapacity(slot.getItem(), output) >= output.getCount());
            if (priority == 0) {
                return slot;
            }
            if (priority == 1 && emptyCandidate == null) {
                emptyCandidate = slot;
            }
        }
        return emptyCandidate;
    }

    static int outputBoxContentPriority(boolean empty,
                                        boolean outputOnly,
                                        boolean enoughCapacity) {
        if (!outputOnly || !enoughCapacity) {
            return -1;
        }
        return empty ? 1 : 0;
    }

    private static boolean isOutputOnlyBox(ItemStack shulker, ItemStack output) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!ItemStack.isSameItemSameComponents(stored, output)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSingleShulker(ItemStack stack) {
        return stack.getCount() == 1 && isShulkerBox(stack);
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.nonEmptyItemCopyStream().toList()) {
            stacks.add(stack);
        }
        return stacks;
    }

    private static List<Slot> getPlayerStorageSlots(CraftingMenu handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory
                    && slot.getContainerSlot() >= 0
                    && slot.getContainerSlot() < Inventory.INVENTORY_SIZE
                    && slot.isActive()) {
                slots.add(slot);
            }
        }
        // 工作台界面中主背包（9..35）位于快捷栏上方，优先从那里取产物盒，避免占用常用快捷栏。
        slots.sort(Comparator.comparingInt((Slot slot) -> outputBoxScanPriority(slot.getContainerSlot()))
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));
        return slots;
    }

    static int outputBoxScanPriority(int inventoryIndex) {
        if (inventoryIndex >= 9 && inventoryIndex < Inventory.INVENTORY_SIZE) {
            return 0;
        }
        if (inventoryIndex >= 0 && inventoryIndex < 9) {
            return 1;
        }
        return 2;
    }
}
