package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewRenderTypes;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2 投影实体网格会缓存 PreparedFrame；其中的 DynamicTransforms 切片会在原版 endFrame 后失效。
 * 只在预览回放缓存帧时换成当前帧的新切片，避免关掉网格缓存。
 */
@Mixin(PreparedRenderType.class)
public class LitematicaPreparedRenderTypeMixin {
    @WrapOperation(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
            )
    )
    private void quickcraft$refreshCachedDynamicTransforms(RenderPass pass, String name, GpuBufferSlice value, Operation<Void> original) {
        if ("DynamicTransforms".equals(name) && QuickLitematicaPreview3D.shouldRefreshCachedPreviewDynamicTransforms()) {
            var material = QuickLitematicaPreviewRenderTypes.refreshed((PreparedRenderType) (Object) this);
            value = material != null ? material.dynamicTransforms()
                    : QuickLitematicaPreviewAccess.cachedDynamicTransforms != null
                    ? QuickLitematicaPreviewAccess.cachedDynamicTransforms
                    : RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
        }
        original.call(pass, name, value);
    }
}
