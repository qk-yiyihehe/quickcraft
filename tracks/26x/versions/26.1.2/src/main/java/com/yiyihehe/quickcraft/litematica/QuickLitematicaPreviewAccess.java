package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.yiyihehe.quickcraft.mixin.RenderLayerAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * 26.1.2 3D 原理图预览渲染与 GPU 适配。
 * 使用 Blaze3D 早期直接 VBO 管线、RenderSystem.outputColorTextureOverride 与 DynamicMeshCollector。
 */
public final class QuickLitematicaPreviewAccess {
    private static final QuickLitematicaPreviewLog LOGGER = QuickLitematicaPreviewLog.LOGGER;
    private static final Vector3f ZERO_MODEL_OFFSET = new Vector3f();
    private static final int VERTEX_BYTES = 44;
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_DYNAMIC_RENDER_LAYERS = 1_024;
    private static final int DYNAMIC_LAYER_INITIAL_BYTES = 64 * 1024;
    private static final float PREVIEW_FIT_PADDING = 0.95F;

    private QuickLitematicaPreviewAccess() {
    }

    public static int getCacheFormatVersion() {
        return 18;
    }

    public static String getCacheRenderMarker() {
        return "quickcraft-model-mesh-v19-api-audit-stable-path-content-resource-signature-dynamic-render-state-mc26.1.2-ctm-overlay-equipment-v1";
    }

    public static VertexConsumer delegateSetUv3(VertexConsumer consumer, float u, float v) {
        return consumer;
    }

    public static void registerSpecialRenderer() {
        PictureInPictureRendererRegistry.register(context -> new PreviewGuiElementRenderer(context.bufferSource()));
    }

    public static QuickLitematicaPreviewBackend createBackend(QuickLitematicaPreview3D.Preview preview) {
        return new Backend2612(preview);
    }

    private static final class PreviewGuiElementRenderer extends PictureInPictureRenderer<QuickLitematicaPreview3D.PreviewGuiElement> {
        private final QuickLitematicaPreview3D.LoadingTexture loadingTexture = new QuickLitematicaPreview3D.LoadingTexture();

        private PreviewGuiElementRenderer(MultiBufferSource.BufferSource vertexConsumers) {
            super(vertexConsumers);
        }

        @Override
        public Class<QuickLitematicaPreview3D.PreviewGuiElement> getRenderStateClass() {
            return QuickLitematicaPreview3D.PreviewGuiElement.class;
        }

