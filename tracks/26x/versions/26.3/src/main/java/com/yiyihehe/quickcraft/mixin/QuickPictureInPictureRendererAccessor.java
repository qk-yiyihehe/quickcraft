package com.yiyihehe.quickcraft.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.3 不再暴露全局输出目标，预览借用 PIP 纹理自行完成绘制。 */
@Mixin(PictureInPictureRenderer.class)
public interface QuickPictureInPictureRendererAccessor {
    @Accessor("textureView")
    GpuTextureView quickcraft$getTextureView();

    @Accessor("depthTextureView")
    GpuTextureView quickcraft$getDepthTextureView();
}
