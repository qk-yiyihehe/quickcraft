package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEasyPlacePaleOakTest {
    @Test
    void axeInteractsWithPaleOakLogAndWood() {
        ItemStack axe = axeStack();

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceToolInteractionsAllowed).thenReturn(true);

            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.PALE_OAK_LOG, axe)).isTrue();
            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.PALE_OAK_WOOD, axe)).isTrue();
        }
    }

    @Test
    void axeKeepsExistingRulesAndDoesNotTreatStrippedOrOtherBlocksAsInteractions() {
        ItemStack axe = axeStack();

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceToolInteractionsAllowed).thenReturn(true);

            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.OAK_LOG, axe)).isTrue();
            assertThat(QuickLitematicaEasyPlaceInteractions.isToolInteraction(Blocks.STRIPPED_PALE_OAK_LOG, axe)).isFalse();
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

    private static ItemStack axeStack() {
        ItemStack realAxe = new ItemStack(Items.WOODEN_AXE);
        if (QuickLitematicaEasyPlaceAccess.isAxe(realAxe)) {
            return realAxe;
        }

        // 26.3 的适配按原版 AXES 数据标签判断；单元测试没有数据包绑定，只提供该分支的标签结果。
        ItemStack taggedAxe = mock(ItemStack.class);
        when(taggedAxe.is(ItemTags.AXES)).thenReturn(true);
        return taggedAxe;
    }
}