        @Override
        protected void renderToTexture(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices) {
            QuickLitematicaPreviewBackend backend = element.preview().backend();
            if (backend instanceof Backend2612 backend2612) {
                try {
                    backend2612.drawSpecial(element, matrices);
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

    private static final class Backend2612 implements QuickLitematicaPreviewBackend {
        private final QuickLitematicaPreview3D.Preview preview;
        private final Map<QuickLitematicaPreview3D.LayerKey, List<LayerBuffer>> layerBuffers = new EnumMap<>(QuickLitematicaPreview3D.LayerKey.class);
        private final QuickLitematicaPreviewTransparentOrder transparentOrder = new QuickLitematicaPreviewTransparentOrder();
        private final QuickLitematicaPreviewQuadDepth sortDepth = new QuickLitematicaPreviewQuadDepth();
        private int[] sortOrder = new int[0], sortKeys = new int[0];
        @Nullable
        private ByteBufferBuilder transparentIndexAllocator;
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        private final List<DynamicLayerBuffer> dynamicBuffers = new ArrayList<>();
        private QuickLitematicaPreviewDynamicBatch<RenderType> dynamicBuild;
        private QuickLitematicaPreviewDynamicBatch<RenderType> dynamicFullBuild;
        private SubmitNodeStorage dynamicFullQueue;
        private boolean dynamicFullSubmitted;
        private FeatureRenderDispatcher dynamicFullDispatcher;
        private DynamicMeshCollector dynamicFullCollector;
        private QuickLitematicaPreviewQueuedBuild dynamicFullGeometry;
        private boolean dynamicFullGeometryReady;
        private boolean dynamicWarmupComplete;
        private boolean dynamicBatchingDisabled;
        private float dynamicProgress;
        private boolean dynamicBuffersReady;
        private boolean dynamicBufferFallback;
        private boolean staticUploadComplete;
        private boolean dynamicPreparationArmed;

        private Backend2612(QuickLitematicaPreview3D.Preview preview) {
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
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.mode(), renderLayer.format());
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
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).getBuffer(drawParameters.indexCount());
                    VertexFormat.IndexType indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).type();

                    uploaded = new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer,
                            translucentSort, translucentSort == null ? null : new QuickLitematicaPreviewQuadDepth());
                    LOGGER.info("GPU 缓冲创建完成：层={}，顶点={}，索引={}，自建索引={}，耗时={} us", layerMesh.layer(), vertexCount,
                            drawParameters.indexCount(), customIndexBuffer, QuickLitematicaPreviewLog.microsSince(bufferStart));
                    this.layerBuffers.computeIfAbsent(layerMesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
                if (layerMesh.layer().isTranslucent()) this.transparentOrder.add(uploaded.translucentSort().centroids());
                    return true;
                } finally {
                    built.close();
                }
            } catch (Throwable throwable) {
                if (uploaded != null) {
                    uploaded.close();
                } else {
                    // 仅关闭已创建的自有缓冲，不关闭 RenderSystem 的共享索引。
                    if (customIndexBuffer && indexBuffer != null) indexBuffer.close();
                    if (vertexBuffer != null) vertexBuffer.close();
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
        public float dynamicPreparationProgress() {
            return this.dynamicBuffersReady || this.dynamicBufferFallback ? 1.0F : this.dynamicProgress;
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
                    var sort = new com.mojang.blaze3d.vertex.MeshData.SortState(centers, VertexFormat.IndexType.INT);
                    vertices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft transparent arena vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, plan.vertexBytes);
                    indices = RenderSystem.getDevice().createBuffer(() -> "QuickCraft transparent arena indices",
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, Math.multiplyExact(plan.indexCount, 4));
                    var rotation = QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(plan.indexCount)
                            ? new QuickLitematicaPreviewIndexBuffers(indices) : null;
                    var combined = new LayerBuffer(vertices, indices, plan.indexCount, VertexFormat.IndexType.INT, true, sort,
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
            var point = new org.joml.Vector3f();
            for (int i = 0; i < source.size(); i++)
                target.set(plan.quadOffset() + i, source.get(i, point));
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
                var sort = new com.mojang.blaze3d.vertex.MeshData.SortState(centers, VertexFormat.IndexType.INT);
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
                var combined = new LayerBuffer(vertices, indices, indexCount, VertexFormat.IndexType.INT, true, sort,
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
            this.closeDynamicBuffers();
        }

        private void closeDynamicBuffers() {
            this.dynamicBuffers.forEach(layerBuffer -> layerBuffer.buffer().close());
            this.dynamicBuffers.clear();
            this.dynamicBuild = null;
            this.closeDynamicFullQueue();
            this.dynamicWarmupComplete = false;
            this.dynamicBatchingDisabled = false;
            this.dynamicProgress = 0.0F;
            this.dynamicBuffersReady = false;
        }

        private void closeDynamicFullQueue() {
            if (this.dynamicFullGeometry != null) this.dynamicFullGeometry.close();
            var dispatcher = this.dynamicFullDispatcher;
            var collector = this.dynamicFullCollector;
            this.dynamicFullGeometry = null;
            this.dynamicFullDispatcher = null;
            this.dynamicFullCollector = null;
            if (this.dynamicFullQueue != null) this.dynamicFullQueue.clear();
            this.dynamicFullQueue = null;
            this.dynamicFullBuild = null;
            this.dynamicFullSubmitted = false;
            this.dynamicFullGeometryReady = false;
            try {
                if (dispatcher != null) dispatcher.close();
            } finally {
                if (collector != null) collector.close();
            }
        }

        @Override
        public void close() {
            this.clearBuffers();
            if (this.previewLightingBuffer != null && !this.previewLightingBuffer.isClosed()) {
                this.previewLightingBuffer.close();
            }
            this.snapshotProjectionBuffer.close();
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
                            if (!this.dynamicBuffersReady && !this.dynamicBufferFallback && !this.dynamicBatchingDisabled
                                && data.hasDynamicContent() && data.entities().isEmpty()) this.prepareDynamicBuffers(data);
                            if (this.dynamicBuffersReady) {
                                this.drawDynamicBuffers(dynamicModelView);
                            } else if (data.hasDynamicContent() && (this.dynamicBatchingDisabled || this.dynamicBufferFallback || !data.entities().isEmpty())) {
                                // 保留首次实体纹理预热，待完整队列准备时不重复现画整层。
                                if (this.dynamicBufferFallback || (!this.dynamicWarmupComplete && data.dynamicSceneIfReady() != null)) {
                                    this.drawDynamic(data, dynamicModelView, element.size());
                                    this.dynamicWarmupComplete = true;
                                }
                                this.prepareDynamicBuffers(data);
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
            drawLayerBuffer(layer.renderLayer(), buffer, modelView, firstIndex, indexCount);
        }

        private static void drawLayerBuffer(RenderType renderLayer, LayerBuffer buffer, Matrix4f modelView) {
            drawLayerBuffer(renderLayer, buffer, modelView, 0, buffer.indexCount());
        }

        private static void drawLayerBuffer(RenderType renderLayer, LayerBuffer buffer, Matrix4f modelView, int firstIndex, int indexCount) {
            RenderPipeline pipeline = buffer.pipeline(renderLayer, renderLayer.pipeline());
            var colorAttachment = Objects.requireNonNull(RenderSystem.outputColorTextureOverride);
            var depthAttachment = RenderSystem.outputDepthTextureOverride;
            var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                    modelView,
                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                    ZERO_MODEL_OFFSET,
                    new Matrix4f()
            );
            RenderSetup setup = ((RenderLayerAccessor) (Object) renderLayer).quickcraft$getRenderSetup();
            var resolvedTextures = setup.getTextures();
            var sequentialIndices = buffer.ownsIndexBuffer() ? null : RenderSystem.getSequentialBuffer(renderLayer.mode());
            GpuBuffer indexBuffer = buffer.ownsIndexBuffer()
                    ? buffer.indexBuffer()
                    : sequentialIndices.getBuffer(buffer.indexCount());
            VertexFormat.IndexType indexType = buffer.ownsIndexBuffer()
                    ? buffer.indexType()
                    : sequentialIndices.type();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "QuickCraft preview " + renderLayer,
                    colorAttachment,
                    OptionalInt.empty(),
                    depthAttachment,
                    OptionalDouble.empty()
            )) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dynamicTransforms);
                pass.setVertexBuffer(0, buffer.vertexBuffer());
                for (var entry : resolvedTextures.entrySet()) {
                    var texture = entry.getValue();
                    pass.bindTexture(entry.getKey(), texture.textureView(), texture.sampler());
                }
                pass.setIndexBuffer(indexBuffer, indexType);
                pass.drawIndexed(0, firstIndex, indexCount, 1);
            }
        }

