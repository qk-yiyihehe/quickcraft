package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewFrameBuild;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class LitematicaPreviewGeometryRendererMixin implements QuickLitematicaPreviewFrameBuild.GeometryRenderer {
    @Invoker("buildGroup")
    public abstract void quickcraft$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits);

    @Redirect(method = "prepareGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer;buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V"))
    private void quickcraft$deferBuild(RenderTypeFeatureRenderer<?> renderer, FeatureFrameContext context, List<? extends SubmitNode> submits) {
        var bridge = (QuickLitematicaPreviewFrameBuild.GeometryRenderer) (Object) renderer;
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferBuild(bridge, context)) bridge.quickcraft$buildGroup(context, submits);
    }

    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void quickcraft$resumeGroup(RenderType material, CallbackInfoReturnable<VertexConsumer> cir) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null) return;
        var consumer = build.vertexBuilder(this, material);
        if (consumer != null) cir.setReturnValue(consumer);
    }
}
