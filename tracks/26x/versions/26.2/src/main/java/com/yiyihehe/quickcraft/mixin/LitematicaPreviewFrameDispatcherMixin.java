package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewFrameBuild;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FeatureRenderDispatcher.class)
public abstract class LitematicaPreviewFrameDispatcherMixin {
    @Redirect(method = "prepareFrameWithContext", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderer;finishPrepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V"))
    private void quickcraft$deferFinish(FeatureRenderer<?> renderer, FeatureFrameContext context) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferFinish(renderer, context)) renderer.finishPrepare(context);
    }

    @Redirect(method = "prepareFrameWithContext", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V"))
    private void quickcraft$deferUpload(StagedVertexBuffer buffer) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferUpload(buffer)) buffer.upload();
    }
}