        private void drawDynamic(QuickLitematicaPreview3D.MeshData data, Matrix4f modelView, int viewSize) {
            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicSceneIfReady();
            if (scene == null) return;
            if (scene.isEmpty()) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
            PoseStack matrices = new PoseStack();
            matrices.mulPose(modelView);
            CameraRenderState cameraState = new CameraRenderState();
            FeatureRenderDispatcher dispatcher = client.gameRenderer.getFeatureRenderDispatcher();
            SubmitNodeCollector queue = dispatcher.getSubmitNodeStorage();

            scene.blockEntities().forEach((pos, entity) -> {
                if (!(entity instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity)
                        && culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }

                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderBlockEntity(client, entity, matrices, queue, cameraState);
                } catch (Throwable ignored) {
                    LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
                } finally {
                    matrices.popPose();
                }
            });

            scene.entities().forEach(renderedEntity -> {
                if (culler.isOutside((float) renderedEntity.x(), (float) renderedEntity.y(), (float) renderedEntity.z())) {
                    return;
                }

                try {
                    EntityRenderState renderState = client.getEntityRenderDispatcher()
                            .extractEntity(renderedEntity.entity(), 0.0F);
                    renderState.lightCoords = renderedEntity.light();
                    renderState.distanceToCameraSq = 0.0D;
                    client.getEntityRenderDispatcher().submit(
                            renderState,
                            cameraState,
                            renderedEntity.x(),
                            renderedEntity.y(),
                            renderedEntity.z(),
                            matrices,
                            queue
                    );
                } catch (Throwable ignored) {
                    LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
                }
            });

