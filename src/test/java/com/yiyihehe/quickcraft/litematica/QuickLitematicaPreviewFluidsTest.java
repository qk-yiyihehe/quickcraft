package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuickLitematicaPreviewFluidsTest {
    @BeforeAll static void bootstrap() {
        System.setProperty("net.bytebuddy.experimental", "true");
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void keepsEveryExposedSideAndMatchesFlowingWaterToStillWater() {
        BlockPos pos = new BlockPos(-7, 19, 32);
        var water = Fluids.WATER.getDefaultState();
        BlockView view = mock(BlockView.class);
        when(view.getFluidState(any(BlockPos.class))).thenReturn(Fluids.FLOWING_WATER.getDefaultState());
        var culling = new QuickLitematicaPreviewFluids();
        assertThat(culling.skipEnclosed(view, pos, water)).isTrue();
        for (Direction exposed : Direction.values()) {
            when(view.getFluidState(any(BlockPos.class))).thenAnswer(call ->
                    pos.offset(exposed).equals(call.getArgument(0)) ? Fluids.EMPTY.getDefaultState() : water);
            assertThat(culling.skipEnclosed(view, pos, water)).isFalse();
        }
        assertThat(culling.examined).isEqualTo(7);
        assertThat(culling.skipped).isEqualTo(1);
        assertThat(culling.custom).isZero();
    }

    @Test void doesNotAssumeSimpleHandlerSubclassesUseDefaultGeometry() {
        var simple = new SimpleFluidRenderHandler(SimpleFluidRenderHandler.WATER_STILL, SimpleFluidRenderHandler.WATER_FLOWING);
        var subclass = new SimpleFluidRenderHandler(SimpleFluidRenderHandler.WATER_STILL, SimpleFluidRenderHandler.WATER_FLOWING) {};
        assertThat(QuickLitematicaPreviewFluids.hasDefaultGeometry(Fluids.WATER, simple)).isTrue();
        assertThat(QuickLitematicaPreviewFluids.hasDefaultGeometry(Fluids.WATER, subclass)).isFalse();
        assertThat(QuickLitematicaPreviewFluids.hasDefaultGeometry(Fluids.EMPTY, null)).isFalse();
    }
}
