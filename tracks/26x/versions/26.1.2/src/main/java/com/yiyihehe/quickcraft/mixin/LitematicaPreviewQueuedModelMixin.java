package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.SharedConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
public abstract class LitematicaPreviewQueuedModelMixin {
    @Invoker("renderModel")
    public abstract void quickcraft$renderModel(SubmitNodeStorage.ModelSubmit<?> model, RenderType layer,
            VertexConsumer consumer, OutlineBufferSource outline, MultiBufferSource.BufferSource crumbling);

    @Inject(method = "renderBatch", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferModels(MultiBufferSource.BufferSource buffers, OutlineBufferSource outline,
            Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> models, MultiBufferSource.BufferSource crumbling, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        Iterable<Map.Entry<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>>> entries = models.entrySet();
        if (SharedConstants.DEBUG_SHUFFLE_MODELS) {
            var shuffled = new ArrayList<>(models.entrySet());
            Collections.shuffle(shuffled);
            entries = shuffled;
        }
        QuickLitematicaPreviewQueuedBuild.deferEntries(buffers, entries, layer -> {
            VertexConsumer consumer = buffers.getBuffer(layer);
            return model -> this.quickcraft$renderModel(model, layer, consumer, outline, crumbling);
        });
        ci.cancel();
    }

    @Inject(method = "renderTranslucents", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferBlended(MultiBufferSource.BufferSource buffers, OutlineBufferSource outline,
            List<SubmitNodeStorage.TranslucentModelSubmit<?>> models, MultiBufferSource.BufferSource crumbling, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        if (QuickLitematicaPreviewQueuedBuild.deferList(buffers, models, model -> this.quickcraft$renderModel(
                model.modelSubmit(), model.renderType(), buffers.getBuffer(model.renderType()), outline, crumbling))) ci.cancel();
    }
}
