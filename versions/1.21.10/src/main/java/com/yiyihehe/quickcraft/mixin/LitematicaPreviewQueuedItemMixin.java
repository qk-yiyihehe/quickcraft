package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.client.render.command.ItemCommandRenderer;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemCommandRenderer.class)
public abstract class LitematicaPreviewQueuedItemMixin {
    @Shadow @Final private MatrixStack matrices;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$deferItems(BatchingRenderCommandQueue queue, VertexConsumerProvider.Immediate vertexConsumers,
            OutlineVertexConsumerProvider outlineVertexConsumers, CallbackInfo ci) {
        if (!QuickLitematicaPreviewQueuedBuild.isCapturing(vertexConsumers)) return;
        QuickLitematicaPreviewQueuedBuild.deferList(vertexConsumers, queue.getItemCommands(), itemCommand -> {
            this.matrices.push();
            this.matrices.peek().copy(itemCommand.positionMatrix());
            ItemRenderer.renderItem(
                itemCommand.displayContext(),
                this.matrices,
                vertexConsumers,
                itemCommand.lightCoords(),
                itemCommand.overlayCoords(),
                itemCommand.tintLayers(),
                itemCommand.quads(),
                itemCommand.renderLayer(),
                itemCommand.glintType()
            );
            if (itemCommand.outlineColor() != 0) {
                outlineVertexConsumers.setColor(itemCommand.outlineColor());
                ItemRenderer.renderItem(
                    itemCommand.displayContext(),
                    this.matrices,
                    outlineVertexConsumers,
                    itemCommand.lightCoords(),
                    itemCommand.overlayCoords(),
                    itemCommand.tintLayers(),
                    itemCommand.quads(),
                    itemCommand.renderLayer(),
                    ItemRenderState.Glint.NONE
                );
            }

            this.matrices.pop();

        });
        ci.cancel();
    }
}
