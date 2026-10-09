package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;

final class QuickLitematicaPreviewFluids {
    private static final Direction[] SIDES = Direction.values();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    long examined, skipped, custom;

    boolean skipEnclosed(BlockGetter view, BlockPos pos, FluidState state) {
        this.examined++;
        var fluid = state.getType();
        // 注册处理器可以绘制内部几何；有处理器时保留完整原生调用。
        if (FluidRenderingRegistry.get(fluid) != null) {
            this.custom++;
            return false;
        }
        for (Direction side : SIDES) {
            this.neighbor.setWithOffset(pos, side);
            if (!view.getFluidState(this.neighbor).getType().isSame(fluid)) return false;
        }
        this.skipped++;
        return true;
    }
}
