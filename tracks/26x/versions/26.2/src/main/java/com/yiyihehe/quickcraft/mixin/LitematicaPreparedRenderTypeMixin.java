package com.yiyihehe.quickcraft.mixin;

//#if MC>=260300
//$$ import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
//$$ import com.mojang.renderpearl.api.commands.RenderPass;
//#else
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2+ 投影实体网格会缓存 PreparedFrame；其中的 DynamicTransforms 切片会在原版 endFrame 后失效。
 * 只在预览回放缓存帧时换成当前帧的新切片，避免关掉网格缓存。
 */
@Mixin(PreparedRenderType.class)
public class LitematicaPreparedRenderTypeMixin {
    @Redirect(
            //#if MC>=260300
            //$$ method = "draw",
            //#else
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            //#endif
            at = @At(
                    value = "INVOKE",
                    //#if MC>=260300
                    //$$ target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"
                    //#else
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
                    //#endif
            )
    )
    private void quickcraft$refreshCachedDynamicTransforms(RenderPass pass, String name, GpuBufferSlice value) {
        if ("DynamicTransforms".equals(name) && QuickLitematicaPreview3D.shouldRefreshCachedPreviewDynamicTransforms()) {
            value = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
        }
        pass.setUniform(name, value);
    }
}
