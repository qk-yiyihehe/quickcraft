package com.yiyihehe.quickcraft.portablestorage;

import com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus;

import com.yiyihehe.quickcraft.QuickSlotTransfer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import static com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer.*;

final class QuickPortableStorageBundles {
    private QuickPortableStorageBundles() {}

    static boolean transfer(MinecraftClient client, Mode mode, ItemStack stack, int index) {
        var menu = client.player.playerScreenHandler;
        if (isStore(mode)) {
            store(client, menu, mode, stack, index);
            return false;
        }

        Entry entry = findTakeEntry(client, stack);
        if (entry == null) {
            message(client, "bundle_selection");
            return false;
        }
        Slot bundle = inventorySlot(menu, entry.carrier());
        ItemStack before = mode == Mode.TAKE_ALL ? bundle.getStack().copy() : ItemStack.EMPTY;
        take(client, menu, mode, bundle, entry);
        return mode == Mode.TAKE_ALL && menu.getCursorStack().isEmpty() && !same(bundle.getStack(), before)
                && findTakeEntry(client, stack) != null;
    }

    private static Entry findTakeEntry(MinecraftClient client, ItemStack stack) {
        var menu = client.player.playerScreenHandler;
        for (Entry entry : entries(client.player, Source.BUNDLE)) {
            Slot bundle = inventorySlot(menu, entry.carrier());
            if (!locked(menu, bundle) && ItemStack.areItemsAndComponentsEqual(entry.stack(), stack)
                    && QuickPortableStorageBundleAccess.canSelect(entry.slot())) {
                return entry;
            }
        }
        return null;
    }

    private static void take(MinecraftClient client, ScreenHandler menu, Mode mode, Slot bundle, Entry entry) {
        ItemStack stack = entry.stack();
        boolean splitConflict = mode == Mode.ONE && stack.isOf(Items.ENDER_CHEST) && stack.getCount() > 1;
        boolean offhand = mode == Mode.OFFHAND;
        Slot target = offhand ? findPlayerSlot(menu, stack, slot -> slot != bundle && !slot.hasStack())
                : findPlayerTakeSlot(menu, stack, slot -> slot != bundle);
        Slot buffer = splitConflict ? findPlayerSlot(menu, stack, slot -> slot != target && !slot.hasStack()) : null;
        if (target == null || splitConflict && buffer == null || offhand && !client.player.getOffHandStack().isEmpty()
                && findPlayerSlot(
                        menu,
                        client.player.getOffHandStack(),
                        slot -> slot != target && !slot.hasStack()
                ) == null) {
            message(client, "no_space");
            return;
        }

        QuickPortableStorageBundleAccess.select(client, bundle, entry.slot());
        click(client, menu, bundle, 1);
        if (!same(menu.getCursorStack(), stack)) {
            if (!menu.getCursorStack().isEmpty()) {
                click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            }
            return;
        }
        if (offhand) {
            click(client, menu, target, 0);
            if (!equipOffhand(client, menu, target) && target.hasStack()) {
                click(client, menu, target, 0);
            }
        } else if (splitConflict) {
            click(client, menu, buffer, 0);
            QuickSlotTransfer.moveAmount(client, menu, buffer, target, 1);
            if (buffer.hasStack() && menu.getCursorStack().isEmpty()) {
                click(client, menu, buffer, 0);
            }
        } else if (mode == Mode.ONE) {
            click(client, menu, target, stack.getCount() == 1 ? 0 : 1);
        } else {
            for (boolean empty : new boolean[] { false, true }) {
                for (Slot slot : menu.slots.reversed()) {
                    ItemStack cursor = menu.getCursorStack();
                    if (cursor.isEmpty()) {
                        return;
                    }
                    if (QuickSlotTransfer.isPlayerStorageSlot(slot)
                            && slot != bundle
                            && !locked(menu, slot)
                            && slot.hasStack() != empty
                            && slot.canInsert(cursor)
                            && (empty || ItemStack.areItemsAndComponentsEqual(slot.getStack(), cursor))) {
                        click(client, menu, slot, 0);
                    }
                }
            }
        }
        if (!menu.getCursorStack().isEmpty()) {
            click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
        }
    }

    private static void store(MinecraftClient client, ScreenHandler menu, Mode mode, ItemStack stack, int index) {
        Slot origin = inventorySlot(menu, index);
        if (mode != Mode.STORE_CURSOR && (locked(menu, origin) || !same(origin.getStack(), stack))) {
            return;
        }
        Slot bundle = null;
        int count = 0;
        for (Slot candidate : menu.slots) {
            if (candidate == origin || !QuickSlotTransfer.isPlayerStorageSlot(candidate) || locked(menu, candidate)
                    || !candidate.getStack().contains(DataComponentTypes.BUNDLE_CONTENTS)) {
                continue;
            }
            int capacity = QuickPortableStorageBundleAccess.capacity(candidate.getStack(), stack);
            if (capacity > count) {
                bundle = candidate;
                count = capacity;
            }
        }
        if (bundle == null) {
            message(client, "no_space");
            return;
        }

        if (origin == null && !stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            return;
        }

        Slot staging = null;
        if (origin == null) {
            staging = findPlayerSlot(menu, stack, slot -> !slot.hasStack());
            if (staging == null) {
                message(client, "no_space");
                return;
            }
            click(client, menu, staging, 0);
            origin = staging;
        } else if (mode == Mode.STORE_ONE && stack.getCount() > 1) {
            staging = findPlayerSlot(menu, stack, slot -> !slot.hasStack());
            if (staging == null || QuickSlotTransfer.moveAmount(client, menu, origin, staging, 1) != 1) {
                message(client, "no_space");
                return;
            }
            origin = staging;
        }
        if (!menu.getCursorStack().isEmpty()) {
            return;
        }

        // 保留目标收纳袋的信息，以确定嵌套收纳袋的放入方向。
        click(client, menu, bundle, 0);
        if (menu.getCursorStack().contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            click(client, menu, origin, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            click(client, menu, bundle, 0);
        }
        if (staging != null && staging.hasStack() && menu.getCursorStack().isEmpty()) {
            if (mode == Mode.STORE_CURSOR) {
                click(client, menu, staging, 0);
            } else {
                QuickSlotTransfer.moveAmount(client, menu, staging, inventorySlot(menu, index), 1);
            }
        }
    }

    private static void message(MinecraftClient client, String key) {
        QuickCrossBoxSortStatus.show(net.minecraft.text.Text.translatable("quickcraft.portable_storage." + key));
    }
}
