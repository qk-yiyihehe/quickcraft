package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderingRegistryImpl;
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
        // 显式 handler 或自定义 FluidModel 都可能绘制内部几何；get() 的默认处理器不能阻断普通流体剔除。
        if (FluidRenderingRegistry.getOverride(fluid) != null
                || FluidRenderingRegistryImpl.getUnbakedModels().containsKey(fluid)) {
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
