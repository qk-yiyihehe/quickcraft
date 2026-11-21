package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.screen.slot.Slot;

import java.util.List;

final class QuickPortableStorageBundleAccess {
    static final int DEPOSIT_BUTTON = 0;

    private QuickPortableStorageBundleAccess() {}

    static List<ItemStack> contents(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT).stream().toList();
    }

    static int capacity(ItemStack bundle, ItemStack stack) {
        return new BundleContentsComponent.Builder(bundle.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS,
                BundleContentsComponent.DEFAULT)).add(stack.copy());
    }

    static boolean canSelect(int index) {
        return index >= 0;
    }

    static void select(MinecraftClient client, Slot slot, int index) {
        BundleItem.setSelectedStackIndex(slot.getStack(), index);
        client.getNetworkHandler().sendPacket(new BundleItemSelectedC2SPacket(slot.id, index));
    }
}
