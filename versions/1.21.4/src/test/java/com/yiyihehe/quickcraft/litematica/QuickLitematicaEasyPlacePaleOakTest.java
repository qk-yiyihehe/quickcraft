package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEasyPlacePaleOakTest {
    @Test
    void axeInteractsWithPaleOakLogAndWood() {
        ItemStack axe = new ItemStack(Items.WOODEN_AXE);

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceToolInteractionsAllowed).thenReturn(true);

            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.PALE_OAK_LOG, axe)).isTrue();
            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.PALE_OAK_WOOD, axe)).isTrue();
        }
    }

    @Test
    void axeKeepsExistingRulesAndDoesNotTreatStrippedOrOtherBlocksAsInteractions() {
        ItemStack axe = new ItemStack(Items.WOODEN_AXE);

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceToolInteractionsAllowed).thenReturn(true);

            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.OAK_LOG, axe)).isTrue();
            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.STRIPPED_PALE_OAK_LOG, axe))
                    .isFalse();
            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.STONE, axe)).isFalse();
        }
    }

    @Test
    void toolInteractionSwitchStillDisablesPaleOak() {
        ItemStack axe = new ItemStack(Items.WOODEN_AXE);

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceToolInteractionsAllowed).thenReturn(false);

            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.PALE_OAK_LOG, axe)).isFalse();
        }
    }
}
