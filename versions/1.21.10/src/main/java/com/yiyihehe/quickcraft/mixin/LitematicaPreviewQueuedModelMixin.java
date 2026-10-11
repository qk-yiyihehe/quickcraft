package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.SharedConstants;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelCommandRenderer.class)
public abstract class LitematicaPreviewQueuedModelMixin {
    @Invoker("render")
    public abstract void quickcraft$renderModel(OrderedRenderCommandQueueImpl.ModelCommand<?> model, RenderLayer layer,
            VertexConsumer consumer, OutlineVertexConsumerProvider outline, VertexConsumerProvider.Immediate crumbling);

    @Inject(method = "renderAll", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferModels(VertexConsumerProvider.Immediate buffers, OutlineVertexConsumerProvider outline,
            Map<RenderLayer, List<OrderedRenderCommandQueueImpl.ModelCommand<?>>> models, VertexConsumerProvider.Immediate crumbling, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        Iterable<Map.Entry<RenderLayer, List<OrderedRenderCommandQueueImpl.ModelCommand<?>>>> entries = models.entrySet();
        if (SharedConstants.SHUFFLE_MODELS) {
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

    @Inject(method = "renderAllBlended", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferBlended(VertexConsumerProvider.Immediate buffers, OutlineVertexConsumerProvider outline,
            List<OrderedRenderCommandQueueImpl.BlendedModelCommand<?>> models, VertexConsumerProvider.Immediate crumbling, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(buffers)) return;
        if (QuickLitematicaPreviewQueuedBuild.deferList(buffers, models, model -> this.quickcraft$renderModel(
                model.model(), model.renderType(), buffers.getBuffer(model.renderType()), outline, crumbling))) ci.cancel();
    }
}
