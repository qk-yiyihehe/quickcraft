package com.yiyihehe.quickcraft.litematica;

import java.util.Arrays;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.CacheFile;

/** 1.21.x 预览缓存的顶点字节记录器；适配器选择保留或省略 overlay 的既有布局。 */
final class QuickLitematicaPreviewVertexBuffer {
    private final int vertexBytes;
    private byte[] bytes;
    private int position;

    QuickLitematicaPreviewVertexBuffer(int vertexBytes) {
        this.vertexBytes = vertexBytes;
        this.bytes = new byte[vertexBytes * 256];
    }

    boolean isEmpty() {
        return this.position == 0;
    }

    int vertexCount() {
        return this.position / this.vertexBytes;
    }

    void add(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
        this.writePrefix(x, y, z, argb, u, v);
        this.writeInt(light);
        this.writeShort(CacheFile.encodeNormal(nx, ny, nz));
    }

    void add(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        this.writePrefix(x, y, z, argb, u, v);
        this.writeShort(CacheFile.encodeOverlay(overlay));
        this.writeInt(light);
        this.writeShort(CacheFile.encodeNormal(nx, ny, nz));
    }

    private void writePrefix(float x, float y, float z, int argb, float u, float v) {
        int needed = this.position + this.vertexBytes;
        if (needed > this.bytes.length) {
            this.bytes = Arrays.copyOf(this.bytes,
                    QuickLitematicaPreviewVertexBudget.growCapacity(this.bytes.length, needed));
        }
        this.writeInt(Float.floatToIntBits(x));
        this.writeInt(Float.floatToIntBits(y));
        this.writeInt(Float.floatToIntBits(z));
        this.writeInt(argb);
        this.writeInt(Float.floatToIntBits(u));
        this.writeInt(Float.floatToIntBits(v));
    }

    byte[] takeBytes() {
        byte[] result = this.bytes.length == this.position ? this.bytes : Arrays.copyOf(this.bytes, this.position);
        this.bytes = new byte[0];
        this.position = 0;
        return result;
    }

    private void writeInt(int value) {
        this.bytes[this.position++] = (byte) (value >>> 24);
        this.bytes[this.position++] = (byte) (value >>> 16);
        this.bytes[this.position++] = (byte) (value >>> 8);
        this.bytes[this.position++] = (byte) value;
    }

    private void writeShort(short value) {
        this.bytes[this.position++] = (byte) (value >>> 8);
        this.bytes[this.position++] = (byte) value;
    }
}
