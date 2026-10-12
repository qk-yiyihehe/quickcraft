package com.yiyihehe.quickcraft.litematica;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaProjectionReplacementCompatibilityTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void allowsMaterialChangesWithinOrdinaryStructuralFamilies() {
        assertThat(allowed(Items.OAK_STAIRS.getDefaultStack(), Items.STONE_BRICK_STAIRS.getDefaultStack())).isTrue();
        assertThat(allowed(Items.JUNGLE_STAIRS.getDefaultStack(), Items.BIRCH_STAIRS.getDefaultStack())).isTrue();
        assertThat(allowed(Items.OAK_SLAB.getDefaultStack(), Items.SMOOTH_STONE_SLAB.getDefaultStack())).isTrue();
        assertThat(allowed(Items.WHITE_GLAZED_TERRACOTTA.getDefaultStack(), Items.BLUE_GLAZED_TERRACOTTA.getDefaultStack())).isTrue();
    }

    @Test
    void rejectsNonBlocksAndStateLoss() {
        assertThat(allowed(Items.JUNGLE_STAIRS.getDefaultStack(), Items.IRON_AXE.getDefaultStack())).isFalse();
        assertThat(allowed(Items.JUNGLE_STAIRS.getDefaultStack(), Items.OAK_PLANKS.getDefaultStack())).isFalse();
        assertThat(allowed(Items.JUNGLE_STAIRS.getDefaultStack(), Items.OAK_SLAB.getDefaultStack())).isFalse();
        assertThat(allowed(Items.WHITE_GLAZED_TERRACOTTA.getDefaultStack(), Items.WHITE_TERRACOTTA.getDefaultStack())).isFalse();
        assertThat(allowed(Items.CUT_COPPER_STAIRS.getDefaultStack(), Items.STONE_BRICK_STAIRS.getDefaultStack())).isTrue();
        assertThat(allowed(Items.IRON_AXE.getDefaultStack(), Items.IRON_AXE.getDefaultStack())).isFalse();
        assertThat(allowed(ItemStack.EMPTY, Items.OAK_STAIRS.getDefaultStack())).isFalse();
    }

    @Test
    void allowsNamesAndLeavesContainerRulesUnrestricted() {
        ItemStack named = Items.OAK_STAIRS.getDefaultStack();
        named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("custom"));
        assertThat(allowed(named, Items.STONE_BRICK_STAIRS.getDefaultStack())).isTrue();
        assertThat(allowed(named, named.copy())).isTrue();
        assertThat(QuickLitematicaContainerReplacements.isReplacementAllowed(
                Items.OAK_STAIRS.getDefaultStack(), Items.IRON_AXE.getDefaultStack(),
                QuickLitematicaContainerReplacements.Scope.CONTAINER)).isTrue();
    }

    @Test
    void copiesRecordedStatesAndRejectsExtraTargetProperties() {
        var source = net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState()
                .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.WEST)
                .with(net.minecraft.state.property.Properties.WATERLOGGED, true)
                .with(net.minecraft.state.property.Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.TOP);
        var target = QuickLitematicaProjectionReplacementCompatibility.replacementState(source, Items.STONE_BRICK_STAIRS.getDefaultStack());
        assertThat(target).isNotNull();
        for (var entry : source.getEntries().entrySet()) { assertThat(target.getEntries().get(entry.getKey())).isEqualTo(entry.getValue()); }
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(source, Items.OAK_PLANKS.getDefaultStack())).isNull();
        assertThat(allowed(Items.STONE.getDefaultStack(), Items.OAK_STAIRS.getDefaultStack())).isFalse();
        assertThat(allowed(Items.STONE.getDefaultStack(), Items.DIAMOND_CHESTPLATE.getDefaultStack())).isFalse();
    }

    @Test
    void mapsPaletteWithoutMutatingOriginalAndKeepsMergedCells() {
        var original = new fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer(32, 1, 1);
        var states = net.minecraft.registry.Registries.BLOCK.stream()
                .map(net.minecraft.block.Block::getDefaultState).filter(state -> !state.isAir() && state.getProperties().isEmpty())
                .limit(20).toList();
        for (int index = 0; index < states.size(); index++) { original.set(index, 0, 0, states.get(index)); }
        var rewritten = QuickLitematicaProjectionMaterials.mapStates(original,
                state -> state.isAir() ? state : net.minecraft.block.Blocks.GLASS.getDefaultState());
        for (int index = 0; index < states.size(); index++) {
            assertThat(original.get(index, 0, 0)).isSameAs(states.get(index));
            assertThat(rewritten.get(index, 0, 0)).isSameAs(net.minecraft.block.Blocks.GLASS.getDefaultState());
        }
        assertThat(rewritten.get(31, 0, 0).isAir()).isTrue();
        var reloaded = fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer.createFrom(
                rewritten.getPalette().writeToNBT(), rewritten.getBackingLongArray().clone(), new net.minecraft.util.math.BlockPos(rewritten.getSize()));
        for (int index = 0; index < states.size(); index++) {
            assertThat(reloaded.get(index, 0, 0)).isSameAs(net.minecraft.block.Blocks.GLASS.getDefaultState());
        }
        assertThat(reloaded.get(31, 0, 0).isAir()).isTrue();
    }

    @Test
    void handStateComponentsCannotChangeOriginalProperties() {
        ItemStack hand = Items.STONE_BRICK_STAIRS.getDefaultStack();
        hand.set(DataComponentTypes.BLOCK_STATE, new net.minecraft.component.type.BlockStateComponent(java.util.Map.of(
                "half", "top", "waterlogged", "true")));
        var extra = QuickLitematicaProjectionReplacementCompatibility.replacementState(net.minecraft.block.Blocks.STONE.getDefaultState(), hand);
        assertThat(extra).isNull();
        var preserved = QuickLitematicaProjectionReplacementCompatibility.replacementState(net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState(), hand);
        assertThat(preserved.get(net.minecraft.state.property.Properties.WATERLOGGED)).isFalse();
        assertThat(preserved.get(net.minecraft.state.property.Properties.BLOCK_HALF)).isEqualTo(net.minecraft.block.enums.BlockHalf.BOTTOM);
    }

    @Test
    void countsLoadedLitematicDataEvenWhenNativeCountCacheIsEmpty() {
        var source = new fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer(4, 1, 1);
        source.set(0, 0, 0, net.minecraft.block.Blocks.STONE.getDefaultState());
        source.set(1, 0, 0, net.minecraft.block.Blocks.STONE.getDefaultState());
        source.set(2, 0, 0, net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState());
        var loaded = fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer.createFrom(
                source.getPalette().writeToNBT(), source.getBackingLongArray().clone(), new net.minecraft.util.math.BlockPos(source.getSize()));
        assertThat(loaded.getBlockCounts()).isEmpty();
        assertThat(countState(loaded, net.minecraft.block.Blocks.STONE.getDefaultState())).isEqualTo(2);
        assertThat(countState(loaded, net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState())).isEqualTo(1);
        assertThat(countState(loaded, net.minecraft.block.Blocks.AIR.getDefaultState())).isEqualTo(1);
        loaded.set(1, 0, 0, net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState());
        assertThat(countState(loaded, net.minecraft.block.Blocks.STONE.getDefaultState())).isEqualTo(1);
        assertThat(countState(loaded, net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState())).isEqualTo(2);
    }

    private static long countState(fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container,
                                   net.minecraft.block.BlockState state) {
        long[] counts = QuickLitematicaProjectionMaterials.countPaletteStates(container);
        long result = 0;
        for (int index = 0; index < counts.length; index++) {
            if (container.getPalette().getBlockState(index) == state) { result += counts[index]; }
        }
        return result;
    }

    @Test
    void rejectsGlassToStairsAndIncompatiblePropertyValueDomains() {
        assertThat(allowed(Items.GLASS.getDefaultStack(), Items.OAK_STAIRS.getDefaultStack())).isFalse();
        assertThat(allowed(Items.GLASS.getDefaultStack(), Items.STONE.getDefaultStack())).isTrue();
        assertThat(allowed(Items.CHEST.getDefaultStack(), Items.BARREL.getDefaultStack())).isFalse();
        assertThat(allowed(Items.BARREL.getDefaultStack(), Items.GLASS.getDefaultStack())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasSamePropertyDefinition(
                net.minecraft.state.property.IntProperty.of("level", 0, 2),
                net.minecraft.state.property.IntProperty.of("level", 0, 3))).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasSamePropertyDefinition(
                net.minecraft.state.property.IntProperty.of("level", 0, 2),
                net.minecraft.state.property.IntProperty.of("level", 0, 2))).isTrue();
    }

    @Test
    void checksEntityCapabilityEvenForBlocksWithoutStateProperties() {
        assertThat(allowed(Items.STONE.getDefaultStack(), Items.SPAWNER.getDefaultStack())).isFalse();
        assertThat(allowed(Items.SPAWNER.getDefaultStack(), Items.ENCHANTING_TABLE.getDefaultStack())).isFalse();
        assertThat(allowed(Items.CHEST.getDefaultStack(), Items.TRAPPED_CHEST.getDefaultStack())).isTrue();
        assertThat(allowed(Items.SHULKER_BOX.getDefaultStack(), Items.BLUE_SHULKER_BOX.getDefaultStack())).isTrue();
        assertThat(allowed(Items.OAK_SIGN.getDefaultStack(), Items.BIRCH_SIGN.getDefaultStack())).isTrue();
    }

    @Test
    void actualInventoryPayloadDoesNotChangeCandidateEligibility() {
        ItemStack empty = Items.SHULKER_BOX.getDefaultStack();
        ItemStack filled = empty.copy();
        filled.set(DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(
                java.util.List.of(new ItemStack(Items.STONE, 64))));
        ItemStack target = Items.BLUE_SHULKER_BOX.getDefaultStack();
        assertThat(allowed(empty, target)).isTrue();
        assertThat(allowed(filled, target)).isEqualTo(allowed(empty, target));
        assertThat(allowed(empty, Items.GLASS.getDefaultStack())).isFalse();
        assertThat(allowed(filled, Items.GLASS.getDefaultStack())).isFalse();
        ItemStack barrel = Items.BARREL.getDefaultStack();
        ItemStack filledBarrel = barrel.copy();
        filledBarrel.set(DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(
                java.util.List.of(new ItemStack(Items.STONE, 64))));
        assertThat(allowed(barrel, Items.GLASS.getDefaultStack())).isFalse();
        assertThat(allowed(filledBarrel, Items.GLASS.getDefaultStack())).isFalse();
        assertThat(allowed(barrel, barrel)).isTrue();
        assertThat(allowed(filledBarrel, barrel)).isTrue();
    }

    @Test
    void fullExteriorIncludesGlassAndSoulSandButNotPathsOrStairs() {
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.GLASS.getDefaultState())).isTrue();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.SOUL_SAND.getDefaultState())).isTrue();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.DIRT_PATH.getDefaultState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.OAK_SLAB.getDefaultState())).isFalse();
        assertThat(allowed(Items.GLASS.getDefaultStack(), Items.SOUL_SAND.getDefaultStack())).isTrue();
        assertThat(allowed(Items.GLASS.getDefaultStack(), Items.DIRT_PATH.getDefaultStack())).isFalse();
        assertThat(allowed(Items.DIRT_PATH.getDefaultStack(), Items.GLASS.getDefaultStack())).isFalse();
    }

    @Test
    void matchesWallVariantsForSignsBannersAndCoralFans() {
        var sign = net.minecraft.block.Blocks.CHERRY_WALL_SIGN.getDefaultState()
                .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.EAST)
                .with(net.minecraft.state.property.Properties.WATERLOGGED, true);
        var replacedSign = QuickLitematicaProjectionReplacementCompatibility.replacementState(sign, Items.BIRCH_SIGN.getDefaultStack());
        assertThat(replacedSign).isNotNull();
        assertThat(replacedSign.getBlock()).isSameAs(net.minecraft.block.Blocks.BIRCH_WALL_SIGN);
        assertThat(replacedSign.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)).isEqualTo(net.minecraft.util.math.Direction.EAST);
        assertThat(replacedSign.get(net.minecraft.state.property.Properties.WATERLOGGED)).isTrue();
        var banner = net.minecraft.block.Blocks.BLACK_WALL_BANNER.getDefaultState();
        var replacedBanner = QuickLitematicaProjectionReplacementCompatibility.replacementState(banner, Items.YELLOW_BANNER.getDefaultStack());
        assertThat(replacedBanner).isNotNull();
        assertThat(replacedBanner.getBlock()).isSameAs(net.minecraft.block.Blocks.YELLOW_WALL_BANNER);
        var coral = net.minecraft.block.Blocks.DEAD_BRAIN_CORAL_WALL_FAN.getDefaultState();
        var replacedCoral = QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, Items.DEAD_TUBE_CORAL_FAN.getDefaultStack());
        assertThat(replacedCoral).isNotNull();
        assertThat(replacedCoral.getBlock()).isSameAs(net.minecraft.block.Blocks.DEAD_TUBE_CORAL_WALL_FAN);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, Items.LADDER.getDefaultStack())).isNull();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, Items.TUBE_CORAL_FAN.getDefaultStack())).isNull();
    }

    @Test
    void separatesNonFullStructuresWithoutNarrowingFullPillars() {
        assertThat(allowed(Items.PINK_CARPET.getDefaultStack(), Items.RED_CARPET.getDefaultStack())).isTrue();
        assertThat(allowed(Items.PINK_CARPET.getDefaultStack(), Items.POPPY.getDefaultStack())).isFalse();
        assertThat(allowed(Items.POPPY.getDefaultStack(), Items.PINK_CARPET.getDefaultStack())).isFalse();
        assertThat(allowed(Items.POPPY.getDefaultStack(), Items.DANDELION.getDefaultStack())).isTrue();
        assertThat(allowed(Items.POPPY.getDefaultStack(), Items.WITHER_ROSE.getDefaultStack())).isFalse();
        assertThat(allowed(Items.CHERRY_LOG.getDefaultStack(), Items.BONE_BLOCK.getDefaultStack())).isTrue();
        assertThat(allowed(Items.CHERRY_LOG.getDefaultStack(), Items.QUARTZ_PILLAR.getDefaultStack())).isTrue();
        assertThat(allowed(Items.OAK_STAIRS.getDefaultStack(), Items.CUT_COPPER_STAIRS.getDefaultStack())).isTrue();
    }

    @Test
    void attachedGrowthDoesNotBecomeAFullBlockThroughShapeFallback() {
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.GLOW_LICHEN.getDefaultState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(net.minecraft.block.Blocks.VINE.getDefaultState())).isFalse();
        for (var state : net.minecraft.block.Blocks.GLOW_LICHEN.getStateManager().getStates()) {
            assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(state)).isFalse();
            assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(state, Items.SCULK_VEIN.getDefaultStack())).isNull();
        }
        assertThat(allowed(Items.GLOW_LICHEN.getDefaultStack(), Items.SCULK_VEIN.getDefaultStack())).isFalse();
    }

    @Test
    void groupsConfirmedStructuralSubclassesWithoutGroupingSpecialPlants() {
        var settings = com.yiyihehe.quickcraft.QuickCraftTestBlocks.settings(net.minecraft.block.Blocks.LANTERN);
        var lanternVariant = new net.minecraft.block.LanternBlock(settings) { };
        var chainVariant = new net.minecraft.block.ChainBlock(com.yiyihehe.quickcraft.QuickCraftTestBlocks.settings(net.minecraft.block.Blocks.CHAIN)) { };
        var paneVariant = new net.minecraft.block.PaneBlock(com.yiyihehe.quickcraft.QuickCraftTestBlocks.settings(net.minecraft.block.Blocks.IRON_BARS)) { };
        var rodVariant = new net.minecraft.block.LightningRodBlock(com.yiyihehe.quickcraft.QuickCraftTestBlocks.settings(net.minecraft.block.Blocks.LIGHTNING_ROD)) { };
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(lanternVariant)).isEqualTo(net.minecraft.block.LanternBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(chainVariant)).isEqualTo(net.minecraft.block.ChainBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(paneVariant)).isEqualTo(net.minecraft.block.PaneBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(rodVariant)).isEqualTo(net.minecraft.block.LightningRodBlock.class);
        assertThat(allowed(Items.GLASS_PANE.getDefaultStack(), Items.WHITE_STAINED_GLASS_PANE.getDefaultStack())).isTrue();
        assertThat(allowed(Items.IRON_BARS.getDefaultStack(), Items.GLASS_PANE.getDefaultStack())).isTrue();
        assertThat(allowed(Items.LANTERN.getDefaultStack(), Items.SOUL_LANTERN.getDefaultStack())).isTrue();
        assertThat(allowed(Items.LANTERN.getDefaultStack(), Items.CHAIN.getDefaultStack())).isFalse();
        assertThat(allowed(Items.POPPY.getDefaultStack(), Items.WITHER_ROSE.getDefaultStack())).isFalse();
    }

    private static boolean allowed(ItemStack source, ItemStack target) {
        return QuickLitematicaProjectionReplacementCompatibility.isAllowed(source, target);
    }
}
