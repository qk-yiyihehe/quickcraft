package com.yiyihehe.quickcraft.crafting;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

final class QuickCraftAnvilTestAccess {
    private QuickCraftAnvilTestAccess() {}
    static HolderLookup.Provider registries() { return VanillaRegistries.createLookup(); }
}
