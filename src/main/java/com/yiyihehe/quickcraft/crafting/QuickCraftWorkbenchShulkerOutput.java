package com.yiyihehe.quickcraft.crafting;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 潜影盒工作台喷射的产物装盒策略。目标盒选择不受格子锁影响。
 */
final class QuickCraftWorkbenchShulkerOutput {
    private QuickCraftWorkbenchShulkerOutput() {
    }

    static int takeBox(MinecraftClient client,
                       CraftingScreenHandler handler,
                       ItemStack output) {
        if (client.player == null || client.interactionManager == null
                || !handler.getCursorStack().isEmpty() || output.isEmpty() || isShulkerBox(output)) {
            return -1;
        }

        Slot target = findBox(handler, output, false);
        if (target == null) {
            target = findBox(handler, output, true);
        }
        if (target == null) {
            return -1;
        }

        client.interactionManager.clickSlot(handler.syncId, target.id, 0,
                SlotActionType.PICKUP, client.player);
        if (isSingleShulker(handler.getCursorStack())
                && getCapacity(handler.getCursorStack(), output) >= output.getCount()) {
            return target.id;
        }
        if (!handler.getCursorStack().isEmpty() && !target.hasStack()) {
            client.interactionManager.clickSlot(handler.syncId, target.id, 0,
                    SlotActionType.PICKUP, client.player);
        }
        return -1;
    }

    static boolean storeOnce(MinecraftClient client,
                             CraftingScreenHandler handler,
                             ItemStack output) {
        if (client.player == null || client.interactionManager == null
                || !handler.getSlot(0).hasStack()
                || !ItemStack.areItemsAndComponentsEqual(handler.getSlot(0).getStack(), output)
                || getCapacity(handler.getCursorStack(), output) < output.getCount()) {
            return false;
        }

        int capacityBefore = getCapacity(handler.getCursorStack(), output);
        client.interactionManager.clickSlot(handler.syncId, 0, 1,
                SlotActionType.PICKUP, client.player);
        return getCapacity(handler.getCursorStack(), output) < capacityBefore;
    }

    static boolean returnBox(MinecraftClient client,
                             CraftingScreenHandler handler,
                             int sourceSlotId) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        if (client.player == null || client.interactionManager == null
                || sourceSlotId < 0 || sourceSlotId >= handler.slots.size()
                || handler.getSlot(sourceSlotId).hasStack()) {
            return false;
        }
        client.interactionManager.clickSlot(handler.syncId, sourceSlotId, 0,
                SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
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
            if (ItemStack.areItemsAndComponentsEqual(stored, output)) {
                matchingCapacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }
        return calculateCapacity(output.getMaxCount(), usedSlots, matchingCapacity);
    }

    static int calculateCapacity(int maxStackSize,
                                 int usedSlots,
                                 int matchingStackCapacity) {
        return Math.max(0, matchingStackCapacity)
                + Math.max(0, 27 - usedSlots) * Math.max(0, maxStackSize);
    }

    private static Slot findBox(CraftingScreenHandler handler,
                                ItemStack output,
                                boolean requireEmpty) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }
            boolean empty = getStoredStacks(slot.getStack()).isEmpty();
            if (empty != requireEmpty || !isOutputOnlyBox(slot.getStack(), output)) {
                continue;
            }
            if (getCapacity(slot.getStack(), output) >= output.getCount()) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isOutputOnlyBox(ItemStack shulker, ItemStack output) {
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!ItemStack.areItemsAndComponentsEqual(stored, output)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSingleShulker(ItemStack stack) {
        return stack.getCount() == 1 && isShulkerBox(stack);
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            stacks.add(stack);
        }
        return stacks;
    }

    private static List<Slot> getPlayerStorageSlots(CraftingScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory
                    && slot.getIndex() >= 0
                    && slot.getIndex() < PlayerInventory.MAIN_SIZE
                    && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        // 工作台界面中主背包（9..35）位于快捷栏上方，优先从那里取产物盒，避免占用常用快捷栏。
        slots.sort(Comparator.comparingInt((Slot slot) -> outputBoxScanPriority(slot.getIndex()))
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));
        return slots;
    }

    static int outputBoxScanPriority(int inventoryIndex) {
        if (inventoryIndex >= 9 && inventoryIndex < PlayerInventory.MAIN_SIZE) {
            return 0;
        }
        if (inventoryIndex >= 0 && inventoryIndex < 9) {
            return 1;
        }
        return 2;
    }
}
