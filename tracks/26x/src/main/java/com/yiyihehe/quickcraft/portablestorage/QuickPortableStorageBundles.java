package com.yiyihehe.quickcraft.portablestorage;
import com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus;

import com.yiyihehe.quickcraft.QuickSlotTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer.*;

final class QuickPortableStorageBundles {
    private QuickPortableStorageBundles() {}

    static boolean transfer(Minecraft client, Mode mode, ItemStack stack, int index) {
        var menu = client.player.inventoryMenu;
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
        ItemStack before = mode == Mode.TAKE_ALL ? bundle.getItem().copy() : ItemStack.EMPTY;
        take(client, menu, mode, bundle, entry);
        return mode == Mode.TAKE_ALL && menu.getCarried().isEmpty()
                && !same(bundle.getItem(), before) && findTakeEntry(client, stack) != null;
    }

    private static Entry findTakeEntry(Minecraft client, ItemStack stack) {
        var menu = client.player.inventoryMenu;
        for (Entry entry : entries(client.player, Source.BUNDLE)) {
            Slot bundle = inventorySlot(menu, entry.carrier());
            if (!locked(menu, bundle) && ItemStack.isSameItemSameComponents(entry.stack(), stack)
                    && QuickPortableStorageBundleAccess.canSelect(entry.slot())) {
                return entry;
            }
        }
        return null;
    }

    private static void take(Minecraft client, AbstractContainerMenu menu, Mode mode, Slot bundle, Entry entry) {
        ItemStack stack = entry.stack();
        boolean splitConflict = mode == Mode.ONE && stack.is(Items.ENDER_CHEST) && stack.getCount() > 1;
        boolean offhand = mode == Mode.OFFHAND;
        Slot target = offhand
                ? findPlayerSlot(menu, stack, slot -> slot != bundle && !slot.hasItem())
                : findPlayerTakeSlot(menu, stack, slot -> slot != bundle);
        Slot buffer = splitConflict ? findPlayerSlot(menu, stack, slot -> slot != target && !slot.hasItem()) : null;
        if (target == null || splitConflict && buffer == null
                || offhand && !client.player.getOffhandItem().isEmpty()
                && findPlayerSlot(menu, client.player.getOffhandItem(), slot -> slot != target && !slot.hasItem()) == null) {
            message(client, "no_space");
            return;
        }

        QuickPortableStorageBundleAccess.select(client, bundle, entry.slot());
        click(client, menu, bundle, 1);
        if (!same(menu.getCarried(), stack)) {
            if (!menu.getCarried().isEmpty()) {
                click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            }
            return;
        }
        if (offhand) {
            click(client, menu, target, 0);
            if (!equipOffhand(client, menu, target) && target.hasItem()) {
                click(client, menu, target, 0);
            }
        } else if (splitConflict) {
            click(client, menu, buffer, 0);
            QuickSlotTransfer.moveAmount(client, menu, buffer, target, 1);
            if (buffer.hasItem() && menu.getCarried().isEmpty()) {
                click(client, menu, buffer, 0);
            }
        } else if (mode == Mode.ONE) {
            click(client, menu, target, stack.getCount() == 1 ? 0 : 1);
        } else {
            for (boolean empty : new boolean[]{false, true}) {
                for (Slot slot : menu.slots.reversed()) {
                    ItemStack cursor = menu.getCarried();
                    if (cursor.isEmpty()) {
                        return;
                    }
                    if (QuickSlotTransfer.isPlayerStorageSlot(slot) && slot != bundle
                            && !locked(menu, slot) && slot.hasItem() != empty && slot.mayPlace(cursor)
                            && (empty || ItemStack.isSameItemSameComponents(slot.getItem(), cursor))) {
                        click(client, menu, slot, 0);
                    }
                }
            }
        }
        if (!menu.getCarried().isEmpty()) {
            click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
        }
    }

    private static void store(Minecraft client, AbstractContainerMenu menu, Mode mode, ItemStack stack, int index) {
        Slot origin = inventorySlot(menu, index);
        if (mode != Mode.STORE_CURSOR && (locked(menu, origin) || !same(origin.getItem(), stack))) {
            return;
        }
        Slot bundle = null;
        int count = 0;
        for (Slot candidate : menu.slots) {
            if (candidate == origin || !QuickSlotTransfer.isPlayerStorageSlot(candidate)
                    || locked(menu, candidate) || !candidate.getItem().has(DataComponents.BUNDLE_CONTENTS)) {
                continue;
            }
            int capacity = QuickPortableStorageBundleAccess.capacity(candidate.getItem(), stack);
            if (capacity > count) {
                bundle = candidate;
                count = capacity;
            }
        }
        if (bundle == null) {
            message(client, "no_space");
            return;
        }

        if (origin == null && !stack.has(DataComponents.BUNDLE_CONTENTS)) {
            click(client, menu, bundle, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            return;
        }

        Slot staging = null;
        if (origin == null) {
            staging = findPlayerSlot(menu, stack, slot -> !slot.hasItem());
            if (staging == null) {
                message(client, "no_space");
                return;
            }
            click(client, menu, staging, 0);
            origin = staging;
        } else if (mode == Mode.STORE_ONE && stack.getCount() > 1) {
            staging = findPlayerSlot(menu, stack, slot -> !slot.hasItem());
            if (staging == null || QuickSlotTransfer.moveAmount(client, menu, origin, staging, 1) != 1) {
                message(client, "no_space");
                return;
            }
            origin = staging;
        }
        if (!menu.getCarried().isEmpty()) {
            return;
        }

        // Carry the destination bundle so nested bundles are inserted in the intended direction.
        click(client, menu, bundle, 0);
        if (menu.getCarried().has(DataComponents.BUNDLE_CONTENTS)) {
            click(client, menu, origin, QuickPortableStorageBundleAccess.DEPOSIT_BUTTON);
            click(client, menu, bundle, 0);
        }
        if (staging != null && staging.hasItem() && menu.getCarried().isEmpty()) {
            if (mode == Mode.STORE_CURSOR) {
                click(client, menu, staging, 0);
            } else {
                QuickSlotTransfer.moveAmount(client, menu, staging, inventorySlot(menu, index), 1);
            }
        }
    }

    private static void message(Minecraft client, String key) {
        QuickCrossBoxSortStatus.show(net.minecraft.network.chat.Component.translatable("quickcraft.portable_storage." + key));
    }
}
