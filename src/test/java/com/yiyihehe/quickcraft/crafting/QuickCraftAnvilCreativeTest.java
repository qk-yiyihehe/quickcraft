package com.yiyihehe.quickcraft.crafting;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftAnvilCreativeTest {
    @Test
    void appliesTargetsWithoutBooksAndKeepsTheOriginalDataAndCompatibleEnchantments() {
        var ops = net.minecraft.registry.BuiltinRegistries.createWrapperLookup().getOps(JsonOps.INSTANCE);
        var efficiency = Enchantment.ENTRY_CODEC.parse(ops, new JsonPrimitive("minecraft:efficiency")).getOrThrow();
        var silk = Enchantment.ENTRY_CODEC.parse(ops, new JsonPrimitive("minecraft:silk_touch")).getOrThrow();
        var fortune = Enchantment.ENTRY_CODEC.parse(ops, new JsonPrimitive("minecraft:fortune")).getOrThrow();
        var mending = Enchantment.ENTRY_CODEC.parse(ops, new JsonPrimitive("minecraft:mending")).getOrThrow();
        var seed = new ItemStack(Items.DIAMOND_PICKAXE);
        seed.set(DataComponentTypes.CUSTOM_NAME, Text.literal("My pickaxe"));
        seed.set(DataComponentTypes.REPAIR_COST, 9);
        seed.setDamage(42);
        seed.addEnchantment(efficiency, 5);
        seed.addEnchantment(silk, 1);

        var output = QuickCraftAnvilPlanner.creativeOutput(seed, Map.of(efficiency, 4, fortune, 3, mending, 1));

        assertThat(output.getEnchantments().getLevel(mending)).isEqualTo(1);
        assertThat(output.getEnchantments().getLevel(efficiency)).isEqualTo(5);
        assertThat(output.getEnchantments().getLevel(silk)).isEqualTo(1);
        assertThat(output.getEnchantments().getLevel(fortune)).isZero();
        assertThat(output.get(DataComponentTypes.CUSTOM_NAME)).isEqualTo(seed.get(DataComponentTypes.CUSTOM_NAME));
        assertThat(output.get(DataComponentTypes.REPAIR_COST)).isEqualTo(9);
        assertThat(output.getDamage()).isEqualTo(42);
        assertThat(seed.getEnchantments().getLevel(mending)).isZero();
    }
}
