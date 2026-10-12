package com.yiyihehe.quickcraft.litematica;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaProjectionReplacementCompatibilityTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allowsMaterialChangesWithinOrdinaryStructuralFamilies() {
        assertThat(allowed(item("minecraft:oak_stairs").getDefaultInstance(), item("minecraft:stone_brick_stairs").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:jungle_stairs").getDefaultInstance(), item("minecraft:birch_stairs").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:oak_slab").getDefaultInstance(), item("minecraft:smooth_stone_slab").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:white_glazed_terracotta").getDefaultInstance(), item("minecraft:blue_glazed_terracotta").getDefaultInstance())).isTrue();
    }

    @Test
    void rejectsNonBlocksAndStateLoss() {
        assertThat(allowed(item("minecraft:jungle_stairs").getDefaultInstance(), item("minecraft:iron_axe").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:jungle_stairs").getDefaultInstance(), item("minecraft:oak_planks").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:jungle_stairs").getDefaultInstance(), item("minecraft:oak_slab").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:white_glazed_terracotta").getDefaultInstance(), item("minecraft:white_terracotta").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:cut_copper_stairs").getDefaultInstance(), item("minecraft:stone_brick_stairs").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:iron_axe").getDefaultInstance(), item("minecraft:iron_axe").getDefaultInstance())).isFalse();
        assertThat(allowed(ItemStack.EMPTY, item("minecraft:oak_stairs").getDefaultInstance())).isFalse();
    }

    @Test
    void allowsNamesAndLeavesContainerRulesUnrestricted() {
        ItemStack named = item("minecraft:oak_stairs").getDefaultInstance();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("custom"));
        assertThat(allowed(named, item("minecraft:stone_brick_stairs").getDefaultInstance())).isTrue();
        assertThat(allowed(named, named.copy())).isTrue();
        assertThat(QuickLitematicaContainerReplacements.isReplacementAllowed(
                item("minecraft:oak_stairs").getDefaultInstance(), item("minecraft:iron_axe").getDefaultInstance(),
                QuickLitematicaContainerReplacements.Scope.CONTAINER)).isTrue();
    }

    @Test
    void copiesRecordedStatesAndRejectsExtraTargetProperties() {
        var source = block("minecraft:oak_stairs").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
        var target = QuickLitematicaProjectionReplacementCompatibility.replacementState(source, item("minecraft:stone_brick_stairs").getDefaultInstance());
        assertThat(target).isNotNull();
        for (var property : source.getProperties()) { assertThat(target.getValue(property)).isEqualTo(source.getValue(property)); }
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(source, item("minecraft:oak_planks").getDefaultInstance())).isNull();
        assertThat(allowed(item("minecraft:stone").getDefaultInstance(), item("minecraft:oak_stairs").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:stone").getDefaultInstance(), item("minecraft:diamond_chestplate").getDefaultInstance())).isFalse();
    }

    @Test
    void mapsPaletteWithoutMutatingOriginalAndKeepsMergedCells() {
        var original = new fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer(32, 1, 1);
        var states = net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream()
                .map(net.minecraft.world.level.block.Block::defaultBlockState).filter(state -> !state.isAir() && state.getProperties().isEmpty())
                .limit(20).toList();
        for (int index = 0; index < states.size(); index++) { original.set(index, 0, 0, states.get(index)); }
        var rewritten = QuickLitematicaProjectionMaterials.mapStates(original,
                state -> state.isAir() ? state : block("minecraft:glass").defaultBlockState());
        for (int index = 0; index < states.size(); index++) {
            assertThat(original.get(index, 0, 0)).isSameAs(states.get(index));
            assertThat(rewritten.get(index, 0, 0)).isSameAs(block("minecraft:glass").defaultBlockState());
        }
        assertThat(rewritten.get(31, 0, 0).isAir()).isTrue();
        var reloaded = QuickLitematicaDataCompat.copyBlockContainer(rewritten);
        for (int index = 0; index < states.size(); index++) {
            assertThat(reloaded.get(index, 0, 0)).isSameAs(block("minecraft:glass").defaultBlockState());
        }
        assertThat(reloaded.get(31, 0, 0).isAir()).isTrue();
    }

    @Test
    void handStateComponentsCannotChangeOriginalProperties() {
        ItemStack hand = item("minecraft:stone_brick_stairs").getDefaultInstance();
        hand.set(DataComponents.BLOCK_STATE, new net.minecraft.world.item.component.BlockItemStateProperties(java.util.Map.of(
                "half", "top", "waterlogged", "true")));
        var extra = QuickLitematicaProjectionReplacementCompatibility.replacementState(block("minecraft:stone").defaultBlockState(), hand);
        assertThat(extra).isNull();
        var preserved = QuickLitematicaProjectionReplacementCompatibility.replacementState(block("minecraft:oak_stairs").defaultBlockState(), hand);
        assertThat(preserved.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)).isFalse();
        assertThat(preserved.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)).isEqualTo(net.minecraft.world.level.block.state.properties.Half.BOTTOM);
    }

    @Test
    void countsLoadedLitematicDataEvenWhenNativeCountCacheIsEmpty() {
        var source = new fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer(4, 1, 1);
        source.set(0, 0, 0, block("minecraft:stone").defaultBlockState());
        source.set(1, 0, 0, block("minecraft:stone").defaultBlockState());
        source.set(2, 0, 0, block("minecraft:oak_stairs").defaultBlockState());
        var loaded = QuickLitematicaDataCompat.copyBlockContainer(source);
        assertThat(loaded.getBlockCounts()).isEmpty();
        assertThat(countState(loaded, block("minecraft:stone").defaultBlockState())).isEqualTo(2);
        assertThat(countState(loaded, block("minecraft:oak_stairs").defaultBlockState())).isEqualTo(1);
        assertThat(countState(loaded, block("minecraft:air").defaultBlockState())).isEqualTo(1);
        loaded.set(1, 0, 0, block("minecraft:oak_stairs").defaultBlockState());
        assertThat(countState(loaded, block("minecraft:stone").defaultBlockState())).isEqualTo(1);
        assertThat(countState(loaded, block("minecraft:oak_stairs").defaultBlockState())).isEqualTo(2);
    }

    private static long countState(fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container,
                                   net.minecraft.world.level.block.state.BlockState state) {
        long[] counts = QuickLitematicaProjectionMaterials.countPaletteStates(container);
        long result = 0;
        for (int index = 0; index < counts.length; index++) {
            if (container.getPalette().getBlockState(index) == state) { result += counts[index]; }
        }
        return result;
    }

    @Test
    void rejectsGlassToStairsAndIncompatiblePropertyValueDomains() {
        assertThat(allowed(item("minecraft:glass").getDefaultInstance(), item("minecraft:oak_stairs").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:glass").getDefaultInstance(), item("minecraft:stone").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:chest").getDefaultInstance(), item("minecraft:barrel").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:barrel").getDefaultInstance(), item("minecraft:glass").getDefaultInstance())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasSamePropertyDefinition(
                net.minecraft.world.level.block.state.properties.IntegerProperty.create("level", 0, 2),
                net.minecraft.world.level.block.state.properties.IntegerProperty.create("level", 0, 3))).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasSamePropertyDefinition(
                net.minecraft.world.level.block.state.properties.IntegerProperty.create("level", 0, 2),
                net.minecraft.world.level.block.state.properties.IntegerProperty.create("level", 0, 2))).isTrue();
    }

    @Test
    void checksEntityCapabilityEvenForBlocksWithoutStateProperties() {
        assertThat(allowed(item("minecraft:stone").getDefaultInstance(), item("minecraft:spawner").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:spawner").getDefaultInstance(), item("minecraft:enchanting_table").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:chest").getDefaultInstance(), item("minecraft:trapped_chest").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:shulker_box").getDefaultInstance(), item("minecraft:blue_shulker_box").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:oak_sign").getDefaultInstance(), item("minecraft:birch_sign").getDefaultInstance())).isTrue();
    }

    @Test
    void actualInventoryPayloadDoesNotChangeCandidateEligibility() {
        ItemStack empty = item("minecraft:shulker_box").getDefaultInstance();
        ItemStack filled = empty.copy();
        filled.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(
                java.util.List.of(new ItemStack(item("minecraft:stone"), 64))));
        ItemStack target = item("minecraft:blue_shulker_box").getDefaultInstance();
        assertThat(allowed(empty, target)).isTrue();
        assertThat(allowed(filled, target)).isEqualTo(allowed(empty, target));
        assertThat(allowed(empty, item("minecraft:glass").getDefaultInstance())).isFalse();
        assertThat(allowed(filled, item("minecraft:glass").getDefaultInstance())).isFalse();
        ItemStack barrel = item("minecraft:barrel").getDefaultInstance();
        ItemStack filledBarrel = barrel.copy();
        filledBarrel.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(
                java.util.List.of(new ItemStack(item("minecraft:stone"), 64))));
        assertThat(allowed(barrel, item("minecraft:glass").getDefaultInstance())).isFalse();
        assertThat(allowed(filledBarrel, item("minecraft:glass").getDefaultInstance())).isFalse();
        assertThat(allowed(barrel, barrel)).isTrue();
        assertThat(allowed(filledBarrel, barrel)).isTrue();
    }

    @Test
    void fullExteriorIncludesGlassAndSoulSandButNotPathsOrStairs() {
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:glass").defaultBlockState())).isTrue();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:soul_sand").defaultBlockState())).isTrue();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:dirt_path").defaultBlockState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:oak_stairs").defaultBlockState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:oak_slab").defaultBlockState())).isFalse();
        assertThat(allowed(item("minecraft:glass").getDefaultInstance(), item("minecraft:soul_sand").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:glass").getDefaultInstance(), item("minecraft:dirt_path").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:dirt_path").getDefaultInstance(), item("minecraft:glass").getDefaultInstance())).isFalse();
    }

    @Test
    void matchesWallVariantsForSignsBannersAndCoralFans() {
        var sign = block("minecraft:cherry_wall_sign").defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        var replacedSign = QuickLitematicaProjectionReplacementCompatibility.replacementState(sign, item("minecraft:birch_sign").getDefaultInstance());
        assertThat(replacedSign).isNotNull();
        assertThat(replacedSign.getBlock()).isSameAs(block("minecraft:birch_wall_sign"));
        assertThat(replacedSign.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)).isEqualTo(net.minecraft.core.Direction.EAST);
        assertThat(replacedSign.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)).isTrue();
        var banner = block("minecraft:black_wall_banner").defaultBlockState();
        var replacedBanner = QuickLitematicaProjectionReplacementCompatibility.replacementState(banner, item("minecraft:yellow_banner").getDefaultInstance());
        assertThat(replacedBanner).isNotNull();
        assertThat(replacedBanner.getBlock()).isSameAs(block("minecraft:yellow_wall_banner"));
        var coral = block("minecraft:dead_brain_coral_wall_fan").defaultBlockState();
        var replacedCoral = QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, item("minecraft:dead_tube_coral_fan").getDefaultInstance());
        assertThat(replacedCoral).isNotNull();
        assertThat(replacedCoral.getBlock()).isSameAs(block("minecraft:dead_tube_coral_wall_fan"));
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, item("minecraft:ladder").getDefaultInstance())).isNull();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(coral, item("minecraft:tube_coral_fan").getDefaultInstance())).isNull();
    }

    @Test
    void separatesNonFullStructuresWithoutNarrowingFullPillars() {
        assertThat(allowed(item("minecraft:pink_carpet").getDefaultInstance(), item("minecraft:red_carpet").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:pink_carpet").getDefaultInstance(), item("minecraft:poppy").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:poppy").getDefaultInstance(), item("minecraft:pink_carpet").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:poppy").getDefaultInstance(), item("minecraft:dandelion").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:poppy").getDefaultInstance(), item("minecraft:wither_rose").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:cherry_log").getDefaultInstance(), item("minecraft:bone_block").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:cherry_log").getDefaultInstance(), item("minecraft:quartz_pillar").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:oak_stairs").getDefaultInstance(), item("minecraft:cut_copper_stairs").getDefaultInstance())).isTrue();
    }

    @Test
    void attachedGrowthDoesNotBecomeAFullBlockThroughShapeFallback() {
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:glow_lichen").defaultBlockState())).isFalse();
        assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(block("minecraft:vine").defaultBlockState())).isFalse();
        for (var state : block("minecraft:glow_lichen").getStateDefinition().getPossibleStates()) {
            assertThat(QuickLitematicaProjectionReplacementCompatibility.hasFullOutline(state)).isFalse();
            assertThat(QuickLitematicaProjectionReplacementCompatibility.replacementState(state, item("minecraft:sculk_vein").getDefaultInstance())).isNull();
        }
        assertThat(allowed(item("minecraft:glow_lichen").getDefaultInstance(), item("minecraft:sculk_vein").getDefaultInstance())).isFalse();
    }

    @Test
    void groupsConfirmedStructuralSubclassesWithoutGroupingSpecialPlants() {
        var settings = net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(block("minecraft:lantern")).setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("quickcraft_test", "lantern")));
        var lanternVariant = new net.minecraft.world.level.block.LanternBlock(settings) { };
        var chainVariant = new net.minecraft.world.level.block.ChainBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(block("minecraft:iron_chain")).setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("quickcraft_test", "iron_chain")))) { };
        var paneVariant = new net.minecraft.world.level.block.IronBarsBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(block("minecraft:iron_bars")).setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("quickcraft_test", "iron_bars")))) { };
        var rodVariant = new net.minecraft.world.level.block.LightningRodBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.ofFullCopy(block("minecraft:lightning_rod")).setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("quickcraft_test", "lightning_rod")))) { };
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(lanternVariant)).isEqualTo(net.minecraft.world.level.block.LanternBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(chainVariant)).isEqualTo(net.minecraft.world.level.block.ChainBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(paneVariant)).isEqualTo(net.minecraft.world.level.block.IronBarsBlock.class);
        assertThat(QuickLitematicaProjectionReplacementCompatibility.nonFullFamily(rodVariant)).isEqualTo(net.minecraft.world.level.block.LightningRodBlock.class);
        assertThat(allowed(item("minecraft:glass_pane").getDefaultInstance(), item("minecraft:white_stained_glass_pane").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:iron_bars").getDefaultInstance(), item("minecraft:glass_pane").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:lantern").getDefaultInstance(), item("minecraft:soul_lantern").getDefaultInstance())).isTrue();
        assertThat(allowed(item("minecraft:lantern").getDefaultInstance(), item("minecraft:iron_chain").getDefaultInstance())).isFalse();
        assertThat(allowed(item("minecraft:poppy").getDefaultInstance(), item("minecraft:wither_rose").getDefaultInstance())).isFalse();
    }

    private static boolean allowed(ItemStack source, ItemStack target) {
        return QuickLitematicaProjectionReplacementCompatibility.isAllowed(source, target);
    }
    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
    }

}
