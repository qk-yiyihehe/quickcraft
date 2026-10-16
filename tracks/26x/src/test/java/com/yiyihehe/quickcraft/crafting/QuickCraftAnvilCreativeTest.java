package com.yiyihehe.quickcraft.crafting;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftAnvilCreativeTest {
    @Test
    void appliesTargetsWithoutBooksAndKeepsTheOriginalDataAndCompatibleEnchantments() {
        var ops = QuickCraftAnvilTestAccess.registries().createSerializationContext(JsonOps.INSTANCE);
        var efficiency = Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:efficiency")).getOrThrow();
        var silk = Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:silk_touch")).getOrThrow();
        var fortune = Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:fortune")).getOrThrow();
        var mending = Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:mending")).getOrThrow();
        var seed = new ItemStack(Items.DIAMOND_PICKAXE);
        seed.set(DataComponents.CUSTOM_NAME, Component.literal("My pickaxe"));
        seed.set(DataComponents.REPAIR_COST, 9);
        seed.setDamageValue(42);
        seed.enchant(efficiency, 5);
        seed.enchant(silk, 1);

        var output = QuickCraftAnvilPlanner.creativeOutput(seed, Map.of(efficiency, 4, fortune, 3, mending, 1));

        assertThat(output.getEnchantments().getLevel(mending)).isEqualTo(1);
        assertThat(output.getEnchantments().getLevel(efficiency)).isEqualTo(5);
        assertThat(output.getEnchantments().getLevel(silk)).isEqualTo(1);
        assertThat(output.getEnchantments().getLevel(fortune)).isZero();
        assertThat(output.get(DataComponents.CUSTOM_NAME)).isEqualTo(seed.get(DataComponents.CUSTOM_NAME));
        assertThat(output.get(DataComponents.REPAIR_COST)).isEqualTo(9);
        assertThat(output.getDamageValue()).isEqualTo(42);
        assertThat(seed.getEnchantments().getLevel(mending)).isZero();
    }
}
