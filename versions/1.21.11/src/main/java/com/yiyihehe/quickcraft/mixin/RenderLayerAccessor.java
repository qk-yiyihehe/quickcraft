package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.11 将渲染层的纹理绑定保存在 RenderSetup 中。
 * 预览缓存原来依赖的 RenderSystem 着色器纹理状态已不再对外提供。
 */
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {
    @Accessor("renderSetup")
    RenderSetup quickcraft$getRenderSetup();
}
