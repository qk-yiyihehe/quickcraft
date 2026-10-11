package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import java.util.List;
import java.util.Map;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.ModelBaker;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.command.ModelPartCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPartCommandRenderer.class)
public abstract class LitematicaPreviewQueuedPartMixin {
    @Shadow @Final private MatrixStack matrices;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferParts(BatchingRenderCommandQueue queue, VertexConsumerProvider.Immediate vertexConsumers,
            OutlineVertexConsumerProvider outlineVertexConsumerProvider, VertexConsumerProvider.Immediate immediate, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(vertexConsumers)) return;
        var commands = (LitematicaPreviewPartCommandsAccessor) queue.getModelPartCommands();
        QuickLitematicaPreviewQueuedBuild.deferEntries(vertexConsumers, commands.quickcraft$getModelPartCommands().entrySet(), renderLayer -> {
            VertexConsumer vertexConsumer = vertexConsumers.getBuffer(renderLayer);
            return modelPartCommand -> {
                VertexConsumer vertexConsumer2;
                if (modelPartCommand.sprite() != null) {
                    if (modelPartCommand.hasGlint()) {
                        vertexConsumer2 = modelPartCommand.sprite()
                            .getTextureSpecificVertexConsumer(ItemRenderer.getItemGlintConsumer(vertexConsumers, renderLayer, modelPartCommand.sheeted(), true));
                    } else {
                        vertexConsumer2 = modelPartCommand.sprite().getTextureSpecificVertexConsumer(vertexConsumer);
                    }
                } else if (modelPartCommand.hasGlint()) {
                    vertexConsumer2 = ItemRenderer.getItemGlintConsumer(vertexConsumers, renderLayer, modelPartCommand.sheeted(), true);
                } else {
                    vertexConsumer2 = vertexConsumer;
                }

                this.matrices.peek().copy(modelPartCommand.matricesEntry());
                modelPartCommand.modelPart()
                    .render(this.matrices, vertexConsumer2, modelPartCommand.lightCoords(), modelPartCommand.overlayCoords(), modelPartCommand.tintedColor());
                if (modelPartCommand.outlineColor() != 0 && (renderLayer.getAffectedOutline().isPresent() || renderLayer.isOutline())) {
                    outlineVertexConsumerProvider.setColor(modelPartCommand.outlineColor());
                    VertexConsumer vertexConsumer3 = outlineVertexConsumerProvider.getBuffer(renderLayer);
                    modelPartCommand.modelPart()
                        .render(
                            this.matrices,
                            modelPartCommand.sprite() == null ? vertexConsumer3 : modelPartCommand.sprite().getTextureSpecificVertexConsumer(vertexConsumer3),
                            modelPartCommand.lightCoords(),
                            modelPartCommand.overlayCoords(),
                            modelPartCommand.tintedColor()
                        );
                }

                if (modelPartCommand.crumblingOverlay() != null) {
                    VertexConsumer vertexConsumer3 = new OverlayVertexConsumer(
                        immediate.getBuffer(ModelBaker.BLOCK_DESTRUCTION_RENDER_LAYERS.get(modelPartCommand.crumblingOverlay().progress())),
                        modelPartCommand.crumblingOverlay().cameraMatricesEntry(),
                        1.0F
                    );
                    modelPartCommand.modelPart()
                        .render(this.matrices, vertexConsumer3, modelPartCommand.lightCoords(), modelPartCommand.overlayCoords(), modelPartCommand.tintedColor());
                }

            };
        });
        ci.cancel();
    }
}
