package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaPreviewVertexBufferAccessor;
import net.minecraft.client.gl.VertexBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog.LOGGER;

final class QuickLitematicaPreviewLegacyBuffers implements AutoCloseable {
    private final VertexBuffer owner;
    private final LitematicaPreviewVertexBufferAccessor access;
    private final net.minecraft.client.gl.GpuBuffer[] buffers;
    private int current;
    private boolean rotationDisabled;

    QuickLitematicaPreviewLegacyBuffers(VertexBuffer owner, boolean rotate) {
        this.owner = owner;
        this.access = (LitematicaPreviewVertexBufferAccessor) owner;
        this.buffers = new net.minecraft.client.gl.GpuBuffer[rotate ? 3 : 1];
        this.buffers[0] = this.access.quickcraft$getIndexBuffer();
    }

    void write(ByteBuffer data) {
        this.owner.bind();
        if (!this.rotationDisabled && this.buffers.length > 1) {
            int next = (this.current + 1) % this.buffers.length;
            if (this.buffers[next] == null) {
                try {
                    this.buffers[next] = new net.minecraft.client.gl.GpuBuffer(net.minecraft.client.gl.GlBufferTarget.INDICES, net.minecraft.client.gl.GlUsage.DYNAMIC_WRITE, data.remaining());
                } catch (RuntimeException | OutOfMemoryError failure) {
                    this.rotationDisabled = true;
                    LOGGER.warn("透明索引轮换分配失败，保留当前缓冲", failure);
                    next = this.current;
                }
            }
            this.current = next;
        }
        var indices = this.buffers[this.current];
        if (indices.size < data.remaining()) indices.resize(data.remaining());
        indices.copyFrom(data, 0);
        this.access.quickcraft$setIndexBuffer(indices);
    }

    static void writeVertices(VertexBuffer destination, ByteBuffer data, int offset) {
        var access = (LitematicaPreviewVertexBufferAccessor) destination;
        int previousArray = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            access.quickcraft$getVertexBuffer().copyFrom(data, offset);
        } finally {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, previousArray);
        }
    }

    static void copyVertices(VertexBuffer destination, List<VertexBuffer> sources, int[] vertexCounts, long bytes) {
        var access = (LitematicaPreviewVertexBufferAccessor) destination;
        int previousRead = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER);
        int previousWrite = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER);
        int previousArray = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            var vertices = access.quickcraft$getVertexBuffer();
            vertices.resize(Math.toIntExact(bytes));
            // 原生 resize 对可写缓冲延迟分配，空写触发分配并保留原版的显存追踪。
            vertices.copyFrom(java.nio.ByteBuffer.allocateDirect(0), 0);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, access.quickcraft$getVertexBuffer().handle);
            if (org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, org.lwjgl.opengl.GL15.GL_BUFFER_SIZE) != bytes) {
                throw new IllegalStateException("Transparent vertex buffer allocation failed");
            }
            long offset = 0;
            for (int i = 0; i < sources.size(); i++) {
                VertexBuffer source = sources.get(i);
                long length = (long) vertexCounts[i] * source.getVertexFormat().getVertexSizeByte();
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,
                        ((LitematicaPreviewVertexBufferAccessor) source).quickcraft$getVertexBuffer().handle);
                org.lwjgl.opengl.GL31.glCopyBufferSubData(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,
                        org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0, offset, length);
                offset += length;
            }
        } finally {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, previousRead);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, previousWrite);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, previousArray);
        }
    }

    @Override
    public void close() {
        // VertexBuffer.close 释放当前槽，其他槽由预览释放。
        for (int i = 0; i < this.buffers.length; i++) {
            if (this.buffers[i] != this.access.quickcraft$getIndexBuffer() && this.buffers[i] != null) this.buffers[i].close();
        }
    }
}
