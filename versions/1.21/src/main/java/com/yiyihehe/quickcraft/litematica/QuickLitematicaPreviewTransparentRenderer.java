package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.BufferAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LayerMesh;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog.LOGGER;

final class QuickLitematicaPreviewTransparentRenderer implements AutoCloseable {
    private final QuickLitematicaPreviewTransparentOrder transparentOrder = new QuickLitematicaPreviewTransparentOrder();
    private final Map<VertexBuffer, TranslucentBuffer> translucentBuffers = new java.util.IdentityHashMap<>();
    private final Map<VertexBuffer, QuickLitematicaPreviewLegacyBuffers> transparentIndexBuffers = new java.util.IdentityHashMap<>();
    private final QuickLitematicaPreviewQuadDepth sortDepth = new QuickLitematicaPreviewQuadDepth();
    private int[] sortOrder = new int[0], sortKeys = new int[0];
    @Nullable
    private BufferAllocator transparentIndexAllocator;
    private boolean rotateTransparentIndices;
    boolean snapshotRendering;
    private QuickLitematicaPreviewAsyncSort transparentAsyncSort;

    private record TranslucentBuffer(net.minecraft.client.render.BuiltBuffer.SortState state, QuickLitematicaPreviewQuadDepth depth) {}
    void register(VertexBuffer buffer, BuiltBuffer.SortState state) {
        this.translucentBuffers.put(buffer, new TranslucentBuffer(state, new QuickLitematicaPreviewQuadDepth()));
    }

    void publish(VertexBuffer buffer) {
        this.transparentOrder.add(this.translucentBuffers.get(buffer).state().centroids());
    }

    List<QuickLitematicaPreviewTransparentOrder.Run> runs(Matrix4f modelView) {
        return this.transparentOrder.runs(modelView);
    }

    VertexFormat.IndexType indexType(VertexBuffer buffer) {
        return this.translucentBuffers.get(buffer).state().indexType();
    }

    private boolean resortAsync(VertexBuffer buffer, TranslucentBuffer translucent, Matrix4f modelView) {
        if (this.snapshotRendering) {
            if (this.transparentAsyncSort != null) { this.transparentAsyncSort.close(); this.transparentAsyncSort = null; }
            return false;
        }
        if (translucent == null || !translucent.depth().initialized()) return false;
        var points = translucent.state().centroids();
        if (points.length < QuickLitematicaPreviewAsyncSort.MIN_QUADS || this.translucentBuffers.size() != 1) return false;
        if (this.transparentAsyncSort == null) {
            this.transparentAsyncSort = new QuickLitematicaPreviewAsyncSort(points.length, translucent.state().indexType().size,
                    translucent.depth(), (depth, order, keys) -> depth.sort(points, order, keys), this.sortOrder, this.sortKeys);
            this.sortOrder = new int[0];
            this.sortKeys = new int[0];
            if (this.transparentIndexAllocator != null) { this.transparentIndexAllocator.close(); this.transparentIndexAllocator = null; }
        }
        var result = this.transparentAsyncSort.poll();
        if (result != null) {
            try (result; var phase = QuickLitematicaPreviewLog.phase("后台透明结果索引上传")) {
                this.transparentIndexBuffers.computeIfAbsent(buffer,
                        ignored -> new QuickLitematicaPreviewLegacyBuffers(buffer, this.rotateTransparentIndices)).write(result.indices());
                translucent.depth().update(result.view);
            }
        }
        this.transparentAsyncSort.request(modelView);
        return true;
    }

