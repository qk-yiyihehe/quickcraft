package com.yiyihehe.quickcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * QuickShulker 会把光标中的末影箱右键解释为开放库存，而不是普通放一件。
 * 拆量时只对来源使用右键对半，落点始终使用左键，临时余量归还来源槽。
 */
final class QuickShulkerClickSafety {
    private QuickShulkerClickSafety() {
    }

    static boolean isBundlingConflict(ItemStack stack) {
        return FabricLoader.getInstance().isModLoaded("quickshulker") && stack.is(Items.ENDER_CHEST);
    }

    static int moveExactAmountToSlot(AbstractContainerMenu handler,
                                     int sourceSlotId,
                                     int targetSlotId,
                                     int amount,
                                     Minecraft client) {
        if (amount <= 0 || !handler.getCarried().isEmpty()
                || client.player == null || client.gameMode == null) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        Slot target = handler.getSlot(targetSlotId);
        if (!source.hasItem()
                || !source.mayPickup(client.player)
                || !target.mayPlace(source.getItem())
                || (target.hasItem() && !ItemStack.isSameItemSameComponents(target.getItem(), source.getItem()))) {
            return 0;
        }

        ItemStack template = source.getItem().copyWithCount(1);
        int targetCapacity = Math.min(template.getMaxStackSize(), target.getMaxStackSize(template))
                - (target.hasItem() ? target.getItem().getCount() : 0);
        int targetAmount = Math.min(amount, Math.min(source.getItem().getCount(), targetCapacity));
        if (targetAmount <= 0) {
            return 0;
        }

        int expectedRemainder = source.getItem().getCount() - targetAmount;
        int halvedRemainder = source.getItem().getCount();
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

        int beforeTargetCount = target.hasItem() ? target.getItem().getCount() : 0;
        int moved = 0;
        while (moved < targetAmount && source.hasItem() && handler.getCarried().isEmpty()) {
            int remaining = targetAmount - moved;
            if (source.getItem().getCount() <= remaining) {
                click(handler, client, sourceSlotId, 0, ContainerInput.PICKUP);
            } else {
                // 空光标右键多件末影箱只是原版对半拆分；QuickShulker 的拆包条件要求单件。
                click(handler, client, sourceSlotId, 1, ContainerInput.PICKUP);
                if (handler.getCarried().isEmpty()
                        || !ItemStack.isSameItemSameComponents(handler.getCarried(), template)) {
                    break;
                }
                if (handler.getCarried().getCount() > remaining) {
                    click(handler, client, buffer.index, 0, ContainerInput.PICKUP);
                    if (!handler.getCarried().isEmpty()) {
                        break;
                    }
                    continue;
                }
            }

            if (handler.getCarried().isEmpty()
                    || !ItemStack.isSameItemSameComponents(handler.getCarried(), template)) {
                break;
            }

            int before = target.hasItem() ? target.getItem().getCount() : 0;
            click(handler, client, targetSlotId, 0, ContainerInput.PICKUP);
            int after = target.hasItem() ? target.getItem().getCount() : 0;
            int delta = Math.max(0, after - before);
            moved += delta;
            if (delta <= 0) {
                break;
            }
        }

        restoreTemporarySplitBuffer(handler, client, sourceSlotId, buffer);
        return Math.max(0, (target.hasItem() ? target.getItem().getCount() : 0) - beforeTargetCount);
    }

    private static Slot findEmptyTemporarySplitSlot(AbstractContainerMenu handler,
                                                    int sourceSlotId,
                                                    int targetSlotId,
                                                    ItemStack template) {
        for (Slot slot : handler.slots) {
            if (slot.index == sourceSlotId || slot.index == targetSlotId || slot.hasItem()
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.mayPlace(template)) {
                continue;
            }
            return slot;
        }
        return null;
    }

    private static void restoreTemporarySplitBuffer(AbstractContainerMenu handler,
                                                    Minecraft client,
                                                    int returnSlotId,
                                                    Slot buffer) {
        if (!handler.getCarried().isEmpty()) {
            click(handler, client, returnSlotId, 0, ContainerInput.PICKUP);
        }
        if (buffer == null || !buffer.hasItem() || !handler.getCarried().isEmpty()) {
            return;
        }

        click(handler, client, buffer.index, 0, ContainerInput.PICKUP);
        click(handler, client, returnSlotId, 0, ContainerInput.PICKUP);
        if (!handler.getCarried().isEmpty()) {
            click(handler, client, buffer.index, 0, ContainerInput.PICKUP);
        }
    }

    private static void click(AbstractContainerMenu handler,
                              Minecraft client,
                              int slotId,
                              int button,
                              ContainerInput actionType) {
        client.gameMode.handleContainerInput(handler.containerId, slotId, button, actionType, client.player);
    }
}
