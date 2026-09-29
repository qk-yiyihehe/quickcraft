package com.yiyihehe.quickcraft;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import java.util.List;

/** 26.3 的 Bundle API 访问：使用无参构造器和新的副本遍历方法。 */
final class QuickCreativePackingBundleAccess {
    private QuickCreativePackingBundleAccess() {
    }

    static BundleContents.Mutable newBuilder() {
        return new BundleContents.Mutable();
    }

    static List<ItemStack> itemCopies(BundleContents contents) {
        return contents.itemCopies().toList();
    }
}
