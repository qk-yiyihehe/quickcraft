package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewFrameBuild;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedGroup")
public abstract class LitematicaPreviewPreparedGroupMixin {
    @Redirect(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderer;prepareGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;Z)V"))
    private void quickcraft$defer(FeatureRenderer<SubmitNode> renderer, FeatureFrameContext context, List<SubmitNode> submits, boolean strict) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build == null || !build.deferGroup(renderer, context, submits, strict)) renderer.prepareGroup(context, submits, strict);
    }
}
