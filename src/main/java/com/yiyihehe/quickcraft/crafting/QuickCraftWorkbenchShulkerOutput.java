package com.yiyihehe.quickcraft.crafting;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
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

        Slot target = findBox(handler, output);
        if (target == null) {
            target = splitEmptyBox(client, handler);
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

        return QuickCraftWorkbenchShulkerContents.get(shulker).capacity(output);
    }

    static int calculateCapacity(int maxStackSize,
                                 int usedSlots,
                                 int matchingStackCapacity) {
        return Math.max(0, matchingStackCapacity)
                + Math.max(0, 27 - usedSlots) * Math.max(0, maxStackSize);
    }

    private static Slot findBox(CraftingScreenHandler handler,
                                ItemStack output) {
        Slot emptyCandidate = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }
            QuickCraftWorkbenchShulkerContents.Contents contents =
                    QuickCraftWorkbenchShulkerContents.get(slot.getIndex(), slot.getStack());
            boolean outputOnly = contents.containsOnly(output);
            int priority = outputBoxContentPriority(contents.count() == 0,
                    outputOnly,
                    outputOnly && contents.capacity(output) >= output.getCount());
            if (priority == 0) {
                return slot;
            }
            if (priority == 1 && emptyCandidate == null) {
                emptyCandidate = slot;
            }
        }
        return emptyCandidate;
    }

    private static Slot splitEmptyBox(MinecraftClient client, CraftingScreenHandler handler) {
        List<Slot> slots = getPlayerStorageSlots(handler);
        Slot source = null;
        for (Slot slot : slots) {
            if (slot.getStack().getCount() > 1 && isShulkerBox(slot.getStack())
                    && slot.canTakeItems(client.player)
                    && QuickCraftWorkbenchShulkerContents.get(slot.getIndex(), slot.getStack()).count() == 0) {
                source = slot;
                break;
            }
        }
        if (source == null) {
            return null;
        }

        ItemStack original = source.getStack().copy();
        for (Slot target : slots) {
            if (target.hasStack() || !target.canInsert(original)) {
                continue;
            }
            client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                    SlotActionType.PICKUP, client.player);
            if (source.hasStack() || handler.getCursorStack().getCount() != original.getCount()
                    || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), original)) {
                return null;
            }
            // 普通右键会被 QuickShulker 当作拆包；右键拖拽只在空格放一个空盒。
            client.interactionManager.clickSlot(handler.syncId, -999, ScreenHandler.packQuickCraftData(0, 1),
                    SlotActionType.QUICK_CRAFT, client.player);
            client.interactionManager.clickSlot(handler.syncId, target.id, ScreenHandler.packQuickCraftData(1, 1),
                    SlotActionType.QUICK_CRAFT, client.player);
            client.interactionManager.clickSlot(handler.syncId, -999, ScreenHandler.packQuickCraftData(2, 1),
                    SlotActionType.QUICK_CRAFT, client.player);
            if (!source.hasStack() && ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), original)) {
                client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                        SlotActionType.PICKUP, client.player);
            }
            if (handler.getCursorStack().isEmpty()
                    && source.getStack().getCount() == original.getCount() - 1
                    && ItemStack.areItemsAndComponentsEqual(source.getStack(), original)
                    && isSingleShulker(target.getStack())
                    && ItemStack.areItemsAndComponentsEqual(target.getStack(), original)) {
                return target;
            }
            return null;
        }
        return null;
    }

    static int outputBoxContentPriority(boolean empty,
                                        boolean outputOnly,
                                        boolean enoughCapacity) {
        if (!outputOnly || !enoughCapacity) {
            return -1;
        }
        return empty ? 1 : 0;
    }

    private static boolean isSingleShulker(ItemStack stack) {
        return stack.getCount() == 1 && isShulkerBox(stack);
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
