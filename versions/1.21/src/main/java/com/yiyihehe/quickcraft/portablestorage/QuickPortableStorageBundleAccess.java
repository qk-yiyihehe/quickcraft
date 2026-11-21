package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

import java.util.List;

final class QuickPortableStorageBundleAccess {
    static final int DEPOSIT_BUTTON = 1;

    private QuickPortableStorageBundleAccess() {}

    static List<ItemStack> contents(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT).stream().toList();
    }

    static int capacity(ItemStack bundle, ItemStack stack) {
        return new BundleContentsComponent.Builder(bundle.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS,
                BundleContentsComponent.DEFAULT)).add(stack.copy());
    }

    static boolean canSelect(int index) {
        return index == 0;
    }

    // Vanilla 1.21–1.21.1 always removes the first stack.
    static void select(MinecraftClient client, Slot slot, int index) {
    }
}
