package com.yiyihehe.quickcraft.litematica;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaGlobalReplacementsTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void restoresStackLimitRatiosAndUnstackableTargets() {
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(item("minecraft:shears")),
                new ItemStack(item("minecraft:oak_planks"))).getCount()).isEqualTo(64);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(item("minecraft:oak_planks"), 64),
                new ItemStack(item("minecraft:shears"))).getCount()).isEqualTo(1);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(item("minecraft:oak_planks"), 16),
                new ItemStack(item("minecraft:ender_pearl"))).getCount()).isEqualTo(4);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(item("minecraft:ender_pearl"), 4),
                new ItemStack(item("minecraft:oak_planks"))).getCount()).isEqualTo(16);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(item("minecraft:oak_planks")),
                new ItemStack(item("minecraft:ender_pearl"))).getCount()).isEqualTo(1);
    }

    @Test
    void manualOptOutWinsAndGlobalsNeverChain() {
        var manualConfig = QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS;
        var globalConfig = QuickCraftConfigs.ProjectionTools.GLOBAL_CONTAINER_REPLACEMENTS;
        var master = QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_MATERIAL_REPLACEMENTS;
        var oldManual = List.copyOf(manualConfig.getStrings());
        var oldGlobals = List.copyOf(globalConfig.getStrings());
        boolean oldEnabled = master.getBooleanValue();
        var lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try {
            master.setBooleanValue(true);
            manualConfig.setStrings(List.of(QuickLitematicaContainerReplacements.encodeContainerRule(
                    new QuickLitematicaContainerReplacements.ContainerReplacement(new ItemStack(item("minecraft:stone")),
                            new ItemStack(item("minecraft:stone"), 17), "test", "group", 0, false), lookup)));
            globalConfig.setStrings(List.of("minecraft:stone->minecraft:glass", "minecraft:glass->minecraft:dirt"));
            var slots = List.of(new ItemStack(item("minecraft:stone"), 17), new ItemStack(item("minecraft:stone"), 32));
            var result = QuickLitematicaGlobalReplacements.containerSlots(slots, "test", "group");
            assertThat(result.get(0).getItem()).isSameAs(item("minecraft:stone"));
            assertThat(result.get(1).getItem()).isSameAs(item("minecraft:glass"));
            assertThat(result.get(1).getCount()).isEqualTo(32);
            assertThat(slots.get(1).getItem()).isSameAs(item("minecraft:stone"));
            master.setBooleanValue(false);
            var disabled = QuickLitematicaGlobalReplacements.containerSlots(slots, "test", "group");
            assertThat(disabled.get(1).getItem()).isSameAs(item("minecraft:stone"));
            assertThat(globalConfig.getStrings()).hasSize(2);
        } finally {
            manualConfig.setStrings(oldManual);
            globalConfig.setStrings(oldGlobals);
            master.setBooleanValue(oldEnabled);
        }
    }
    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
    }

}