    void resort(VertexBuffer buffer, Matrix4f modelView) {
        TranslucentBuffer translucent = this.translucentBuffers.get(buffer);
        if (this.resortAsync(buffer, translucent, modelView)) return;
        if (translucent == null || !translucent.depth().update(modelView)) return;
        this.sortDepth.update(modelView);
        var points = translucent.state().centroids();
        if (this.sortOrder.length != points.length) {
            this.sortOrder = new int[points.length];
            this.sortKeys = new int[points.length];
        }
        if (this.transparentIndexAllocator == null) this.transparentIndexAllocator = new BufferAllocator(256);
        try (var phase = QuickLitematicaPreviewLog.phase("静态透明层按视角重排")) {
            int[] order;
            try (var sorting = QuickLitematicaPreviewLog.phase("透明面中心排序")) {
                order = this.sortDepth.sort(points, this.sortOrder, this.sortKeys);
            }
            BufferAllocator.CloseableBuffer indices;
            try (var generation = QuickLitematicaPreviewLog.phase("透明三角形索引生成")) {
                indices = QuickLitematicaPreviewIndices.build(this.transparentIndexAllocator, order, translucent.state().indexType().size);
            }
            try (indices; var upload = QuickLitematicaPreviewLog.phase("透明索引上传")) {
                this.transparentIndexBuffers.computeIfAbsent(buffer,
                        ignored -> new QuickLitematicaPreviewLegacyBuffers(buffer, this.rotateTransparentIndices)).write(indices.getBuffer());
            }
        } finally {
            this.transparentIndexAllocator.clear();
        }
    }

    private QuickLitematicaPreviewTransparentMerge.Stream transparentUpload;
    private boolean initializingArena;

    boolean uploadBatch(LayerMesh mesh, BuiltBuffer built, BuiltBuffer.SortState batchSort,
                        QuickLitematicaPreview3D.MeshData meshData, Map<LayerKey, List<VertexBuffer>> vertexBuffers,
                        java.util.function.Function<LayerMesh, VertexBuffer> upload) {
        if (this.initializingArena) return false;
        var plan = this.transparentUpload;
        if (plan == null) plan = QuickLitematicaPreviewTransparentMerge.stream(meshData.layers(), built.getBuffer().remaining() / mesh.vertexCount());
        if (plan == null) return false;
        try (built) {
            int batchBytes = built.getBuffer().remaining();
            plan.checkBatch(mesh.vertexCount(), batchBytes);
            if (this.transparentUpload == null) {
                VertexBuffer combined = null;
                boolean published = false;
                try {
                    var centers = new Vector3f[plan.vertices / 4];
                    this.initializingArena = true;
                    combined = upload.apply(new LayerMesh(LayerKey.TRANSLUCENT, new byte[4 * QUANTIZED_VERTEX_BYTES]));
                    if (combined == null) throw new IllegalStateException("Transparent arena initialization failed");
                    QuickLitematicaPreviewLegacyBuffers.copyVertices(combined, List.of(), new int[0], plan.vertexBytes);
                    var access = (com.yiyihehe.quickcraft.mixin.LitematicaPreviewVertexBufferAccessor) combined;
                    access.quickcraft$setIndexType(VertexFormat.IndexType.INT);
                    access.quickcraft$setIndexCount(plan.indexCount);
                    this.translucentBuffers.put(combined, new TranslucentBuffer(
                            new BuiltBuffer.SortState(centers, VertexFormat.IndexType.INT), new QuickLitematicaPreviewQuadDepth()));
                    vertexBuffers.put(LayerKey.TRANSLUCENT, List.of(combined));
                    this.transparentUpload = plan;
                    this.rotateTransparentIndices = QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(plan.indexCount);
                    published = true;
                } finally {
                    this.initializingArena = false;
                    VertexBuffer.unbind();
                    if (!published && combined != null) {
                        this.translucentBuffers.remove(combined);
                        combined.close();
                    }
                }
            }
            var buffer = vertexBuffers.get(LayerKey.TRANSLUCENT).getFirst();
            QuickLitematicaPreviewLegacyBuffers.writeVertices(buffer, built.getBuffer(), plan.byteOffset());
            System.arraycopy(batchSort.centroids(), 0, this.translucentBuffers.get(buffer).state().centroids(),
                    plan.quadOffset(), batchSort.centroids().length);
            plan.advance(mesh.vertexCount(), batchBytes);
            return true;
        }
    }

