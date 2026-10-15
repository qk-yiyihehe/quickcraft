package com.yiyihehe.quickcraft.crafting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import java.util.List;
final class QuickCraftAnvilAccess {
    private QuickCraftAnvilAccess() {}
    static List<Holder.Reference<Enchantment>> enchantments(Minecraft client) {
        return client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().toList();
    }
}
