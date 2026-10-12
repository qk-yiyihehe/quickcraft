package com.yiyihehe.quickcraft.litematica;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaGlobalReplacementsTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void restoresStackLimitRatiosAndUnstackableTargets() {
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(Items.SHEARS),
                new ItemStack(Items.OAK_PLANKS)).getCount()).isEqualTo(64);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.SHEARS)).getCount()).isEqualTo(1);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(Items.OAK_PLANKS, 16),
                new ItemStack(Items.ENDER_PEARL)).getCount()).isEqualTo(4);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(Items.ENDER_PEARL, 4),
                new ItemStack(Items.OAK_PLANKS)).getCount()).isEqualTo(16);
        assertThat(QuickLitematicaGlobalReplacements.proportionalTarget(new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.ENDER_PEARL)).getCount()).isEqualTo(1);
    }

    @Test
    void manualOptOutWinsAndGlobalsNeverChain() {
        var manualConfig = QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS;
        var globalConfig = QuickCraftConfigs.ProjectionTools.GLOBAL_CONTAINER_REPLACEMENTS;
        var master = QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_MATERIAL_REPLACEMENTS;
        var oldManual = List.copyOf(manualConfig.getStrings());
        var oldGlobals = List.copyOf(globalConfig.getStrings());
        boolean oldEnabled = master.getBooleanValue();
        var lookup = DynamicRegistryManager.of(Registries.REGISTRIES);
        try {
            master.setBooleanValue(true);
            manualConfig.setStrings(List.of(QuickLitematicaContainerReplacements.encodeContainerRule(
                    new QuickLitematicaContainerReplacements.ContainerReplacement(new ItemStack(Items.STONE),
                            new ItemStack(Items.STONE, 17), "test", "group", 0, false), lookup)));
            globalConfig.setStrings(List.of("minecraft:stone->minecraft:glass", "minecraft:glass->minecraft:dirt"));
            var slots = List.of(new ItemStack(Items.STONE, 17), new ItemStack(Items.STONE, 32));
            var result = QuickLitematicaGlobalReplacements.containerSlots(slots, "test", "group");
            assertThat(result.get(0).getItem()).isSameAs(Items.STONE);
            assertThat(result.get(1).getItem()).isSameAs(Items.GLASS);
            assertThat(result.get(1).getCount()).isEqualTo(32);
            assertThat(slots.get(1).getItem()).isSameAs(Items.STONE);
            master.setBooleanValue(false);
            var disabled = QuickLitematicaGlobalReplacements.containerSlots(slots, "test", "group");
            assertThat(disabled.get(1).getItem()).isSameAs(Items.STONE);
            assertThat(globalConfig.getStrings()).hasSize(2);
        } finally {
            manualConfig.setStrings(oldManual);
            globalConfig.setStrings(oldGlobals);
            master.setBooleanValue(oldEnabled);
        }
    }
}
