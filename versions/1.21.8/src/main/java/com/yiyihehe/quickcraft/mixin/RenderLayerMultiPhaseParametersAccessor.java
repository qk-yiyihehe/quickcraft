package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 MultiPhaseParameters 选择的目标帧缓冲，保证缓存网格与原版 RenderLayer 落在同一渲染目标。
 */
@Mixin(RenderLayer.MultiPhaseParameters.class)
public interface RenderLayerMultiPhaseParametersAccessor {
    @Accessor("target")
    RenderPhase.Target quickcraft$getTarget();
}
