package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPartFeatureRenderer.class)
public abstract class LitematicaPreviewQueuedPartMixin {
    @Shadow @Final private PoseStack poseStack;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferParts(Map<RenderType, List<SubmitNodeStorage.ModelPartSubmit>> modelPartSubmitsMap,
            MultiBufferSource.BufferSource bufferSource, OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource crumblingBufferSource, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(bufferSource)) return;
        QuickLitematicaPreviewQueuedBuild.deferEntries(bufferSource, modelPartSubmitsMap.entrySet(), renderType -> {
            VertexConsumer buffer = bufferSource.getBuffer(renderType);
            return modelPartSubmit -> {
                VertexConsumer actualBuffer;
                if (modelPartSubmit.sprite() != null) {
                    if (modelPartSubmit.hasFoil()) {
                        actualBuffer = modelPartSubmit.sprite().wrap(ItemFeatureRenderer.getFoilBuffer(bufferSource, renderType, modelPartSubmit.sheeted(), true));
                    } else {
                        actualBuffer = modelPartSubmit.sprite().wrap(buffer);
                    }
                } else if (modelPartSubmit.hasFoil()) {
                    actualBuffer = ItemFeatureRenderer.getFoilBuffer(bufferSource, renderType, modelPartSubmit.sheeted(), true);
                } else {
                    actualBuffer = buffer;
                }

                this.poseStack.last().set(modelPartSubmit.pose());
                modelPartSubmit.modelPart()
                    .render(this.poseStack, actualBuffer, modelPartSubmit.lightCoords(), modelPartSubmit.overlayCoords(), modelPartSubmit.tintedColor());
                if (modelPartSubmit.outlineColor() != 0 && (renderType.outline().isPresent() || renderType.isOutline())) {
                    outlineBufferSource.setColor(modelPartSubmit.outlineColor());
                    VertexConsumer outlineBuffer = outlineBufferSource.getBuffer(renderType);
                    modelPartSubmit.modelPart()
                        .render(
                            this.poseStack,
                            modelPartSubmit.sprite() == null ? outlineBuffer : modelPartSubmit.sprite().wrap(outlineBuffer),
                            modelPartSubmit.lightCoords(),
                            modelPartSubmit.overlayCoords(),
                            modelPartSubmit.tintedColor()
                        );
                }

                if (modelPartSubmit.crumblingOverlay() != null) {
                    VertexConsumer breakingBuffer = new SheetedDecalTextureGenerator(
                        crumblingBufferSource.getBuffer(ModelBakery.DESTROY_TYPES.get(modelPartSubmit.crumblingOverlay().progress())),
                        modelPartSubmit.crumblingOverlay().cameraPose(),
                        1.0F
                    );
                    modelPartSubmit.modelPart()
                        .render(this.poseStack, breakingBuffer, modelPartSubmit.lightCoords(), modelPartSubmit.overlayCoords(), modelPartSubmit.tintedColor());
                }

            };
        });
        ci.cancel();
    }
}
