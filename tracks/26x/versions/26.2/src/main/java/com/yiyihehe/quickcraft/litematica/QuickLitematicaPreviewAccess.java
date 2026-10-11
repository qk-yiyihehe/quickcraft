package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 26.2 3D 原理图预览渲染与 GPU 适配。
 * 使用 Blaze3D PreparedRenderType、PreparedDynamicScene 以及 FeatureRenderDispatcher 提交模型。
 */
public final class QuickLitematicaPreviewAccess {
    private static final QuickLitematicaPreviewLog LOGGER = QuickLitematicaPreviewLog.LOGGER;
    private static final int VERTEX_BYTES = 44;
    private static final float PREVIEW_FIT_PADDING = 0.95F;
    @Nullable public static GpuBufferSlice cachedDynamicTransforms;

    private QuickLitematicaPreviewAccess() {
    }

    public static int getCacheFormatVersion() {
        return 19;
    }

    public static String getCacheRenderMarker() {
        return "quickcraft-model-mesh-v20-api-audit-stable-path-content-resource-signature-dynamic-render-state-mc26.2-ctm-overlay-equipment-v1";
    }

    public static VertexConsumer delegateSetUv3(VertexConsumer consumer, float u, float v) {
        return consumer;
    }

    public static void registerSpecialRenderer() {
        PictureInPictureRendererRegistry.register(context -> new PreviewGuiElementRenderer());
    }

    public static QuickLitematicaPreviewBackend createBackend(QuickLitematicaPreview3D.Preview preview) {
        return new Backend262(preview);
    }

    private static final class PreviewGuiElementRenderer extends PictureInPictureRenderer<QuickLitematicaPreview3D.PreviewGuiElement> {
        private final QuickLitematicaPreview3D.LoadingTexture loadingTexture = new QuickLitematicaPreview3D.LoadingTexture();

        @Override
        public Class<QuickLitematicaPreview3D.PreviewGuiElement> getRenderStateClass() {
            return QuickLitematicaPreview3D.PreviewGuiElement.class;
        }

        @Override
        protected void renderToTexture(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices, SubmitNodeCollector submitNodes) {
            QuickLitematicaPreviewBackend backend = element.preview().backend();
            if (backend instanceof Backend262 backend262) {
                try {
                    backend262.drawSpecial(element, matrices);
                    this.loadingTexture.rendered(element);
                } catch (RuntimeException | Error failure) {
                    element.preview().failBuild(failure);
                }
            }
        }

        @Override
        protected boolean textureIsReadyToBlit(QuickLitematicaPreview3D.PreviewGuiElement element) {
            return element.preview().reuseLoadingTexture(this.loadingTexture, element);
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2.0F;
        }

        @Override
        protected String getTextureLabel() {
            return "quickcraft:schematic_preview";
        }
    }

    private static final class Backend262 extends QuickLitematicaPreviewDynamicBackend implements QuickLitematicaPreviewBackend {
        private final QuickLitematicaPreview3D.Preview preview;
        private final Map<QuickLitematicaPreview3D.LayerKey, List<LayerBuffer>> layerBuffers = new EnumMap<>(QuickLitematicaPreview3D.LayerKey.class);
        private final QuickLitematicaPreviewTransparentOrder transparentOrder = new QuickLitematicaPreviewTransparentOrder();
        private final QuickLitematicaPreviewQuadDepth sortDepth = new QuickLitematicaPreviewQuadDepth();
        private int[] sortOrder = new int[0], sortKeys = new int[0];
        @Nullable
        private ByteBufferBuilder transparentIndexAllocator;
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        private final ProjectionMatrixBuffer dynamicProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft dynamic preview projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        private boolean staticUploadComplete;
        private boolean dynamicPreparationArmed;

        private Backend262(QuickLitematicaPreview3D.Preview preview) {
            this.preview = preview;
        }

