package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewRenderTypes;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** RenderPearl 的 pass 内不能再上传缓冲，使用预览在 pass 外准备的当帧变换。 */
@Mixin(PreparedRenderType.class)
public class LitematicaPreparedRenderTypeMixin {
    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"))
    private void quickcraft$refreshCachedDynamicTransforms(RenderPass pass, String name, GpuBufferSlice value, Operation<Void> original) {
        if ("DynamicTransforms".equals(name) && QuickLitematicaPreview3D.shouldRefreshCachedPreviewDynamicTransforms()
                && QuickLitematicaPreviewAccess.cachedDynamicTransforms != null) {
            var material = QuickLitematicaPreviewRenderTypes.refreshed((PreparedRenderType) (Object) this);
            value = material != null ? material.dynamicTransforms() : QuickLitematicaPreviewAccess.cachedDynamicTransforms;
        }
        original.call(pass, name, value);
    }
}
