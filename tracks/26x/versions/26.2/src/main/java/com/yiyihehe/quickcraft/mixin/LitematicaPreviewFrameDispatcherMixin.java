package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewFrameBuild;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(FeatureRenderDispatcher.class)
public abstract class LitematicaPreviewFrameDispatcherMixin {
    @WrapOperation(method = "prepareFrameWithContext", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderer;finishPrepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V"))
    private void quickcraft$deferFinish(FeatureRenderer<?> renderer, FeatureFrameContext context, Operation<Void> original) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferFinish(renderer, context)) original.call(renderer, context);
    }

    @WrapOperation(method = "prepareFrameWithContext", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V"))
    private void quickcraft$deferUpload(StagedVertexBuffer buffer, Operation<Void> original) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferUpload(buffer)) original.call(buffer);
    }
}
