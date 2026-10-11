package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.class)
public abstract class LitematicaPreviewQueuedItemMixin {
    @Invoker("hasTranslucency")
    public static boolean quickcraft$hasTranslucency(SubmitNodeStorage.ItemSubmit submit) {
        throw new UnsupportedOperationException();
    }

    @Invoker("renderItem")
    public abstract void quickcraft$renderItem(MultiBufferSource.BufferSource buffers, OutlineBufferSource outline,
            SubmitNodeStorage.ItemSubmit submit);

    @Inject(method = "renderSolid", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferSolid(SubmitNodeCollection collection, MultiBufferSource.BufferSource buffers,
            OutlineBufferSource outline, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        if (QuickLitematicaPreviewQueuedBuild.deferList(buffers, collection.getItemSubmits(), submit -> {
            if (!quickcraft$hasTranslucency(submit)) this.quickcraft$renderItem(buffers, outline, submit);
        })) ci.cancel();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferTranslucent(SubmitNodeCollection collection, MultiBufferSource.BufferSource buffers,
            OutlineBufferSource outline, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        if (QuickLitematicaPreviewQueuedBuild.deferList(buffers, collection.getItemSubmits(), submit -> {
            if (quickcraft$hasTranslucency(submit)) this.quickcraft$renderItem(buffers, outline, submit);
        })) ci.cancel();
    }
}
