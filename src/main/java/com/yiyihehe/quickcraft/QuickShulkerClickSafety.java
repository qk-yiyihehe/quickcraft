package com.yiyihehe.quickcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * QuickShulker 会把光标中的末影箱右键解释为开放库存，而不是普通放一件。
 * 拆量时只对来源使用右键对半，落点始终使用左键，临时余量归还来源槽。
 */
final class QuickShulkerClickSafety {
    private QuickShulkerClickSafety() {
    }

    static boolean isBundlingConflict(ItemStack stack) {
        return FabricLoader.getInstance().isModLoaded("quickshulker") && stack.isOf(Items.ENDER_CHEST);
    }

    static int moveExactAmountToSlot(ScreenHandler handler,
                                     int sourceSlotId,
                                     int targetSlotId,
                                     int amount,
                                     MinecraftClient client) {
        if (amount <= 0 || !handler.getCursorStack().isEmpty()
                || client.player == null || client.interactionManager == null) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        Slot target = handler.getSlot(targetSlotId);
        if (!source.hasStack()
                || !source.canTakeItems(client.player)
                || !target.canInsert(source.getStack())
                || (target.hasStack() && !ItemStack.areItemsAndComponentsEqual(target.getStack(), source.getStack()))) {
            return 0;
        }

        ItemStack template = source.getStack().copyWithCount(1);
        int targetCapacity = Math.min(template.getMaxCount(), target.getMaxItemCount(template))
                - (target.hasStack() ? target.getStack().getCount() : 0);
        int targetAmount = Math.min(amount, Math.min(source.getStack().getCount(), targetCapacity));
        if (targetAmount <= 0) {
            return 0;
        }

        int expectedRemainder = source.getStack().getCount() - targetAmount;
        int halvedRemainder = source.getStack().getCount();
        // 连续对半即可留下所需余量时，不要求额外空槽。
        while (halvedRemainder > expectedRemainder) {
            halvedRemainder /= 2;
        }
        boolean needsBuffer = halvedRemainder != expectedRemainder;
        Slot buffer = needsBuffer
                ? findEmptyTemporarySplitSlot(handler, sourceSlotId, targetSlotId, template)
                : null;
        if (needsBuffer && buffer == null) {
            return 0;
        }

        int beforeTargetCount = target.hasStack() ? target.getStack().getCount() : 0;
        int moved = 0;
        while (moved < targetAmount && source.hasStack() && handler.getCursorStack().isEmpty()) {
            int remaining = targetAmount - moved;
            if (source.getStack().getCount() <= remaining) {
                click(handler, client, sourceSlotId, 0, SlotActionType.PICKUP);
            } else {
                // 空光标右键多件末影箱只是原版对半拆分；QuickShulker 的拆包条件要求单件。
                click(handler, client, sourceSlotId, 1, SlotActionType.PICKUP);
                if (handler.getCursorStack().isEmpty()
                        || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) {
                    break;
                }
                if (handler.getCursorStack().getCount() > remaining) {
                    click(handler, client, buffer.id, 0, SlotActionType.PICKUP);
                    if (!handler.getCursorStack().isEmpty()) {
                        break;
                    }
                    continue;
                }
            }

            if (handler.getCursorStack().isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) {
                break;
            }

            int before = target.hasStack() ? target.getStack().getCount() : 0;
            click(handler, client, targetSlotId, 0, SlotActionType.PICKUP);
            int after = target.hasStack() ? target.getStack().getCount() : 0;
            int delta = Math.max(0, after - before);
            moved += delta;
            if (delta <= 0) {
                break;
            }
        }

        restoreTemporarySplitBuffer(handler, client, sourceSlotId, buffer);
        return Math.max(0, (target.hasStack() ? target.getStack().getCount() : 0) - beforeTargetCount);
    }

    private static Slot findEmptyTemporarySplitSlot(ScreenHandler handler,
                                                    int sourceSlotId,
                                                    int targetSlotId,
                                                    ItemStack template) {
        for (Slot slot : handler.slots) {
            if (slot.id == sourceSlotId || slot.id == targetSlotId || slot.hasStack()
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.canInsert(template)) {
                continue;
            }
            return slot;
        }
        return null;
    }

    private static void restoreTemporarySplitBuffer(ScreenHandler handler,
                                                    MinecraftClient client,
                                                    int returnSlotId,
                                                    Slot buffer) {
        if (!handler.getCursorStack().isEmpty()) {
            click(handler, client, returnSlotId, 0, SlotActionType.PICKUP);
        }
        if (buffer == null || !buffer.hasStack() || !handler.getCursorStack().isEmpty()) {
            return;
        }

        click(handler, client, buffer.id, 0, SlotActionType.PICKUP);
        click(handler, client, returnSlotId, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) {
            click(handler, client, buffer.id, 0, SlotActionType.PICKUP);
        }
    }

    private static void click(ScreenHandler handler,
                              MinecraftClient client,
                              int slotId,
                              int button,
                              SlotActionType actionType) {
        client.interactionManager.clickSlot(handler.syncId, slotId, button, actionType, client.player);
    }
}