        @Override
        public boolean uploadLayer(QuickLitematicaPreview3D.LayerMesh layerMesh) {
            int vertexCount = layerMesh.vertexCount();
            int allocatorSize = allocatorSize(vertexCount);
            ByteBufferBuilder allocator = new ByteBufferBuilder(allocatorSize);
            LayerBuffer uploaded = null;
            GpuBuffer vertexBuffer = null;
            GpuBuffer indexBuffer = null;
            boolean customIndexBuffer = false;
            try {
                RenderType renderLayer = layerMesh.layer().renderLayer();
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.primitiveTopology(), renderLayer.format());
                QuickLitematicaPreviewCache.CacheFile.decodeQuantizedToBuilder(layerMesh.quantizedVertices(), builder);

                com.mojang.blaze3d.vertex.MeshData built = builder.build();
                if (built == null) {
                    return true;
                }

                try {
                    com.mojang.blaze3d.vertex.MeshData.SortState translucentSort = null;
                    if (layerMesh.needsTranslucentSorting()) {
                        try (var phase = QuickLitematicaPreviewLog.phase("GPU层透明排序")) {
                            translucentSort = built.sortQuads(allocator, points -> QuickLitematicaPreviewIndices.initialOrder(points.size()));
                        }
                    }

                    if (translucentSort != null && this.uploadTransparentBatch(built, translucentSort, vertexCount)) return true;

                    long bufferStart = QuickLitematicaPreviewLog.startTimer();
                    var drawParameters = built.drawState();
                    vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "QuickCraft preview vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST
                                    | (layerMesh.needsTranslucentSorting() ? GpuBuffer.USAGE_COPY_SRC : 0),
                            built.vertexBuffer()
                    );
                    customIndexBuffer = built.indexBuffer() != null;
                    indexBuffer = customIndexBuffer
                            ? RenderSystem.getDevice().createBuffer(
                                    () -> "QuickCraft preview indices",
                                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                    built.indexBuffer()
                            )
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).getBuffer(drawParameters.indexCount());
                    IndexType indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).type();

