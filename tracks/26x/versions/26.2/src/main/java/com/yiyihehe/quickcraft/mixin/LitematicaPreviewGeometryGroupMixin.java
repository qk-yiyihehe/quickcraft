package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewFrameBuild;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class LitematicaPreviewGeometryGroupMixin implements QuickLitematicaPreviewFrameBuild.GeometryGroup {
    @Shadow public abstract VertexConsumer getVertexBuilder(RenderType material);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void quickcraft$captureGroup(StagedVertexBuffer buffer, boolean canReorder, CallbackInfo ci) {
        var build = QuickLitematicaPreviewFrameBuild.current();
        if (build != null) build.createdGroup(buffer, (QuickLitematicaPreviewFrameBuild.GeometryGroup) (Object) this);
    }
}
