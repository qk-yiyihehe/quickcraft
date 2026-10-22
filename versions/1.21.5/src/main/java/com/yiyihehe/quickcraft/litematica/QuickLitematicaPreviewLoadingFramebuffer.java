package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

final class QuickLitematicaPreviewLoadingFramebuffer implements AutoCloseable {
    // 贴回 GUI 时忽略已有深度，且不写深度；与旧后端的 disableDepthTest 保持一致。
    private static final RenderPipeline BLIT = new OpaqueGuiPipeline(RenderPipelines.GUI_TEXTURED_OVERLAY);
    private Framebuffer framebuffer;
    private GpuBuffer quad;

    boolean hasSize(int size) { return this.framebuffer != null && this.framebuffer.textureWidth == size; }

    Framebuffer prepare(int size) {
        if (!this.hasSize(size)) {
            this.close();
            this.framebuffer = new SimpleFramebuffer("QuickCraft loading preview", size, size, true);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                this.framebuffer.getColorAttachment(), 0, this.framebuffer.getDepthAttachment(), 1.0);
        return this.framebuffer;
    }

    void restore() {}

    void blit(DrawContext context, int x, int y, int size) {
        if (this.quad == null) {
            try (BufferAllocator allocator = new BufferAllocator(256)) {
                var builder = new BufferBuilder(allocator, BLIT.getVertexFormatMode(), BLIT.getVertexFormat());
                builder.vertex(0, 0, 0).texture(0, 1).color(-1);
                builder.vertex(0, 1, 0).texture(0, 0).color(-1);
                builder.vertex(1, 1, 0).texture(1, 0).color(-1);
                builder.vertex(1, 0, 0).texture(1, 1).color(-1);
                try (var built = builder.end()) {
                    this.quad = RenderSystem.getDevice().createBuffer(() -> "QuickCraft loading preview quad",
                            BufferType.VERTICES, BufferUsage.STATIC_WRITE, built.getBuffer());
                }
            }
        }
        context.enableScissor(x + 1, y + 1, x + size - 2, y + size - 2);
        var modelView = RenderSystem.getModelViewStack();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        modelView.pushMatrix();
        try {
            // 1.21.5 在 draw 前用全局状态重写预定义 uniform，pass.setUniform 的变换会被覆盖。
            modelView.mul(context.getMatrices().peek().getPositionMatrix())
                    .translate(x, y, 0).scale(size, size, 1);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            var main = MinecraftClient.getInstance().getFramebuffer();
            var sequential = RenderSystem.getSequentialBuffer(BLIT.getVertexFormatMode());
            var indices = sequential.getIndexBuffer(6);
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    main.getColorAttachment(), OptionalInt.empty(), main.getDepthAttachment(), OptionalDouble.empty())) {
                pass.setPipeline(BLIT);
                pass.setVertexBuffer(0, this.quad);
                pass.setIndexBuffer(indices, sequential.getIndexType());
                pass.enableScissor(RenderSystem.SCISSOR_STATE);
                pass.bindSampler("Sampler0", this.framebuffer.getColorAttachment());
                pass.drawIndexed(0, 6);
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            context.disableScissor();
        }
    }

    @Override public void close() {
        if (this.quad != null) { this.quad.close(); this.quad = null; }
        if (this.framebuffer != null) { this.framebuffer.delete(); this.framebuffer = null; }
    }

    private static final class OpaqueGuiPipeline extends RenderPipeline {
        OpaqueGuiPipeline(RenderPipeline gui) {
            // 加载期不包含透明层；丢弃空像素后直接覆盖，保持原生裁切层的颜色。
            super(Identifier.of("quickcraft", "pipeline/preview_loading_blit"), gui.getVertexShader(), gui.getFragmentShader(),
                    gui.getShaderDefines(), gui.getSamplers(), gui.getUniforms(),
                    Optional.empty(),
                    gui.getDepthTestFunction(), gui.getPolygonMode(), gui.isCull(), gui.isWriteColor(), gui.isWriteAlpha(),
                    gui.isWriteDepth(), gui.getColorLogic(), gui.getVertexFormat(), gui.getVertexFormatMode(),
                    gui.getDepthBiasScaleFactor(), gui.getDepthBiasConstant());
        }
    }
}
