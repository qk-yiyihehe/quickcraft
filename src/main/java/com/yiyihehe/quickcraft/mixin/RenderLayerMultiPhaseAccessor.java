package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.6-1.21.8 将 RenderLayer 的 GPU 管线私有化；3D 预览缓存复用静态顶点缓冲时需沿用原版管线。
 */
@Mixin(targets = "net.minecraft.client.render.RenderLayer$MultiPhase")
public interface RenderLayerMultiPhaseAccessor {
    @Accessor("pipeline")
    RenderPipeline quickcraft$getPipeline();

    @Accessor("phases")
    RenderLayer.MultiPhaseParameters quickcraft$getPhases();
}
