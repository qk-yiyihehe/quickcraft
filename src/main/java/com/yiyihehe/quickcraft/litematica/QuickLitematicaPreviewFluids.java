package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

final class QuickLitematicaPreviewFluids {
    private static final Direction[] SIDES = Direction.values();
    private final BlockPos.Mutable neighbor = new BlockPos.Mutable();
    long examined, skipped, custom;

    boolean skipEnclosed(BlockView view, BlockPos pos, FluidState state) {
        this.examined++;
        Fluid fluid = state.getFluid();
        var handler = FluidRenderHandlerRegistry.INSTANCE.getOverride(fluid);
        if (!hasDefaultGeometry(fluid, handler)) {
            this.custom++;
            return false;
        }
        for (Direction side : SIDES) {
            this.neighbor.set(pos, side);
            // 与原生 isSameFluid 保持相同调用方向，包含静止/流动流体配对。
            if (!view.getFluidState(this.neighbor).getFluid().matchesType(fluid)) return false;
        }
        this.skipped++;
        return true;
    }

    static boolean hasDefaultGeometry(Fluid fluid, FluidRenderHandler override) {
        // 自定义处理器可能绘制内部几何；Simple 的子类也可能重写 renderFluid。
        if (override != null) return override.getClass() == SimpleFluidRenderHandler.class;
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER
                || fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }
}