    void merge(Map<LayerKey, List<VertexBuffer>> vertexBuffers, java.util.function.Function<LayerMesh, VertexBuffer> upload) {
        if (this.transparentUpload != null) this.transparentUpload.requireComplete();
        List<VertexBuffer> buffers = vertexBuffers.get(LayerKey.TRANSLUCENT);
        if (buffers == null || buffers.size() < 2) return;
        long vertexBytes = 0, quads = 0;
        int[] vertexCounts = new int[buffers.size()];
        List<Vector3f[]> centers = new ArrayList<>();
        for (int i = 0; i < buffers.size(); i++) {
            VertexBuffer buffer = buffers.get(i);
            TranslucentBuffer translucent = this.translucentBuffers.get(buffer);
            if (translucent == null) return;
            var points = translucent.state().centroids();
            centers.add(points);
            vertexCounts[i] = Math.multiplyExact(points.length, 4);
            vertexBytes += (long) vertexCounts[i] * buffer.getVertexFormat().getVertexSizeByte();
            quads += points.length;
        }
        int indexCount = QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(vertexBytes, quads);
        if (indexCount == 0) {
            LOGGER.info("透明合并保留分批：批次={}，顶点字节={}，四边形={}，新增 GPU 预算=256 MiB", buffers.size(), vertexBytes, quads);
            return;
        }
        VertexBuffer combined = null;
        boolean published = false;
        try (var phase = QuickLitematicaPreviewLog.phase("透明 GPU 批次合并")) {
            var points = QuickLitematicaPreviewTransparentMerge.centers(centers);
            // 原生上传一个完整占位 quad 初始化 VAO/格式；实际顶点通过 GPU 复制，不在 CPU 拼接整层。
            combined = upload.apply(new LayerMesh(LayerKey.TRANSLUCENT, new byte[4 * QUANTIZED_VERTEX_BYTES]));
            if (combined == null) throw new IllegalStateException("Transparent vertex buffer initialization failed");
            QuickLitematicaPreviewLegacyBuffers.copyVertices(combined, buffers, vertexCounts, vertexBytes);
            var access = (com.yiyihehe.quickcraft.mixin.LitematicaPreviewVertexBufferAccessor) combined;
            access.quickcraft$setIndexType(VertexFormat.IndexType.INT);
            access.quickcraft$setIndexCount(indexCount);
            this.translucentBuffers.put(combined, new TranslucentBuffer(
                    new net.minecraft.client.render.BuiltBuffer.SortState(points, VertexFormat.IndexType.INT),
                    new QuickLitematicaPreviewQuadDepth()));
            vertexBuffers.put(LayerKey.TRANSLUCENT, List.of(combined));
            this.transparentOrder.clear();
            this.rotateTransparentIndices = QuickLitematicaPreviewTransparentMerge.canRotateIndices(vertexBytes, indexCount);
            published = true;
            LOGGER.info("transparent-merge-v1：透明 GPU 批次 {} -> 1，顶点字节={}，索引字节={}，旋转使用单次绘制", buffers.size(), vertexBytes, (long) indexCount * 4);
            LOGGER.info("transparent-stream-v1：索引缓冲槽数={}，索引暂存与排序工作数组按预览复用，新增 GPU 总预算=256 MiB", this.rotateTransparentIndices ? 3 : 1);
        } catch (RuntimeException | OutOfMemoryError failure) {
            LOGGER.warn("透明 GPU 合并失败，保留原分批渲染", failure);
        } finally {
            VertexBuffer.unbind();
            if (published) {
                for (VertexBuffer buffer : buffers) {
                    this.translucentBuffers.remove(buffer);
                    buffer.close();
                }
            } else if (combined != null) {
                this.translucentBuffers.remove(combined);
                combined.close();
            }
        }
    }
    @Override
    public void close() {
        this.transparentIndexBuffers.values().forEach(QuickLitematicaPreviewLegacyBuffers::close);
        this.transparentIndexBuffers.clear();
        if (this.transparentAsyncSort != null) { this.transparentAsyncSort.close(); this.transparentAsyncSort = null; }
        this.sortOrder = new int[0];
        this.sortKeys = new int[0];
        if (this.transparentIndexAllocator != null) {
            this.transparentIndexAllocator.close();
            this.transparentIndexAllocator = null;
        }
        this.rotateTransparentIndices = false;
        this.transparentOrder.clear();
        this.translucentBuffers.clear();
        this.transparentUpload = null;
        this.initializingArena = false;
    }
}
