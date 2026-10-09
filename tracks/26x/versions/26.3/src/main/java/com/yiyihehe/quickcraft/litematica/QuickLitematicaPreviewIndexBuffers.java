package com.yiyihehe.quickcraft.litematica;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog.LOGGER;

final class QuickLitematicaPreviewIndexBuffers implements AutoCloseable {
    private final GpuBuffer[] buffers = new GpuBuffer[3];
    private int current = -1;
    private boolean rotationDisabled;

    QuickLitematicaPreviewIndexBuffers(GpuBuffer first) { this.buffers[0] = first; }

    GpuBuffer current() { return this.buffers[Math.max(0, this.current)]; }

    void advance() {
        if (this.rotationDisabled) return;
        int next = (this.current + 1) % this.buffers.length;
        if (this.buffers[next] == null) {
            try {
                this.buffers[next] = RenderSystem.getDevice().createBuffer(() -> "QuickCraft rotating transparent indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, this.buffers[0].size());
            } catch (RuntimeException | OutOfMemoryError failure) {
                this.rotationDisabled = true;
                this.current = 0;
                LOGGER.warn("透明索引轮换缓冲分配失败，退回已有索引缓冲", failure);
                return;
            }
        }
        // 前帧的绘制继续引用旧槽；只在视角改变时轮换，避免立即覆盖 GPU 仍可能读取的索引。
        this.current = next;
    }

    @Override
    public void close() {
        for (GpuBuffer buffer : this.buffers) if (buffer != null) buffer.close();
    }
}
