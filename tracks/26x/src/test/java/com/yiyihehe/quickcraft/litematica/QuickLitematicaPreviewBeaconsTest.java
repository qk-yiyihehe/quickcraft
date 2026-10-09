package com.yiyihehe.quickcraft.litematica;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuickLitematicaPreviewBeaconsTest {
    @BeforeAll
    static void bootstrap() {
        // 仓库的 Mockito/Byte Buddy 尚未声明 Java 25，测试只模拟世界读取接口。
        System.setProperty("net.bytebuddy.experimental", "true");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void buildsColoredBeamAndHonorsObstructionAndMissingBase() {
        BlockPos pos = new BlockPos(0, 8, 0);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(pos, Blocks.BEACON.defaultBlockState());
        BlockState base = mock(BlockState.class);
        when(base.is(BlockTags.BEACON_BASE_BLOCKS)).thenReturn(true);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) states.put(new BlockPos(x, 7, z), base);
        }
        states.put(new BlockPos(0, 32, 0), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "red_stained_glass")).defaultBlockState());
        states.put(new BlockPos(0, 40, 0), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "blue_stained_glass")).defaultBlockState());
        Level world = mock(Level.class);
        when(world.isClientSide()).thenReturn(true);
        when(world.getMinY()).thenReturn(-64);
        when(world.getHeight(any(Heightmap.Types.class), anyInt(), anyInt()))
                .thenAnswer(call -> QuickLitematicaPreviewBeacons.initializationTop());
        when(world.getBlockState(any(BlockPos.class)))
                .thenAnswer(call -> states.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));

        BeaconBlockEntity active = initialize(world, pos, states);
        assertThat(active.getBeamSections()).hasSize(3);
        assertThat(active.getBeamSections().get(1).getHeight()).isEqualTo(8);
        assertThat(active.getBeamSections().get(1).getColor())
                .isNotEqualTo(active.getBeamSections().get(2).getColor());
        assertThat(QuickLitematicaPreviewBeacons.isInitializing()).isFalse();

        states.put(new BlockPos(0, 48, 0), Blocks.STONE.defaultBlockState());
        assertThat(initialize(world, pos, states).getBeamSections()).isEmpty();
        states.remove(new BlockPos(0, 48, 0));
        states.remove(new BlockPos(1, 7, 1));
        assertThat(initialize(world, pos, states).getBeamSections()).isEmpty();
    }

    private static BeaconBlockEntity initialize(Level world, BlockPos pos, Map<BlockPos, BlockState> states) {
        BeaconBlockEntity beacon = new BeaconBlockEntity(pos, states.get(pos));
        beacon.setLevel(world);
        Map<BlockPos, BlockEntity> entities = Map.of(pos, beacon);
        QuickLitematicaPreviewBeacons.initialize(world, states, entities);
        return beacon;
    }
}
