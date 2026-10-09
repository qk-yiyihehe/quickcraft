package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

final class QuickLitematicaPreviewLoadingFramebuffer implements AutoCloseable {
    private Framebuffer framebuffer;

    boolean hasSize(int size) { return this.framebuffer != null && this.framebuffer.textureWidth == size; }

    Framebuffer prepare(int size) {
        if (!this.hasSize(size)) {
            this.close();
            this.framebuffer = new SimpleFramebuffer(size, size, true);
            this.framebuffer.setClearColor(0, 0, 0, 0);
        }
        this.framebuffer.clear();
        return this.framebuffer;
    }

    void restore() { MinecraftClient.getInstance().getFramebuffer().beginWrite(true); }

    void blit(DrawContext context, int x, int y, int size) {
        context.enableScissor(x + 1, y + 1, x + size - 2, y + size - 2);
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        float[] color = RenderSystem.getShaderColor();
        float red = color[0], green = color[1], blue = color[2], alpha = color[3];
        RenderSystem.setShaderColor(1, 1, 1, 1);
        // 加载期只画不透明/裁切层；着色器丢弃空像素，其余直接覆盖，保持裁切纹理颜色。
        try {
            RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX);
            RenderSystem.setShaderTexture(0, this.framebuffer.getColorAttachment());
            var matrix = context.getMatrices().peek().getPositionMatrix();
            BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
            builder.vertex(matrix, x, y, 0).texture(0, 1);
            builder.vertex(matrix, x, y + size, 0).texture(0, 0);
            builder.vertex(matrix, x + size, y + size, 0).texture(1, 0);
            builder.vertex(matrix, x + size, y, 0).texture(1, 1);
            BufferRenderer.drawWithGlobalProgram(builder.end());
        } finally {
            RenderSystem.setShaderColor(red, green, blue, alpha);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            context.disableScissor();
        }
    }

    @Override public void close() {
        if (this.framebuffer != null) {
            this.framebuffer.delete();
            this.framebuffer = null;
        }
    }
}
