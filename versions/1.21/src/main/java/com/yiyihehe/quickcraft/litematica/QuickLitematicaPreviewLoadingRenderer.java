package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.NativePreview;

final class QuickLitematicaPreviewLoadingRenderer implements AutoCloseable {
    private final QuickLitematicaPreviewLoadingTexture texture = new QuickLitematicaPreviewLoadingTexture();
    private final QuickLitematicaPreviewLoadingFramebuffer framebuffer = new QuickLitematicaPreviewLoadingFramebuffer();
    private boolean disabled;

    boolean draw(NativePreview preview, DrawContext context, int x, int y, int size, DragState drag) {
        if (this.disabled || preview.staticUploadComplete || preview.cancelled.get() || preview.dimensions == null) return false;
        var window = MinecraftClient.getInstance().getWindow();
        int pixels = Math.max(1, (int) Math.ceil(size * window.getScaleFactor()));
        var view = new QuickLitematicaPreviewLoadingTexture.View(preview, x, y, size,
                drag.dx, drag.dy, drag.angle, drag.pitch, drag.scale);
        try {
            // GUI 的待提交顶点必须先画回主目标，不能落进预览 FBO。
            context.draw();
            boolean reuse = this.framebuffer.hasSize(pixels)
                    && this.texture.canReuse(view, true, System.nanoTime());
            if (!reuse) {
                try {
                    RenderSystem.disableScissor();
                    var target = this.framebuffer.prepare(pixels);
                    preview.renderLoadingFrame(target, preview.dimensions, drag);
                } finally {
                    this.framebuffer.restore();
                }
                this.texture.rendered(view);
            } else preview.trace.milestone("加载期首次复用预览贴图，静态上传继续，视角改变或上传完成立即重画");
            this.framebuffer.blit(context, x, y, size);
            return true;
        } catch (RuntimeException | OutOfMemoryError failure) {
            this.disabled = true;
            this.close();
            LOGGER.warn("加载期贴图复用不可用，回退直接绘制", failure);
            return false;
        }
    }

    @Override public void close() {
        this.texture.clear();
        this.framebuffer.close();
    }
}
