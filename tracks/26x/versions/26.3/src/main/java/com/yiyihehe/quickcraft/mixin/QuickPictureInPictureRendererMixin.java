package com.yiyihehe.quickcraft.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPictureInPictureRenderPass;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PictureInPictureRenderer.class)
public abstract class QuickPictureInPictureRendererMixin {
    @Redirect(
            method = "prepare",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"
            )
    )
    private void quickcraft$renderPreviewFeatures(RenderPass renderPass,
                                                  FeatureRenderDispatcher.PreparedFrame frame) {
        if ((Object) this instanceof QuickLitematicaPictureInPictureRenderPass extension) {
            extension.quickcraft$renderFeatures(renderPass, frame);
        } else {
            FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
        }
    }
}
