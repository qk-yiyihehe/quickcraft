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
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreviewAccess.class);
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
        return "quickcraft-model-mesh-v19-api-audit-stable-path-content-resource-signature-dynamic-render-state-mc26.1.2";
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
                backend2612.drawSpecial(element, matrices);
            }
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
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        private List<DynamicLayerBuffer> dynamicBuffers = List.of();
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
            try {
                RenderType renderLayer = layerMesh.layer().renderLayer();
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.mode(), renderLayer.format());
                QuickLitematicaPreview3D.CacheFile.decodeQuantizedToBuilder(layerMesh.quantizedVertices(), builder);

                com.mojang.blaze3d.vertex.MeshData built = builder.build();
                if (built == null) {
                    return true;
                }

                try {
                    if (layerMesh.layer().isTranslucent()) {
                        built.sortQuads(allocator, VertexSorting.byDistance(0.0F, 0.0F, 1000.0F));
                    }

                    var drawParameters = built.drawState();
                    GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "QuickCraft preview vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            built.vertexBuffer()
                    );
                    boolean customIndexBuffer = built.indexBuffer() != null;
                    GpuBuffer indexBuffer = customIndexBuffer
                            ? RenderSystem.getDevice().createBuffer(
                                    () -> "QuickCraft preview indices",
                                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                    built.indexBuffer()
                            )
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).getBuffer(drawParameters.indexCount());
                    VertexFormat.IndexType indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).type();

                    uploaded = new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer);
                    this.layerBuffers.computeIfAbsent(layerMesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
                    return true;
                } finally {
                    built.close();
                }
            } catch (Throwable throwable) {
                if (uploaded != null) {
                    uploaded.close();
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
        public void setStaticUploadComplete(boolean complete) {
            this.staticUploadComplete = complete;
        }

        @Override
        public void clearBuffers() {
            this.layerBuffers.values().forEach(buffers -> buffers.forEach(LayerBuffer::close));
            this.layerBuffers.clear();
            this.staticUploadComplete = false;
            this.dynamicPreparationArmed = false;
            this.closeDynamicBuffers();
        }

        private void closeDynamicBuffers() {
            this.dynamicBuffers.forEach(layerBuffer -> layerBuffer.buffer().close());
            this.dynamicBuffers = List.of();
            this.dynamicBuffersReady = false;
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
                            if (this.dynamicBuffersReady) {
                                this.drawDynamicBuffers(dynamicModelView);
                            } else if (data.hasDynamicContent()) {
                                this.drawDynamic(data, dynamicModelView, element.size());
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
                for (LayerBuffer buffer : buffers) {
                    drawLayerBuffer(layer, buffer, modelView);
                }
            }
        }

        private static void drawLayerBuffer(QuickLitematicaPreview3D.LayerKey layer, LayerBuffer buffer, Matrix4f modelView) {
            drawLayerBuffer(layer.renderLayer(), buffer, modelView);
        }

        private static void drawLayerBuffer(RenderType renderLayer, LayerBuffer buffer, Matrix4f modelView) {
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
                pass.drawIndexed(0, 0, buffer.indexCount(), 1);
            }
        }

        private void drawDynamic(QuickLitematicaPreview3D.MeshData data, Matrix4f modelView, int viewSize) {
            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
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
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }

                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderBlockEntity(client, entity, matrices, queue, cameraState);
                } catch (Throwable ignored) {
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
            renderer.submit(renderState, matrices, queue, cameraState);
        }

        private void prepareDynamicBuffers(QuickLitematicaPreview3D.MeshData data) {
            if (this.dynamicBuffersReady || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.dynamicBuffersReady = true;
                data.closeDynamic();
                return;
            }

            DynamicMeshCollector collector = new DynamicMeshCollector();
            try {
                Minecraft client = Minecraft.getInstance();
                SubmitNodeStorage queue = new SubmitNodeStorage();
                CameraRenderState cameraState = new CameraRenderState();
                PoseStack matrices = new PoseStack();
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
                    scene.blockEntities().forEach((pos, entity) -> {
                        matrices.pushPose();
                        try {
                            matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                            renderBlockEntity(client, entity, matrices, queue, cameraState);
                        } catch (DynamicBufferTooLargeException e) {
                            throw e;
                        } catch (Throwable ignored) {
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
                            client.getEntityRenderDispatcher().submit(
                                    renderState,
                                    cameraState,
                                    renderedEntity.x(),
                                    renderedEntity.y(),
                                    renderedEntity.z(),
                                    matrices,
                                    queue
                            );
                        } catch (DynamicBufferTooLargeException e) {
                            throw e;
                        } catch (Throwable ignored) {
                        }
                    });
                    Matrix4fStack bakeView = RenderSystem.getModelViewStack();
                    bakeView.pushMatrix();
                    try {
                        bakeView.identity();
                        dispatcher.renderAllFeatures();
                    } finally {
                        bakeView.popMatrix();
                    }
                }

                this.dynamicBuffers = collector.upload();
                this.dynamicBuffersReady = true;
                data.closeDynamic();
            } catch (Throwable ignored) {
                this.closeDynamicBuffers();
                this.dynamicBufferFallback = true;
            } finally {
                collector.close();
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
            this.prepareDynamicBuffers(data);
            if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                errorCallback.accept(new IllegalStateException("Dynamic content not ready for snapshot"));
                return;
            }

            RenderTarget framebuffer;
            try {
                framebuffer = new TextureTarget("QuickCraft snapshot", resolution, resolution, true);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        Objects.requireNonNull(framebuffer.getColorTexture()),
                        backgroundColor,
                        Objects.requireNonNull(framebuffer.getDepthTexture()),
                        1.0D
                );
                this.renderSnapshot(framebuffer, data, drag);
            } catch (Throwable throwable) {
                errorCallback.accept(throwable);
                return;
            }

            copySnapshot(framebuffer, keepBackgroundOpaque, callback, errorCallback);
        }

        private void renderSnapshot(RenderTarget framebuffer, QuickLitematicaPreview3D.MeshData data, QuickLitematicaPreview3D.DragState drag) {
            var previousColorTarget = RenderSystem.outputColorTextureOverride;
            var previousDepthTarget = RenderSystem.outputDepthTextureOverride;
            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.outputColorTextureOverride = framebuffer.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = framebuffer.getDepthTextureView();
            RenderSystem.backupProjectionMatrix();
            this.setupPreviewProjection(framebuffer.width, framebuffer.height, drag.scale);

            PoseStack matrices = new PoseStack();
            try {
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
            var mapEncoder = device.createCommandEncoder();
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
                ByteBuffer pixelData;
                try (var view = mapEncoder.mapBuffer(buffer, true, false)) {
                    var pixels = view.data();
                    pixelData = ByteBuffer.allocate(pixels.remaining()).order(pixels.order());
                    pixelData.put(pixels).flip();
                } catch (Throwable throwable) {
                    errorCallback.accept(throwable);
                    return;
                } finally {
                    try {
                        buffer.close();
                    } finally {
                        framebuffer.destroyBuffers();
                    }
                }

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
            }, 0);
        }
    }

    private record DynamicLayerBuffer(RenderType layer, LayerBuffer buffer) {
    }

    private static final class DynamicMeshCollector extends MultiBufferSource.BufferSource implements AutoCloseable {
        private final ByteBufferBuilder fallbackAllocator;
        private final Map<RenderType, DynamicMeshBuilder> sharedBuilders = new LinkedHashMap<>();
        private final List<DynamicMeshBuilder> builders = new ArrayList<>();
        private long allocatedBytes;

        private DynamicMeshCollector() {
            this(new ByteBufferBuilder(256));
        }

        private DynamicMeshCollector(ByteBufferBuilder fallbackAllocator) {
            super(fallbackAllocator, new LinkedHashMap<>());
            this.fallbackAllocator = fallbackAllocator;
        }

        @Override
        public VertexConsumer getBuffer(RenderType layer) {
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
            if (this.builders.size() >= MAX_DYNAMIC_RENDER_LAYERS) {
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
            if (this.allocatedBytes > MAX_DYNAMIC_BUFFER_BYTES) {
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
                               VertexFormat.IndexType indexType, boolean ownsIndexBuffer) implements AutoCloseable {
        private RenderPipeline pipeline(RenderType renderLayer, RenderPipeline defaultPipeline) {
            return defaultPipeline;
        }

        @Override
        public void close() {
            this.vertexBuffer.close();
            if (this.ownsIndexBuffer) {
                this.indexBuffer.close();
            }
        }
    }
}