                    uploaded = new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer,
                            translucentSort, translucentSort == null ? null : new QuickLitematicaPreviewQuadDepth());
                    LOGGER.info("GPU 缓冲创建完成：层={}，顶点={}，索引={}，自建索引={}，耗时={} us", layerMesh.layer(), vertexCount,
                            drawParameters.indexCount(), customIndexBuffer, QuickLitematicaPreviewLog.microsSince(bufferStart));
                } finally {
                    built.close();
                }
                this.layerBuffers.computeIfAbsent(layerMesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
                if (layerMesh.layer().isTranslucent()) this.transparentOrder.add(uploaded.translucentSort().centroids());
                return true;
            } catch (Throwable throwable) {
                if (uploaded != null) {
                    uploaded.close();
                } else {
                    if (vertexBuffer != null) {
                        vertexBuffer.close();
                    }
                    // 顺序索引缓冲由原版共享，只有自建索引缓冲随本次上传释放。
                    if (customIndexBuffer && indexBuffer != null) {
                        indexBuffer.close();
                    }
                }
                LOGGER.error("Failed to upload layer mesh for {}", this.preview.sourceName(), throwable);
                return false;
            } finally {
                allocator.close();
            }
        }

        private static int allocatorSize(int vertexCount) {
            long bytes = Math.max(256L, (long) vertexCount * VERTEX_BYTES);
            return (int) Math.min(Integer.MAX_VALUE - 8L, bytes);
        }

        @Override
        public boolean hasBuffers() {
            return !this.layerBuffers.isEmpty();
        }

        @Override
        public boolean isStaticUploadComplete() {
            return this.staticUploadComplete;
        }

        @Override
        public float dynamicPreparationProgress() {
            return this.dynamicContentProgress();
        }

        @Override
        public void setStaticUploadComplete(boolean complete) {
            if (complete && !this.staticUploadComplete) this.mergeTransparentBuffers();
            this.staticUploadComplete = complete;
        }

        private QuickLitematicaPreviewTransparentMerge.Stream transparentUpload;
        private QuickLitematicaPreviewAsyncSort transparentAsyncSort;
        private boolean snapshotRendering;

        private boolean uploadTransparentBatch(com.mojang.blaze3d.vertex.MeshData built, com.mojang.blaze3d.vertex.MeshData.SortState batchSort, int vertexCount) {
            var data = built.vertexBuffer();
            var plan = this.transparentUpload;
            if (plan == null) plan = QuickLitematicaPreviewTransparentMerge.stream(this.preview.meshData().layers(), data.remaining() / vertexCount);
            if (plan == null) return false;
            int batchBytes = data.remaining();
            plan.checkBatch(vertexCount, batchBytes);
            if (this.transparentUpload == null) {
                GpuBuffer vertices = null, indices = null;
                boolean published = false;
                try {
                    var centers = new com.mojang.blaze3d.vertex.CompactVectorArray(plan.vertices / 4);
                    var sort = new com.mojang.blaze3d.vertex.MeshData.SortState(centers, IndexType.INT);
                    vertices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft transparent arena vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, plan.vertexBytes);
                    indices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft transparent arena indices",
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, Math.multiplyExact(plan.indexCount, 4));
                    var rotation = QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(plan.indexCount)
                            ? new QuickLitematicaPreviewIndexBuffers(indices) : null;
                    var combined = new LayerBuffer(vertices, indices, plan.indexCount, IndexType.INT, true, sort,
                            new QuickLitematicaPreviewQuadDepth(), rotation);
                    this.layerBuffers.put(QuickLitematicaPreview3D.LayerKey.TRANSLUCENT, List.of(combined));
                    this.transparentUpload = plan;
                    published = true;
                } finally {
                    if (!published) {
                        if (indices != null) indices.close();
                        if (vertices != null) vertices.close();
                    }
                }
            }
            var buffer = this.layerBuffers.get(QuickLitematicaPreview3D.LayerKey.TRANSLUCENT).getFirst();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.vertexBuffer().slice(plan.byteOffset(), data.remaining()), data);
            var source = batchSort.centroids();
            var target = buffer.translucentSort().centroids();
            for (int i = 0; i < source.size(); i++)
                target.set(plan.quadOffset() + i, source.getX(i), source.getY(i), source.getZ(i));
            plan.advance(vertexCount, batchBytes);
            return true;
        }

        private void mergeTransparentBuffers() {
            if (this.transparentUpload != null) this.transparentUpload.requireComplete();
            List<LayerBuffer> buffers = this.layerBuffers.get(QuickLitematicaPreview3D.LayerKey.TRANSLUCENT);
            if (buffers == null || buffers.size() < 2) return;
            long vertexBytes = 0, quads = 0;
            for (var buffer : buffers) {
                if (buffer.translucentSort() == null) return;
                vertexBytes += buffer.vertexBuffer().size();
                quads += buffer.translucentSort().centroids().size();
            }
            int indexCount = QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(vertexBytes, quads);
            if (indexCount == 0) {
                LOGGER.info("透明合并保留分批：批次={}，顶点字节={}，四边形={}，新增 GPU 预算=256 MiB", buffers.size(), vertexBytes, quads);
                return;
            }
            GpuBuffer vertices = null, indices = null;
            boolean published = false;
            try (var phase = QuickLitematicaPreviewLog.phase("透明 GPU 批次合并")) {
                var centers = QuickLitematicaPreviewTransparentMerge.centers(
                        buffers.stream().map(buffer -> buffer.translucentSort().centroids()).toList());
                var sort = new com.mojang.blaze3d.vertex.MeshData.SortState(centers, IndexType.INT);
                vertices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft merged transparent vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexBytes);
                indices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft merged transparent indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (long) indexCount * 4);
                var encoder = RenderSystem.getDevice().createCommandEncoder();
                long offset = 0;
                for (var buffer : buffers) {
                    long bytes = buffer.vertexBuffer().size();
                    encoder.copyToBuffer(buffer.vertexBuffer().slice(), vertices.slice(offset, bytes));
                    offset += bytes;
                }
                // 索引尚未填充，首次 draw 的 resort 必须运行；中心顺序与拼接后的顶点顺序相同。
                var indexBuffers = QuickLitematicaPreviewTransparentMerge.canRotateIndices(vertexBytes, indexCount)
                        ? new QuickLitematicaPreviewIndexBuffers(indices) : null;
                var combined = new LayerBuffer(vertices, indices, indexCount, IndexType.INT, true, sort,
                        new QuickLitematicaPreviewQuadDepth(), indexBuffers);
                this.layerBuffers.put(QuickLitematicaPreview3D.LayerKey.TRANSLUCENT, List.of(combined));
                this.transparentOrder.clear();
                published = true;
                LOGGER.info("transparent-merge-v1：透明 GPU 批次 {} -> 1，顶点字节={}，索引字节={}，旋转使用单次绘制",
                        buffers.size(), vertexBytes, (long) indexCount * 4);
                LOGGER.info("transparent-stream-v1：索引缓冲槽数={}，索引暂存与排序工作数组按预览复用，含轮换索引的新增 GPU 总预算=256 MiB",
                        indexBuffers == null ? 1 : 3);
            } catch (RuntimeException | OutOfMemoryError failure) {
                LOGGER.warn("透明 GPU 合并失败，保留原分批渲染", failure);
            } finally {
                if (published) buffers.forEach(LayerBuffer::close);
                else {
                    if (vertices != null) vertices.close();
                    if (indices != null) indices.close();
                }
            }
        }

        @Override
        public void clearBuffers() {
            this.layerBuffers.values().forEach(buffers -> buffers.forEach(LayerBuffer::close));
            this.layerBuffers.clear();
            this.transparentUpload = null;
            if (this.transparentAsyncSort != null) { this.transparentAsyncSort.close(); this.transparentAsyncSort = null; }
            this.transparentOrder.clear();
            this.sortOrder = new int[0];
            this.sortKeys = new int[0];
            if (this.transparentIndexAllocator != null) {
                this.transparentIndexAllocator.close();
                this.transparentIndexAllocator = null;
            }
            this.staticUploadComplete = false;
            this.dynamicPreparationArmed = false;
            this.preparedDynamicScene = null;
            this.closeDynamicFrame();
        }



        @Override
        public void close() {
            this.clearBuffers();
            if (this.previewLightingBuffer != null && !this.previewLightingBuffer.isClosed()) {
                this.previewLightingBuffer.close();
            }
            this.preparedDynamicScene = null;
            this.snapshotProjectionBuffer.close();
            this.dynamicProjectionBuffer.close();
        }

        void drawSpecial(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices) {
            if (this.preview.dimensions() == null || this.preview.isCancelled()) return;
            if (QuickLitematicaPreviewLog.enabled()) this.preview.trace.frame(this.staticUploadComplete, () -> this.drawSpecialContent(element, matrices));
            else this.drawSpecialContent(element, matrices);
        }

        private void drawSpecialContent(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices) {
            QuickLitematicaPreview3D.MeshData data = this.preview.meshData();
            QuickLitematicaPreview3D.PreviewDimensions dimensions = this.preview.dimensions();
            if (dimensions == null || this.preview.isCancelled()) {
                return;
            }

            var previousLights = RenderSystem.getShaderLights();
            var colorTarget = Objects.requireNonNull(RenderSystem.outputColorTextureOverride);
            RenderSystem.backupProjectionMatrix();
            try {
                this.setupPreviewProjection(colorTarget.getWidth(0), colorTarget.getHeight(0), element.dragScale());
                matrices.pushPose();
                try {
                    matrices.scale(1.0F, -1.0F, -1.0F);
                    matrices.translate(element.dragX(), -element.dragY(), 0.0F);
                    matrices.mulPose(Axis.XP.rotation(element.pitch()));
                    matrices.mulPose(Axis.YP.rotation((float) element.angle()));
                    float scale = dimensions.scaleFactor(element.size(), element.size()) * element.size() * 0.5F * element.dragScale();
                    matrices.scale(scale, scale, scale);
                    matrices.translate(-dimensions.sizeX() / 2.0F, -dimensions.sizeY() / 2.0F, -dimensions.sizeZ() / 2.0F);
                    Matrix4f dynamicModelView = new Matrix4f(matrices.last().pose());
                    this.applyLight(element.pitch(), element.angle());
                    this.drawBuffers(dynamicModelView, false);
                    if (data != null && this.staticUploadComplete) {
                        if (this.dynamicPreparationArmed) {
                            if (data.hasDynamicContent()) {
                                this.prepareDynamicFrame(data);
                                if (this.dynamicFrame != null) {
                                    this.drawDynamicFrame(dynamicModelView);
                                } else if (this.dynamicFrameProgress() >= 1.0F && this.prepareDynamicStates(data)) {
                                    SubmitNodeStorage fallbackNodes = new SubmitNodeStorage();
                                    if (this.preparedDynamicScene != null) {
                                        this.drawPreparedDynamic(this.preparedDynamicScene, dynamicModelView, element.size(), fallbackNodes);
                                    } else {
                                        this.drawDynamic(data, dynamicModelView, element.size(), fallbackNodes);
                                    }
                                    Minecraft.getInstance().gameRenderer.featureRenderDispatcher().renderAllFeatures(fallbackNodes);
                                }
                            }
                        } else {
                            this.dynamicPreparationArmed = true;
                        }
                    }
                    this.drawBuffers(dynamicModelView, true);
                } finally {
                    matrices.popPose();
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.setShaderLights(previousLights);
            }
        }

        private void setupPreviewProjection(int width, int height, float zoom) {
            float depthRange = Math.max(1000.0F, width * Math.max(1.0F, zoom));
            this.snapshotProjection.setupOrtho(-depthRange, depthRange, width, height, true);
            RenderSystem.setProjectionMatrix(this.snapshotProjectionBuffer.getBuffer(this.snapshotProjection), ProjectionType.ORTHOGRAPHIC);
        }

        private void applyLight(float pitch, double yaw) {
            Matrix4f lightTransform = new Matrix4f().rotateX(pitch).rotateY((float) yaw);
            Vector4f lightDirection = new Vector4f(0.0F, 0.35F, 0.25F, 0.0F);
            lightTransform.invert();
            lightDirection.mul(lightTransform);
            Vector3f transformed = new Vector3f(lightDirection.x, lightDirection.y, lightDirection.z).normalize();

            if (this.previewLightingBuffer == null) {
                this.previewLightingBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "QuickCraft preview lighting",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        Lighting.UBO_SIZE
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                var data = Std140Builder.onStack(stack, Lighting.UBO_SIZE)
                        .putVec3(transformed)
                        .putVec3(transformed)
                        .get();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.previewLightingBuffer.slice(), data);
            }
            RenderSystem.setShaderLights(this.previewLightingBuffer.slice());
        }

        private void drawBuffers(Matrix4f modelView, boolean afterEntities) {
            this.sortDepth.update(modelView);
            for (QuickLitematicaPreview3D.LayerKey layer : QuickLitematicaPreview3D.LayerKey.DRAW_ORDER) {
                if (layer.drawAfterEntities() != afterEntities) {
                    continue;
                }
                if (layer.isTranslucent() && !this.staticUploadComplete) {
                    continue;
                }
                List<LayerBuffer> buffers = this.layerBuffers.get(layer);
                if (buffers == null || buffers.isEmpty()) {
                    continue;
                }
                if (layer.isTranslucent() && buffers.size() > 1) {
                    for (var run : this.transparentOrder.runs(modelView)) {
                        LayerBuffer buffer = buffers.get(run.buffer());
                        this.resort(buffer, modelView);
                        drawLayerBuffer(layer, buffer, modelView, run.firstIndex(), run.indexCount());
                    }
                    continue;
                }
                for (LayerBuffer buffer : buffers) {
                    this.resort(buffer, modelView);
                    drawLayerBuffer(layer, buffer, modelView);
                }
            }
        }

        private int[] sortTransparentQuads(com.mojang.blaze3d.vertex.CompactVectorArray points) {
            if (this.sortOrder.length != points.size()) {
                this.sortOrder = new int[points.size()];
                this.sortKeys = new int[points.size()];
            }
            try (var phase = QuickLitematicaPreviewLog.phase("透明面中心排序")) {
                return this.sortDepth.sort(points, this.sortOrder, this.sortKeys);
            }
        }

        private boolean resortAsync(LayerBuffer buffer, Matrix4f modelView) {
            if (this.snapshotRendering) {
                if (this.transparentAsyncSort != null) { this.transparentAsyncSort.close(); this.transparentAsyncSort = null; }
                return false;
            }
            if (buffer.translucentSort() == null || !buffer.depth().initialized()) return false;
            var points = buffer.translucentSort().centroids();
            var buffers = this.layerBuffers.get(QuickLitematicaPreview3D.LayerKey.TRANSLUCENT);
            if (points.size() < QuickLitematicaPreviewAsyncSort.MIN_QUADS || buffers == null || buffers.size() != 1 || buffers.getFirst() != buffer) return false;
            if (this.transparentAsyncSort == null) {
                this.transparentAsyncSort = new QuickLitematicaPreviewAsyncSort(points.size(), buffer.indexType().bytes, buffer.depth(),
                        (depth, order, keys) -> depth.sort(points, order, keys), this.sortOrder, this.sortKeys);
                // 首次同步排序的工作区交给后台独占；索引暂存也改由后台池持有。
                this.sortOrder = new int[0];
                this.sortKeys = new int[0];
                if (this.transparentIndexAllocator != null) { this.transparentIndexAllocator.close(); this.transparentIndexAllocator = null; }
            }
            var result = this.transparentAsyncSort.poll();
            if (result != null) {
                try (result; var phase = QuickLitematicaPreviewLog.phase("后台透明结果索引上传")) {
                    if (buffer.indexBuffers() != null) buffer.indexBuffers().advance();
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.indexBuffer().slice(), result.indices());
                    buffer.depth().update(result.view);
                }
            }
            this.transparentAsyncSort.request(modelView);
            return true;
        }

        private void resort(LayerBuffer buffer, Matrix4f modelView) {
            if (this.resortAsync(buffer, modelView)) return;
            if (buffer.translucentSort() == null || !buffer.depth().update(modelView)) return;
            // 所有批次在渲染线程串行消费索引，共用一个暂存区，避免强制渲染时按批次累积 CPU 内存。
            if (this.transparentIndexAllocator == null) this.transparentIndexAllocator = new ByteBufferBuilder(256);
            try (var phase = QuickLitematicaPreviewLog.phase("静态透明层按视角重排")) {
                int[] order = this.sortTransparentQuads(buffer.translucentSort().centroids());
                ByteBufferBuilder.Result indices;
                try (var generation = QuickLitematicaPreviewLog.phase("透明三角形索引生成")) {
                    indices = QuickLitematicaPreviewIndices.build(this.transparentIndexAllocator, order, buffer.indexType().bytes);
                }
                try (indices; var upload = QuickLitematicaPreviewLog.phase("透明索引上传")) {
                    if (buffer.indexBuffers() != null) buffer.indexBuffers().advance();
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.indexBuffer().slice(), indices.byteBuffer());
                }
            } finally {
                this.transparentIndexAllocator.clear();
            }
        }

        private static void drawLayerBuffer(QuickLitematicaPreview3D.LayerKey layer, LayerBuffer buffer, Matrix4f modelView) {
            drawLayerBuffer(layer, buffer, modelView, 0, buffer.indexCount());
        }

        private static void drawLayerBuffer(QuickLitematicaPreview3D.LayerKey layer, LayerBuffer buffer, Matrix4f modelView, int firstIndex, int indexCount) {
            RenderType renderLayer = layer.renderLayer();
            Matrix4fStack renderStack = RenderSystem.getModelViewStack();
            renderStack.pushMatrix();
            try {
                renderStack.set(modelView);
                var prepared = renderLayer.prepare();
                var sequentialIndices = buffer.ownsIndexBuffer()
                        ? null
                        : RenderSystem.getSequentialBuffer(renderLayer.primitiveTopology());
                GpuBuffer indexBuffer = buffer.ownsIndexBuffer()
                        ? buffer.indexBuffer()
                        : sequentialIndices.getBuffer(buffer.indexCount());
                IndexType indexType = buffer.ownsIndexBuffer()
                        ? buffer.indexType()
                        : sequentialIndices.type();
                prepared.drawFromBuffer(
                        buffer.vertexBuffer(),
                        indexBuffer,
                        indexType,
                        0,
                        firstIndex,
                        indexCount
                );
            } finally {
                renderStack.popMatrix();
            }
        }








        private void drawDynamicFrame(Matrix4f modelView) {
            FeatureRenderDispatcher.PreparedFrame frame = this.dynamicFrame;
            if (frame == null) {
                return;
            }

            Matrix4f projection = this.snapshotProjection.getMatrix(new Matrix4f()).mul(modelView);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(this.dynamicProjectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            // PreparedRenderType 会把 prepare 时的 DynamicTransforms 切片缓存下来。
            // 原版每帧 endFrame 后旧 UBO 被关掉，复用缓存帧就会偶发崩溃。
            var previousTransforms = cachedDynamicTransforms;
            QuickLitematicaPreview3D.refreshCachedPreviewDynamicTransforms = true;
            try (var phase = QuickLitematicaPreviewLog.phase("动态缓存帧回放 CPU 调用")) {
                this.refreshDynamicFrameTransforms(new Matrix4f());
                // 动态顶点已带各模型变换，预览整体旋转合入投影；未捕获的原生提交使用当帧默认变换。
                cachedDynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
                this.preview.trace.milestone("dynamic-material-transform-v2：缓存帧按材质保留层偏移与纹理变换，仅刷新当帧切片");
                // 只对同材质不透明模型分批；跨批仍按原阶段顺序执行。
                this.forEachDynamicFrame(FeatureRenderDispatcher.PreparedFrame::executeSolid);
                this.forEachDynamicFrame(FeatureRenderDispatcher.PreparedFrame::executeTranslucent);
                this.forEachDynamicFrame(FeatureRenderDispatcher.PreparedFrame::executeTranslucentAfterTerrain);
                this.forEachDynamicFrame(FeatureRenderDispatcher.PreparedFrame::executeAlwaysOnTop);
            } finally {
                cachedDynamicTransforms = previousTransforms;
                QuickLitematicaPreview3D.refreshCachedPreviewDynamicTransforms = false;
                RenderSystem.restoreProjectionMatrix();
            }
        }

        @Override
        public void captureSnapshot(
                int resolution,
                int backgroundColor,
                QuickLitematicaPreview3D.DragState drag,
                QuickLitematicaPreview3D.MeshData data,
                boolean keepBackgroundOpaque,
                Consumer<NativeImage> callback,
                Consumer<Throwable> errorCallback
        ) {
            try (var scope = this.preview.trace.bind(); var phase = QuickLitematicaPreviewLog.phase("离屏渲染/像素读回提交")) {
                this.prepareDynamicFrame(data);
                if (this.dynamicFrame == null && !this.prepareDynamicStates(data)) {
                    throw new IllegalStateException("Dynamic preview is still preparing");
                }

                RenderTarget framebuffer;
                try {
                    framebuffer = new TextureTarget("QuickCraft snapshot", resolution, resolution, true, GpuFormat.RGBA8_UNORM);
                    boolean rendered = false;
                    try {
                        Vector4f clearColor = new Vector4f(
                                ((backgroundColor >> 16) & 0xFF) / 255.0F,
                                ((backgroundColor >> 8) & 0xFF) / 255.0F,
                                (backgroundColor & 0xFF) / 255.0F,
                                ((backgroundColor >>> 24) & 0xFF) / 255.0F
                        );
                        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                                Objects.requireNonNull(framebuffer.getColorTexture()),
                                clearColor,
                                Objects.requireNonNull(framebuffer.getDepthTexture()),
                                0.0D
                        );
                        this.renderSnapshot(framebuffer, data, drag);
                        rendered = true;
                    } finally {
                        if (!rendered) framebuffer.destroyBuffers();
                    }
                } catch (Throwable throwable) {
                    LOGGER.error("离屏渲染失败：分辨率={}", resolution, throwable);
                    errorCallback.accept(throwable);
                    return;
                }

                copySnapshot(framebuffer, keepBackgroundOpaque, this.preview.trace.wrapConsumer(image -> {
                    this.preview.trace.milestone("快照像素读回完成：分辨率={}", resolution);
                    callback.accept(image);
                }), this.preview.trace.wrapConsumer(errorCallback));
            } catch (Throwable failure) {
                errorCallback.accept(failure);
            }
        }

        private void renderSnapshot(RenderTarget framebuffer, QuickLitematicaPreview3D.MeshData data, QuickLitematicaPreview3D.DragState drag) {
            var previousColorTarget = RenderSystem.outputColorTextureOverride;
            var previousDepthTarget = RenderSystem.outputDepthTextureOverride;
            var previousLights = RenderSystem.getShaderLights();
            boolean previousSnapshotMode = this.snapshotRendering;
            RenderSystem.backupProjectionMatrix();
            try {
                RenderSystem.outputColorTextureOverride = framebuffer.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = framebuffer.getDepthTextureView();
                this.setupPreviewProjection(framebuffer.width, framebuffer.height, drag.scale);

                PoseStack matrices = new PoseStack();
                this.snapshotRendering = true;
                matrices.translate(framebuffer.width / 2.0F, framebuffer.height / 2.0F, 0.0F);
                matrices.scale(1.0F, -1.0F, 1.0F);
                float viewportSize = Math.max(1, drag.size);
                matrices.translate(drag.dx * framebuffer.width / viewportSize, -drag.dy * framebuffer.height / viewportSize, 0.0F);
                matrices.mulPose(Axis.XP.rotation(drag.pitch));
                matrices.mulPose(Axis.YP.rotation((float) drag.angle));
                double diagonal = Math.sqrt(
                        (double) data.sizeX() * data.sizeX()
                                + (double) data.sizeY() * data.sizeY()
                                + (double) data.sizeZ() * data.sizeZ()
                );
                float scale = (float) (PREVIEW_FIT_PADDING * framebuffer.width / Math.max(1.0, diagonal)) * drag.scale;
                matrices.scale(scale, scale, scale);
                matrices.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);
                Matrix4f modelView = new Matrix4f(matrices.last().pose());
                this.applyLight(drag.pitch, drag.angle);
                this.drawBuffers(modelView, false);
                if (data.hasDynamicContent()) {
                    if (this.dynamicFrame != null) {
                        this.drawDynamicFrame(modelView);
                    } else {
                        SubmitNodeStorage submitNodes = new SubmitNodeStorage();
                        if (this.preparedDynamicScene != null) {
                            this.drawPreparedDynamic(this.preparedDynamicScene, modelView, framebuffer.width, submitNodes);
                        } else {
                            this.drawDynamic(data, modelView, framebuffer.width, submitNodes);
                        }
                        Minecraft.getInstance().gameRenderer.featureRenderDispatcher().renderAllFeatures(submitNodes);
                    }
                }
                this.drawBuffers(modelView, true);
            } finally {
                this.snapshotRendering = previousSnapshotMode;
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.outputColorTextureOverride = previousColorTarget;
                RenderSystem.outputDepthTextureOverride = previousDepthTarget;
                RenderSystem.setShaderLights(previousLights);
            }
        }

        private static void copySnapshot(
                RenderTarget framebuffer,
                boolean keepBackgroundOpaque,
                Consumer<NativeImage> callback,
                Consumer<Throwable> errorCallback
        ) {
            var resources = new QuickLitematicaPreviewReadback(framebuffer::destroyBuffers);
            try {
                var texture = Objects.requireNonNull(framebuffer.getColorTexture());
                int width = framebuffer.width;
                int height = framebuffer.height;
                int pixelSize = texture.getFormat().blockSize();
                var device = RenderSystem.getDevice();
                GpuBuffer buffer = device.createBuffer(
                        () -> "QuickCraft PNG readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                        width * height * pixelSize
                );
                resources.buffer(buffer::close);
                device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
                    ByteBuffer pixelData;
                    try (resources; var view = buffer.map(true, false)) {
                        var pixels = view.data();
                        pixelData = ByteBuffer.allocate(pixels.remaining()).order(pixels.order());
                        pixelData.put(pixels).flip();
                    } catch (Throwable throwable) {
                        errorCallback.accept(throwable);
                        return;
                    }

                    try {
                        Util.backgroundExecutor().execute(() -> {
                            NativeImage image = null;
                            try {
                                image = new NativeImage(width, height, false);
                                for (int y = 0; y < height; y++) {
                                    for (int x = 0; x < width; x++) {
                                        int color = pixelData.getInt((x + y * width) * pixelSize);
                                        image.setPixelABGR(x, height - y - 1, keepBackgroundOpaque ? color | 0xFF000000 : color);
                                    }
                                }
                                callback.accept(image);
                            } catch (Throwable throwable) {
                                if (image != null) {
                                    image.close();
                                }
                                errorCallback.accept(throwable);
                            }
                        });
                    } catch (Throwable failure) {
                        errorCallback.accept(failure);
                    }
                }, 0);

            } catch (Throwable failure) {
                resources.failed(failure, errorCallback);
            }
        }
    }




    private record LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount,
                               IndexType indexType, boolean ownsIndexBuffer,
                               com.mojang.blaze3d.vertex.MeshData.SortState translucentSort,
                               QuickLitematicaPreviewQuadDepth depth, @Nullable QuickLitematicaPreviewIndexBuffers indexBuffers) implements AutoCloseable {
        LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, IndexType indexType, boolean ownsIndexBuffer,
                    com.mojang.blaze3d.vertex.MeshData.SortState translucentSort, QuickLitematicaPreviewQuadDepth depth) {
            this(vertexBuffer, indexBuffer, indexCount, indexType, ownsIndexBuffer, translucentSort, depth, null);
        }

        LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, IndexType indexType, boolean ownsIndexBuffer) {
            this(vertexBuffer, indexBuffer, indexCount, indexType, ownsIndexBuffer, null, null, null);
        }

        @Override
        public GpuBuffer indexBuffer() {
            return this.indexBuffers == null ? this.indexBuffer : this.indexBuffers.current();
        }

        @Override
        public void close() {
            this.vertexBuffer.close();
            if (this.indexBuffers != null) {
                this.indexBuffers.close();
            } else if (this.ownsIndexBuffer) {
                this.indexBuffer.close();
            }
        }
    }


}
