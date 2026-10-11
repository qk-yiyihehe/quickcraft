package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.CustomCommandRenderer;
import net.minecraft.client.render.command.DebugHitboxCommandRenderer;
import net.minecraft.client.render.command.FallingBlockCommandRenderer;
import net.minecraft.client.render.command.FireCommandRenderer;
import net.minecraft.client.render.command.ItemCommandRenderer;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.client.render.command.LayeredCustomCommandRenderer;
import net.minecraft.client.render.command.LeashCommandRenderer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.ModelPartCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.RenderDispatcher;
import net.minecraft.client.render.command.ShadowPiecesCommandRenderer;
import net.minecraft.client.render.command.TextCommandRenderer;
import net.minecraft.client.texture.AtlasManager;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderDispatcher.class)
public abstract class LitematicaPreviewQueuedDispatcherMixin implements QuickLitematicaPreviewQueuedBuild.DispatcherBridge {
    @Shadow @Final private OrderedRenderCommandQueueImpl queue;
    @Shadow @Final private BlockRenderManager blockRenderManager;
    @Shadow @Final private VertexConsumerProvider.Immediate vertexConsumers;
    @Shadow @Final private AtlasManager atlasManager;
    @Shadow @Final private OutlineVertexConsumerProvider outlineVertexConsumers;
    @Shadow @Final private VertexConsumerProvider.Immediate crumblingOverlayVertexConsumers;
    @Shadow @Final private TextRenderer textRenderer;
    @Shadow @Final private ShadowPiecesCommandRenderer shadowPiecesCommandRenderer;
    @Shadow @Final private FireCommandRenderer fireCommandRenderer;
    @Shadow @Final private ModelCommandRenderer modelCommandRenderer;
    @Shadow @Final private ModelPartCommandRenderer modelPartCommandRenderer;
    @Shadow @Final private LabelCommandRenderer labelCommandRenderer;
    @Shadow @Final private TextCommandRenderer textCommandRenderer;
    @Shadow @Final private DebugHitboxCommandRenderer debugHitboxCommandRenderer;
    @Shadow @Final private LeashCommandRenderer leashCommandRenderer;
    @Shadow @Final private ItemCommandRenderer itemCommandRenderer;
    @Shadow @Final private CustomCommandRenderer customCommandRenderer;
    @Shadow @Final private FallingBlockCommandRenderer fallingBlockCommandRenderer;
    @Shadow @Final private LayeredCustomCommandRenderer layeredCustomCommandRenderer;

    @Override
    public void quickcraft$queuePreviewPhases(Consumer<Runnable> submit) {
        for (BatchingRenderCommandQueue batchingRenderCommandQueue : this.queue.getBatchingQueues().values()) {
            submit.accept(() -> this.shadowPiecesCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers));
            submit.accept(() -> this.modelCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.outlineVertexConsumers, this.crumblingOverlayVertexConsumers));
            submit.accept(() -> this.modelPartCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.outlineVertexConsumers, this.crumblingOverlayVertexConsumers));
            submit.accept(() -> this.fireCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.atlasManager));
            submit.accept(() -> this.labelCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.textRenderer));
            submit.accept(() -> this.textCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers));
            submit.accept(() -> this.debugHitboxCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers));
            submit.accept(() -> this.leashCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers));
            submit.accept(() -> this.itemCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.outlineVertexConsumers));
            submit.accept(() -> this.fallingBlockCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers, this.blockRenderManager, this.outlineVertexConsumers));
            submit.accept(() -> this.customCommandRenderer.render(batchingRenderCommandQueue, this.vertexConsumers));
            submit.accept(() -> this.layeredCustomCommandRenderer.render(batchingRenderCommandQueue));
        }

        submit.accept(() -> this.queue.clear());
    }
}
