package com.yiyihehe.quickcraft.litematica;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuickLitematicaPreviewFluidsTest {
    @BeforeAll static void bootstrap() {
        System.setProperty("net.bytebuddy.experimental", "true");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void keepsEveryExposedSideAndMatchesFlowingWaterToStillWater() {
        BlockPos pos = new BlockPos(-7, 19, 32);
        var water = Fluids.WATER.defaultFluidState();
        BlockGetter view = mock(BlockGetter.class);
        when(view.getFluidState(any(BlockPos.class))).thenReturn(Fluids.FLOWING_WATER.defaultFluidState());
        var culling = new QuickLitematicaPreviewFluids();
        assertThat(culling.skipEnclosed(view, pos, water)).isTrue();
        for (Direction exposed : Direction.values()) {
            when(view.getFluidState(any(BlockPos.class))).thenAnswer(call ->
                    pos.relative(exposed).equals(call.getArgument(0)) ? Fluids.EMPTY.defaultFluidState() : water);
            assertThat(culling.skipEnclosed(view, pos, water)).isFalse();
        }
        assertThat(culling.examined).isEqualTo(7);
        assertThat(culling.skipped).isEqualTo(1);
        assertThat(culling.custom).isZero();
    }
}
