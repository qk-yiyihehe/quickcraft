package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.1 将渲染管线和解析后的纹理绑定保存在 RenderType.state 中。
 * 绘制缓存中的 VBO 时，预览渲染器需要复用完全相同的设置。
 */
@Mixin(RenderType.class)
public interface RenderLayerAccessor {
    @Accessor("state")
    RenderSetup quickcraft$getRenderSetup();
}
