package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import java.util.List;

final class QuickPortableStorageBundleAccess {
    static final int DEPOSIT_BUTTON = 0;

    private QuickPortableStorageBundleAccess() {}

    static List<ItemStack> contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopies().toList();
    }

    static int capacity(ItemStack bundle, ItemStack stack) {
        return bundle.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).asMutable()
                .tryInsert(stack.copy());
    }

    static boolean canSelect(int index) {
        return index >= 0;
    }

    static void select(Minecraft client, Slot slot, int index) {
        var contents = slot.getItem().getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        if (contents.getSelectedItemIndex() != index) {
            BundleItem.toggleSelectedItem(slot.getItem(), index);
        }
        // The protocol toggles selection, including when the server already has this index selected.
        client.getConnection().send(new ServerboundSelectBundleItemPacket(slot.index, -1));
        client.getConnection().send(new ServerboundSelectBundleItemPacket(slot.index, index));
    }
}