            dispatcher.renderAllFeatures();
        }

        private static <T extends BlockEntity, S extends BlockEntityRenderState> void renderBlockEntity(
                Minecraft client,
                T entity,
                PoseStack matrices,
                SubmitNodeCollector queue,
                CameraRenderState cameraState
        ) {
            @SuppressWarnings("unchecked")
            BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) client.getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return;
            }
            S renderState = renderer.createRenderState();
            renderer.extractRenderState(entity, renderState, 0.0F, net.minecraft.world.phys.Vec3.ZERO, null);
            if (renderState instanceof net.minecraft.client.renderer.blockentity.state.BeaconRenderState beam) {
                // 正交预览的宽度随整个模型缩放，不应按真实世界到相机原点的距离加粗。
                beam.beamRadiusScale = 1.0F;
            }
            renderer.submit(renderState, matrices, queue, cameraState);
        }

        private void prepareDynamicBuffers(QuickLitematicaPreview3D.MeshData data) {
            if (this.dynamicBuffersReady || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            QuickLitematicaPreview3D.DynamicScene fullScene = data.dynamicSceneIfReady();

            if (fullScene == null) return;
            if (fullScene.isEmpty()) {
                this.dynamicBuffersReady = true;
                this.dynamicProgress = 1.0F;
                data.closeDynamic();
                return;
            }
            if (!this.dynamicBatchingDisabled && !fullScene.entities().isEmpty()) this.dynamicBatchingDisabled = true;
            if (!this.dynamicBatchingDisabled && this.dynamicBuild == null && !fullScene.isEmpty()) {
                this.dynamicBuild = new QuickLitematicaPreviewDynamicBatch<>(fullScene);
                LOGGER.info("动态分批准备开始：方块实体={}，实体={}，目标单批={} us，强制渲染={}",
                        fullScene.blockEntities().size(), fullScene.entities().size(), QuickLitematicaPreviewBatchBudget.TARGET_NANOS / 1_000L, data.forceRender);
            }
            var batch = this.dynamicBuild;
            if (batch == null && this.dynamicFullBuild == null) {
                this.dynamicFullBuild = new QuickLitematicaPreviewDynamicBatch<>(fullScene, 16);
                this.dynamicFullQueue = new SubmitNodeStorage();
            }
            QuickLitematicaPreview3D.DynamicScene scene = batch != null ? batch.nextScene()
                    : this.dynamicFullSubmitted ? null : this.dynamicFullBuild.nextScene();
            long batchStart = System.nanoTime();

            boolean fullGeometryOwner = batch == null && this.dynamicFullSubmitted;
            if (fullGeometryOwner && this.dynamicFullCollector == null) this.dynamicFullCollector = new DynamicMeshCollector(data.forceRender);
            DynamicMeshCollector collector = fullGeometryOwner ? this.dynamicFullCollector : new DynamicMeshCollector(data.forceRender);
            collector.batch = batch;
            if (batch != null) collector.allocatedBytes = batch.allocatedBytes;
            try (var phase = QuickLitematicaPreviewLog.phase(batch != null ? "动态单批提交/几何构建/上传"
                    : !this.dynamicFullSubmitted ? "动态完整队列分步提交"
                    : !this.dynamicFullGeometryReady ? "动态完整队列几何切片" : "动态完整队列最终上传")) {
                Minecraft client = Minecraft.getInstance();
                SubmitNodeStorage queue = batch != null ? new SubmitNodeStorage() : this.dynamicFullQueue;
                CameraRenderState cameraState = new CameraRenderState();
                PoseStack matrices = new PoseStack();
                if (scene != null) {
                    scene.blockEntities().forEach((pos, entity) -> {
                        matrices.pushPose();
                        try {
                            matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                            renderBlockEntity(client, entity, matrices, queue, cameraState);
                        } catch (DynamicBufferTooLargeException | QuickLitematicaPreviewDynamicBatch.IncompatibleException e) {
                            throw e;
                        } catch (RuntimeException ignored) {
                            LOGGER.warn("原生预览局部失败：入口=prepareDynamicBuffers，继续原有跳过/回退", ignored);
                        } finally {
                            matrices.popPose();
                        }
                    });

                    scene.entities().forEach(renderedEntity -> {
                        try {
                            EntityRenderState renderState = client.getEntityRenderDispatcher()
                                    .extractEntity(renderedEntity.entity(), 0.0F);
                            renderState.lightCoords = renderedEntity.light();
                            renderState.distanceToCameraSq = 0.0D;
                            // 缓存预览没有主世界轮廓后处理，禁止跨帧向游戏的 outline provider 写入。
                            renderState.outlineColor = 0;
                            client.getEntityRenderDispatcher().submit(
                                    renderState,
                                    cameraState,
                                    renderedEntity.x(),
                                    renderedEntity.y(),
                                    renderedEntity.z(),
                                    matrices,
                                    queue
                            );
                        } catch (DynamicBufferTooLargeException | QuickLitematicaPreviewDynamicBatch.IncompatibleException e) {
                            throw e;
                        } catch (RuntimeException ignored) {
                            LOGGER.warn("原生预览局部失败：入口=prepareDynamicBuffers，继续原有跳过/回退", ignored);
                        }
                    });
                }
                if (batch == null && !this.dynamicFullSubmitted) {
                    this.dynamicFullSubmitted = this.dynamicFullBuild.finishCollected(System.nanoTime() - batchStart);
                    this.dynamicProgress = 0.5F * this.dynamicFullBuild.progress();
                    return;
                }
                if (batch == null) {
                    if (this.dynamicFullDispatcher == null) {
                        this.dynamicFullDispatcher = new FeatureRenderDispatcher(
                            queue,
                            client.getModelManager(),
                            collector,
                            client.getAtlasManager(),
                            client.renderBuffers().outlineBufferSource(),
                            collector,
                            client.font,
                            client.gameRenderer.getGameRenderState()
                        );
                        this.dynamicFullGeometry = new QuickLitematicaPreviewQueuedBuild((QuickLitematicaPreviewQueuedBuild.DispatcherBridge) this.dynamicFullDispatcher, collector);
                        return;
                    }
                    if (!this.dynamicFullGeometryReady) {
                        Matrix4fStack bakeView = RenderSystem.getModelViewStack();
                        bakeView.pushMatrix();
                        try {
                            bakeView.identity();
                            this.dynamicFullGeometryReady = this.dynamicFullGeometry.advance();
                        } finally {
                            bakeView.popMatrix();
                        }
                        if (this.dynamicFullGeometryReady) this.dynamicProgress = 0.9F;
                        return;
                    }
                } else {
                    try (FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(
                            queue,
                            client.getModelManager(),
                            collector,
                            client.getAtlasManager(),
                            client.renderBuffers().outlineBufferSource(),
                            collector,
                            client.font,
                            client.gameRenderer.getGameRenderState()
                    )) {
                        Matrix4fStack bakeView = RenderSystem.getModelViewStack();
                        bakeView.pushMatrix();
                        try {
                            bakeView.identity();
                            dispatcher.renderAllFeatures();
                        } finally {
                            bakeView.popMatrix();
                        }
                    }
                }

                var uploaded = collector.upload();
                try {
                    this.dynamicBuffers.addAll(uploaded);
                } catch (RuntimeException | Error failure) {
                    uploaded.forEach(layerBuffer -> layerBuffer.buffer().close());
                    throw failure;
                }
                if (batch == null || batch.finish(collector.allocatedBytes, System.nanoTime() - batchStart)) {
                    if (batch == null) LOGGER.info("dynamic-queued-full-v1：提交切片={}，几何切片={}，最终统一上传，顶点字节={}",
                            this.dynamicFullBuild.batches, this.dynamicFullGeometry.slices, collector.allocatedBytes);
                    this.dynamicBuffersReady = true;
                    this.dynamicBuild = null;
                    this.closeDynamicFullQueue();
                    this.dynamicProgress = 1.0F;
                    data.closeDynamic();
                    LOGGER.info("动态缓存准备全部完成：分批={}，批次数={}，缓冲数={}",
                            batch != null, batch == null ? 1 : batch.batches, this.dynamicBuffers.size());
                } else this.dynamicProgress = batch.progress();
            } catch (QuickLitematicaPreviewDynamicBatch.IncompatibleException incompatible) {
                this.closeDynamicBuffers();
                this.dynamicBatchingDisabled = true;
                LOGGER.info("动态分批回退：包含其他材质/透明或特殊内容，撤销未发布批次，保留完整帧原顺序");
            } catch (RuntimeException ignored) {
                LOGGER.warn("原生预览局部失败：入口=prepareDynamicBuffers，继续原有跳过/回退", ignored);
                this.closeDynamicBuffers();
                if (batch != null) this.dynamicBatchingDisabled = true;
                else this.dynamicBufferFallback = true;
            } catch (Error failure) {
                this.closeDynamicBuffers();
                throw failure;
            } finally {
                if (!fullGeometryOwner) collector.close();
            }
        }

        private void drawDynamicBuffers(Matrix4f modelView) {
            for (DynamicLayerBuffer dynamicBuffer : this.dynamicBuffers) {
                drawLayerBuffer(dynamicBuffer.layer(), dynamicBuffer.buffer(), modelView);
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
                this.prepareDynamicBuffers(data);
                if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                    errorCallback.accept(new IllegalStateException("Dynamic content not ready for snapshot"));
                    return;
                }

                RenderTarget framebuffer;
                try {
                    framebuffer = new TextureTarget("QuickCraft snapshot", resolution, resolution, true);
                    boolean rendered = false;
                    try {
                        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                                Objects.requireNonNull(framebuffer.getColorTexture()),
                                backgroundColor,
                                Objects.requireNonNull(framebuffer.getDepthTexture()),
                                1.0D
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
                this.drawDynamicBuffers(modelView);
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
                int pixelSize = texture.getFormat().pixelSize();
                var device = RenderSystem.getDevice();
                GpuBuffer buffer = device.createBuffer(
                        () -> "QuickCraft PNG readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                        width * height * pixelSize
                );
                resources.buffer(buffer::close);
                var mapEncoder = device.createCommandEncoder();
                device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
                    ByteBuffer pixelData;
                    try (resources; var view = mapEncoder.mapBuffer(buffer, true, false)) {
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
                                        int index = (y * width + x) * 4;
                                        int red = pixelData.get(index) & 0xFF;
                                        int green = pixelData.get(index + 1) & 0xFF;
                                        int blue = pixelData.get(index + 2) & 0xFF;
                                        int alpha = keepBackgroundOpaque ? 0xFF : (pixelData.get(index + 3) & 0xFF);
                                        int argb = (alpha << 24) | (blue << 16) | (green << 8) | red;
                                        image.setPixelABGR(x, height - y - 1, argb);
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

    private record DynamicLayerBuffer(RenderType layer, LayerBuffer buffer) {
    }

    private static final class DynamicMeshCollector extends MultiBufferSource.BufferSource implements AutoCloseable {
        private final ByteBufferBuilder fallbackAllocator;
        private final Map<RenderType, DynamicMeshBuilder> sharedBuilders = new LinkedHashMap<>();
        private final List<DynamicMeshBuilder> builders = new ArrayList<>();
        private final boolean forceRender;
        private long allocatedBytes;
        private QuickLitematicaPreviewDynamicBatch<RenderType> batch;

        private DynamicMeshCollector(boolean forceRender) {
            this(new ByteBufferBuilder(256), forceRender);
        }

        private DynamicMeshCollector(ByteBufferBuilder fallbackAllocator, boolean forceRender) {
            super(fallbackAllocator, new LinkedHashMap<>());
            this.fallbackAllocator = fallbackAllocator;
            this.forceRender = forceRender;
        }

        @Override
        public VertexConsumer getBuffer(RenderType layer) {
            if (this.batch != null) this.batch.checkLayer(layer,
                    layer == net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutCull(net.minecraft.client.renderer.Sheets.CHEST_SHEET)
                            && !layer.sortOnUpload());
            DynamicMeshBuilder meshBuilder = !layer.canConsolidateConsecutiveGeometry()
                    ? this.createBuilder(layer)
                    : this.sharedBuilders.computeIfAbsent(layer, this::createBuilder);
            int vertexBytes = layer.format().getVertexSize();
            if (layer.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES) {
                vertexBytes *= 2;
            }
            return new LimitedVertexConsumer(meshBuilder.builder(), this, vertexBytes);
        }

        private DynamicMeshBuilder createBuilder(RenderType layer) {
            if (!this.forceRender && this.builders.size() >= MAX_DYNAMIC_RENDER_LAYERS) {
                throw new DynamicBufferTooLargeException();
            }
            int initialBytes = !layer.canConsolidateConsecutiveGeometry()
                    ? 256
                    : Math.max(256, Math.min(layer.bufferSize(), DYNAMIC_LAYER_INITIAL_BYTES));
            DynamicMeshBuilder meshBuilder = new DynamicMeshBuilder(layer, new ByteBufferBuilder(initialBytes));
            this.builders.add(meshBuilder);
            return meshBuilder;
        }

        private void reserve(int bytes) {
            this.allocatedBytes += bytes;
            if (!this.forceRender && this.allocatedBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                throw new DynamicBufferTooLargeException();
            }
        }

        private List<DynamicLayerBuffer> upload() {
            List<DynamicLayerBuffer> uploaded = new ArrayList<>();
            try {
                for (DynamicMeshBuilder meshBuilder : this.builders) {
                    try (com.mojang.blaze3d.vertex.MeshData built = meshBuilder.builder().build()) {
                        if (built == null) {
                            continue;
                        }
                        if (meshBuilder.layer().sortOnUpload()) {
                            built.sortQuads(meshBuilder.allocator(), VertexSorting.byDistance(0.0F, 0.0F, 1000.0F));
                        }
                        uploaded.add(new DynamicLayerBuffer(meshBuilder.layer(), uploadBuiltBuffer(built)));
                    }
                }
                return List.copyOf(uploaded);
            } catch (Throwable throwable) {
                uploaded.forEach(layerBuffer -> layerBuffer.buffer().close());
                throw throwable;
            }
        }

        private static LayerBuffer uploadBuiltBuffer(com.mojang.blaze3d.vertex.MeshData built) {
            var drawParameters = built.drawState();
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "QuickCraft dynamic preview vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    built.vertexBuffer()
            );
            boolean customIndexBuffer = built.indexBuffer() != null;
            GpuBuffer indexBuffer = null;
            try {
                indexBuffer = customIndexBuffer
                        ? RenderSystem.getDevice().createBuffer(
                                () -> "QuickCraft dynamic preview indices",
                                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                built.indexBuffer()
                        )
                        : RenderSystem.getSequentialBuffer(drawParameters.mode()).getBuffer(drawParameters.indexCount());
                var indexType = customIndexBuffer
                        ? drawParameters.indexType()
                        : RenderSystem.getSequentialBuffer(drawParameters.mode()).type();
                return new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer);
            } catch (Throwable throwable) {
                vertexBuffer.close();
                if (customIndexBuffer && indexBuffer != null) {
                    indexBuffer.close();
                }
                throw throwable;
            }
        }

        @Override
        public void endBatch() {
        }

        @Override
        public void endLastBatch() {
        }

        @Override
        public void endBatch(RenderType layer) {
        }

        @Override
        public void close() {
            this.builders.forEach(meshBuilder -> meshBuilder.allocator().close());
            this.builders.clear();
            this.sharedBuilders.clear();
            this.fallbackAllocator.close();
        }
    }

    private record DynamicMeshBuilder(RenderType layer, ByteBufferBuilder allocator, BufferBuilder builder) {
        private DynamicMeshBuilder(RenderType layer, ByteBufferBuilder allocator) {
            this(layer, allocator, new BufferBuilder(allocator, layer.mode(), layer.format()));
        }
    }

    private static final class LimitedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final DynamicMeshCollector collector;
        private final int vertexBytes;

        private LimitedVertexConsumer(VertexConsumer delegate, DynamicMeshCollector collector, int vertexBytes) {
            this.delegate = delegate;
            this.collector = collector;
            this.vertexBytes = vertexBytes;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.collector.reserve(this.vertexBytes);
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            this.delegate.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            this.delegate.setLineWidth(width);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.collector.reserve(this.vertexBytes);
            this.delegate.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    private static final class DynamicBufferTooLargeException extends RuntimeException {
    }

    private record LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount,
                               VertexFormat.IndexType indexType, boolean ownsIndexBuffer,
                               com.mojang.blaze3d.vertex.MeshData.SortState translucentSort,
                               QuickLitematicaPreviewQuadDepth depth, @Nullable QuickLitematicaPreviewIndexBuffers indexBuffers) implements AutoCloseable {
        LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, VertexFormat.IndexType indexType, boolean ownsIndexBuffer,
                    com.mojang.blaze3d.vertex.MeshData.SortState translucentSort, QuickLitematicaPreviewQuadDepth depth) {
            this(vertexBuffer, indexBuffer, indexCount, indexType, ownsIndexBuffer, translucentSort, depth, null);
        }

        LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, VertexFormat.IndexType indexType, boolean ownsIndexBuffer) {
            this(vertexBuffer, indexBuffer, indexCount, indexType, ownsIndexBuffer, null, null, null);
        }

        private RenderPipeline pipeline(RenderType renderLayer, RenderPipeline defaultPipeline) {
            return defaultPipeline;
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
