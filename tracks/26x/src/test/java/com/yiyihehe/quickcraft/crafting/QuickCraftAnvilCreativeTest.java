package com.yiyihehe.quickcraft.crafting;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftAnvilCreativeTest {
    @Test
    void appliesTargetsWithoutBooksAndKeepsTheOriginalDataAndCompatibleEnchantments() {
        var ops = QuickCraftAnvilTestAccess.registries().createSerializationContext(JsonOps.INSTANCE);
        var efficiency = withTags(Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:efficiency")).getOrThrow(), false);
        var silk = withTags(Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:silk_touch")).getOrThrow(), true);
        var fortune = withTags(Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:fortune")).getOrThrow(), true);
        var mending = withTags(Enchantment.CODEC.parse(ops, new JsonPrimitive("minecraft:mending")).getOrThrow(), false);
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

    private static Holder<Enchantment> withTags(Holder<Enchantment> entry, boolean miningExclusive) {
        var tagged = spy(entry);
        // 数据生成注册表不绑定标签；只补充本用例需要的原版挖掘互斥组。
        doAnswer(invocation -> miningExclusive && EnchantmentTags.MINING_EXCLUSIVE.equals(invocation.getArgument(0)))
                .when(tagged).is(org.mockito.ArgumentMatchers.<TagKey<Enchantment>>any());
        return tagged;
    }
}
