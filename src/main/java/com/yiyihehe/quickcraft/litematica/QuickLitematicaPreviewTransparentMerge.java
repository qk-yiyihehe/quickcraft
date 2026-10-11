package com.yiyihehe.quickcraft.litematica;

import org.joml.Vector3f;
import java.util.List;

final class QuickLitematicaPreviewTransparentMerge {
    // 合并时旧 VBO 尚在使用；限制新增顶点和索引合计，避免超大投影的临时显存翻倍。
    private static final long EXTRA_GPU_BYTES = 256L * 1024 * 1024;

    static int indexCountWithinBudget(long vertexBytes, long quads) {
        if (vertexBytes < 0 || quads <= 0 || quads > (Integer.MAX_VALUE - 8L) / 24
                || vertexBytes > EXTRA_GPU_BYTES - quads * 24) return 0;
        return (int) (quads * 6);
    }


    static Stream stream(List<QuickLitematicaPreview3D.LayerMesh> layers, int stride) {
        long vertices = 0;
        for (var layer : layers) if (layer.needsTranslucentSorting()) vertices += layer.vertexCount();
        return stream(vertices, stride);
    }

    static Stream stream(long vertices, int stride) {
        if (vertices == 0) return null;
        if (vertices < 0 || vertices % 4 != 0 || stride <= 0) throw new IllegalArgumentException("Invalid transparent mesh size");
        // 旧版本原生缓冲与索引暂存采用 int 长度；极端强制投影保留分批支持，不能因此拒绝加载。
        if (vertices > (Integer.MAX_VALUE - 8L) / stride || vertices / 4 > (Integer.MAX_VALUE - 8L) / 24) return null;
        long bytes = vertices * stride;
        if (indexCountWithinBudget(bytes, vertices / 4) != 0) return null;
        return new Stream(Math.toIntExact(vertices), stride);
    }

    static final class Stream {
        final int vertices, stride, vertexBytes, indexCount;
        private int uploaded;

        Stream(int vertices, int stride) {
            this.vertices = vertices;
            this.stride = stride;
            this.vertexBytes = Math.multiplyExact(vertices, stride);
            this.indexCount = Math.multiplyExact(vertices / 4, 6);
        }

        int byteOffset() { return Math.multiplyExact(this.uploaded, this.stride); }
        int quadOffset() { return this.uploaded / 4; }

        void checkBatch(int vertices, int bytes) {
            if (vertices <= 0 || vertices % 4 != 0 || vertices > this.vertices - this.uploaded
                    || (long) vertices * this.stride != bytes)
                throw new IllegalArgumentException("Transparent upload batch does not match its arena");
        }

        void advance(int vertices, int bytes) {
            this.checkBatch(vertices, bytes);
            this.uploaded += vertices;
            if (this.uploaded == this.vertices) QuickLitematicaPreviewLog.LOGGER.info(
                    "transparent-arena-v1：透明层按批上传完成，顶点={}，顶点字节={}，索引字节={}，GPU 批次=1，无旧整层副本",
                    this.vertices, this.vertexBytes, (long) this.indexCount * 4);
        }

        void requireComplete() {
            if (this.uploaded != this.vertices) throw new IllegalStateException("Transparent arena upload is incomplete");
        }
    }

    static boolean canRotateStreamedIndices(int indexCount) {
        // 直接上传的 VBO 和首个索引槽已计入常驻显存；这里仅预算另外两个索引槽。
        return indexCount > 0 && indexCount * 8L <= EXTRA_GPU_BYTES;
    }

    static boolean canRotateIndices(long vertexBytes, int indexCount) {
        return vertexBytes >= 0 && indexCount > 0 && vertexBytes <= EXTRA_GPU_BYTES - indexCount * 12L;
    }

    static Vector3f[] centers(List<Vector3f[]> batches) {
        int count = 0;
        for (var batch : batches) count = Math.addExact(count, batch.length);
        Vector3f[] combined = new Vector3f[count];
        int offset = 0;
        for (var batch : batches) {
            System.arraycopy(batch, 0, combined, offset, batch.length);
            offset += batch.length;
        }
        return combined;
    }
}
