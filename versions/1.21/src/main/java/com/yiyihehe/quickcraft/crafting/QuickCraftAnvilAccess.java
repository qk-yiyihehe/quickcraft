package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import java.util.List;

final class QuickCraftAnvilAccess {
    private QuickCraftAnvilAccess() {}
    static List<RegistryEntry.Reference<Enchantment>> enchantments(MinecraftClient client) {
        return client.world.getRegistryManager().get(RegistryKeys.ENCHANTMENT).streamEntries().toList();
    }
}
