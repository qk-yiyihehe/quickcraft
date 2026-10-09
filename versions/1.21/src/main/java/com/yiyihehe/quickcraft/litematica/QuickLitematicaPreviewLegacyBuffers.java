package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaPreviewVertexBufferAccessor;
import net.minecraft.client.gl.VertexBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog.LOGGER;

final class QuickLitematicaPreviewLegacyBuffers implements AutoCloseable {
    private final VertexBuffer owner;
    private final LitematicaPreviewVertexBufferAccessor access;
    private final int[] buffers;
    private int current;
    private boolean rotationDisabled;

    QuickLitematicaPreviewLegacyBuffers(VertexBuffer owner, boolean rotate) {
        this.owner = owner;
        this.access = (LitematicaPreviewVertexBufferAccessor) owner;
        this.buffers = new int[rotate ? 3 : 1];
        this.buffers[0] = this.access.quickcraft$getIndexBufferId();
    }

    void write(ByteBuffer data) {
        this.owner.bind();
        if (!this.rotationDisabled && this.buffers.length > 1) {
            int next = (this.current + 1) % this.buffers.length;
            if (this.buffers[next] == 0) {
                try {
                    this.buffers[next] = org.lwjgl.opengl.GL15.glGenBuffers();
                    if (this.buffers[next] == 0) throw new IllegalStateException("Transparent index buffer allocation failed");
                } catch (RuntimeException | OutOfMemoryError failure) {
                    this.rotationDisabled = true;
                    LOGGER.warn("透明索引轮换分配失败，保留当前缓冲", failure);
                    next = this.current;
                }
            }
            this.current = next;
        }
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, this.buffers[this.current]);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, data, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
        if (org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER,
                org.lwjgl.opengl.GL15.GL_BUFFER_SIZE) != data.remaining()) {
            throw new IllegalStateException("Transparent index buffer allocation failed");
        }
        this.access.quickcraft$setIndexBufferId(this.buffers[this.current]);
    }

    static void writeVertices(VertexBuffer destination, ByteBuffer data, int offset) {
        var access = (LitematicaPreviewVertexBufferAccessor) destination;
        int previousArray = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, access.quickcraft$getVertexBufferId());
            org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, offset, data);
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
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, access.quickcraft$getVertexBufferId());
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, bytes, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, access.quickcraft$getVertexBufferId());
            if (org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, org.lwjgl.opengl.GL15.GL_BUFFER_SIZE) != bytes) {
                throw new IllegalStateException("Transparent vertex buffer allocation failed");
            }
            long offset = 0;
            for (int i = 0; i < sources.size(); i++) {
                VertexBuffer source = sources.get(i);
                long length = (long) vertexCounts[i] * source.getVertexFormat().getVertexSizeByte();
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,
                        ((LitematicaPreviewVertexBufferAccessor) source).quickcraft$getVertexBufferId());
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
            if (this.buffers[i] != this.access.quickcraft$getIndexBufferId() && this.buffers[i] != 0) org.lwjgl.opengl.GL15.glDeleteBuffers(this.buffers[i]);
        }
    }
}
