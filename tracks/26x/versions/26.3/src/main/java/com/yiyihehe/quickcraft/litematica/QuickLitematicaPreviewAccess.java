package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.yiyihehe.quickcraft.mixin.LitematicaFeatureRenderDispatcherAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaStagedVertexBufferAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * 26.3 3D 原理图预览渲染与 GPU 适配。
 * 使用 RenderPearl 管线、RenderPass 命令回调以及 QuickLitematicaPictureInPictureRenderPass。
 */
public final class QuickLitematicaPreviewAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreviewAccess.class);
    private static final int VERTEX_BYTES = 44;
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;
    private static final float PREVIEW_FIT_PADDING = 0.95F;

    private QuickLitematicaPreviewAccess() {
    }

    public static int getCacheFormatVersion() {
        return 19;
    }

    public static String getCacheRenderMarker() {
        return "quickcraft-model-mesh-v20-api-audit-stable-path-content-resource-signature-dynamic-render-state-mc26.3-ctm-overlay-equipment-v1";
    }

    public static VertexConsumer delegateSetUv3(VertexConsumer consumer, float u, float v) {
        return consumer.setUv3(u, v);
    }

    public static void registerSpecialRenderer() {
        PictureInPictureRendererRegistry.register(context -> new PreviewGuiElementRenderer());
    }

    public static QuickLitematicaPreviewBackend createBackend(QuickLitematicaPreview3D.Preview preview) {
        return new Backend263(preview);
    }

    private static final class PreviewGuiElementRenderer extends PictureInPictureRenderer<QuickLitematicaPreview3D.PreviewGuiElement>
            implements QuickLitematicaPictureInPictureRenderPass {
        @Nullable
        private QuickLitematicaPreview3D.PreviewGuiElement pendingElement;

        @Override
        public Class<QuickLitematicaPreview3D.PreviewGuiElement> getRenderStateClass() {
            return QuickLitematicaPreview3D.PreviewGuiElement.class;
        }

        @Override
        protected void renderToTexture(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices, SubmitNodeCollector submitNodes) {
            this.pendingElement = element;
            QuickLitematicaPreviewBackend backend = element.preview().backend();
            if (backend instanceof Backend263 backend263) {
                backend263.drawSpecial(element, matrices, submitNodes);
            }
        }

        @Override
        public void quickcraft$renderFeatures(RenderPass renderPass, FeatureRenderDispatcher.PreparedFrame frame) {
            QuickLitematicaPreview3D.PreviewGuiElement element = this.pendingElement;
            this.pendingElement = null;
            if (element == null) {
                FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
                return;
            }
            QuickLitematicaPreviewBackend backend = element.preview().backend();
            if (backend instanceof Backend263 backend263) {
                backend263.renderPreparedPip(renderPass, frame);
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

    private static final class Backend263 implements QuickLitematicaPreviewBackend {
        private final QuickLitematicaPreview3D.Preview preview;
        private final Map<QuickLitematicaPreview3D.LayerKey, List<LayerBuffer>> layerBuffers = new EnumMap<>(QuickLitematicaPreview3D.LayerKey.class);
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        private final ProjectionMatrixBuffer dynamicProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft dynamic preview projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        @Nullable
        private PreparedDynamicScene preparedDynamicScene;
        @Nullable
        private StagedVertexBuffer dynamicStagedVertexBuffer;
        @Nullable
        private FeatureRenderDispatcher dynamicDispatcher;
        @Nullable
        private FeatureRenderDispatcher.PreparedFrame dynamicFrame;
        private boolean dynamicBufferFallback;
        private boolean dynamicStateFallback;
        private boolean staticUploadComplete;
        private boolean dynamicPreparationArmed;

        @Nullable
        private Matrix4f pendingPipModelView;
        @Nullable
        private GpuBufferSlice pendingPipDynamicProjection;
        @Nullable
        private GpuBufferSlice pendingPipPreviousLights;
        private boolean pendingPipProjectionBackup;
        private boolean pendingPipUseCurrentFrame;

        private Backend263(QuickLitematicaPreview3D.Preview preview) {
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
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.primitiveTopology(), renderLayer.format());
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
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).getBuffer(drawParameters.indexCount());
                    IndexType indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).type();

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
            this.closeDynamicFrame();
        }

        private void closeDynamicFrame() {
            FeatureRenderDispatcher.PreparedFrame frame = this.dynamicFrame;
            FeatureRenderDispatcher dispatcher = this.dynamicDispatcher;
            StagedVertexBuffer stagedBuffer = this.dynamicStagedVertexBuffer;
            this.dynamicFrame = null;
            this.dynamicDispatcher = null;
            this.dynamicStagedVertexBuffer = null;
            closeQuietly(frame);
            closeQuietly(dispatcher);
            closeQuietly(stagedBuffer);
        }

        private static void closeQuietly(@Nullable AutoCloseable resource) {
            if (resource == null) {
                return;
            }
            try {
                resource.close();
            } catch (Exception ignored) {
            }
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

        void drawSpecial(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices, SubmitNodeCollector submitNodes) {
            QuickLitematicaPreview3D.MeshData data = this.preview.meshData();
            QuickLitematicaPreview3D.PreviewDimensions dimensions = this.preview.dimensions();
            if (dimensions == null || this.preview.isCancelled()) {
                return;
            }

            this.pendingPipPreviousLights = RenderSystem.getShaderLights();
            RenderSystem.backupProjectionMatrix();
            this.pendingPipProjectionBackup = true;
            try {
                int targetSize = Math.max(1, element.size() * Minecraft.getInstance().getWindow().getGuiScale());
                this.setupPreviewProjection(targetSize, targetSize, element.dragScale());
                matrices.pushPose();
                try {
                    matrices.scale(1.0F, -1.0F, -1.0F);
                    matrices.translate(element.dragX(), -element.dragY(), 0.0F);
                    matrices.rotate(Axis.XP, element.pitch());
                    matrices.rotate(Axis.YP, (float) element.angle());
                    float scale = dimensions.scaleFactor(element.size(), element.size()) * element.size() * 0.5F * element.dragScale();
                    matrices.scale(scale, scale, scale);
                    matrices.translate(-dimensions.sizeX() / 2.0F, -dimensions.sizeY() / 2.0F, -dimensions.sizeZ() / 2.0F);
                    Matrix4f dynamicModelView = new Matrix4f(matrices.last().pose());
                    this.pendingPipModelView = dynamicModelView;
                    this.pendingPipDynamicProjection = this.dynamicFrame == null
                            ? null
                            : this.prepareDynamicProjection(dynamicModelView);
                    this.applyLight(element.pitch(), element.angle());
                    if (data != null && this.staticUploadComplete) {
                        if (this.dynamicPreparationArmed) {
                            if (data.hasDynamicContent()) {
                                if (this.dynamicFrame == null) {
                                    this.prepareDynamicStates(data);
                                    if (this.preparedDynamicScene != null) {
                                        this.drawPreparedDynamic(this.preparedDynamicScene, dynamicModelView, element.size(), submitNodes);
                                    } else {
                                        this.drawDynamic(data, dynamicModelView, element.size(), submitNodes);
                                    }
                                    this.pendingPipUseCurrentFrame = true;
                                    this.prepareDynamicFrame(data);
                                }
                            }
                        } else {
                            this.dynamicPreparationArmed = true;
                        }
                    }
                } finally {
                    matrices.popPose();
                }
            } catch (Throwable throwable) {
                this.finishPipRenderState();
                throw throwable;
            }
        }

        void renderPreparedPip(RenderPass renderPass, FeatureRenderDispatcher.PreparedFrame currentFrame) {
            Matrix4f modelView = this.pendingPipModelView;
            if (modelView == null) {
                FeatureRenderDispatcher.renderAllFeatures(renderPass, currentFrame);
                return;
            }

            try {
                this.drawBuffers(modelView, false, renderPass);
                if (this.dynamicFrame != null && !this.pendingPipUseCurrentFrame) {
                    this.drawDynamicFrame(renderPass, this.pendingPipDynamicProjection);
                } else {
                    FeatureRenderDispatcher.renderAllFeatures(renderPass, currentFrame);
                }
                this.drawBuffers(modelView, true, renderPass);
            } finally {
                this.finishPipRenderState();
            }
        }

        private void finishPipRenderState() {
            this.pendingPipModelView = null;
            this.pendingPipDynamicProjection = null;
            if (this.pendingPipProjectionBackup) {
                RenderSystem.restoreProjectionMatrix();
                this.pendingPipProjectionBackup = false;
            }
            RenderSystem.setShaderLights(this.pendingPipPreviousLights);
            this.pendingPipPreviousLights = null;
            this.pendingPipUseCurrentFrame = false;
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

        private void drawBuffers(Matrix4f modelView, boolean afterEntities, RenderPass renderPass) {
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
                    drawLayerBuffer(layer, buffer, modelView, renderPass);
                }
            }
        }

        private static void drawLayerBuffer(QuickLitematicaPreview3D.LayerKey layer, LayerBuffer buffer, Matrix4f modelView, RenderPass renderPass) {
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
                StagedVertexBuffer.ExecuteInfo info = new StagedVertexBuffer.ExecuteInfo(
                        buffer.vertexBuffer(),
                        buffer.ownsIndexBuffer() ? indexBuffer : null,
                        indexType,
                        0,
                        0,
                        buffer.indexCount(),
                        renderLayer.primitiveTopology()
                );
                prepared.drawFromBuffer(info, renderPass);
            } finally {
                renderStack.popMatrix();
            }
        }

        private GpuBufferSlice prepareDynamicProjection(Matrix4f modelView) {
            Matrix4f projection = this.snapshotProjection.getMatrix(new Matrix4f()).mul(modelView);
            return this.dynamicProjectionBuffer.getBuffer(projection);
        }

        private void drawDynamicFrame(RenderPass renderPass, @Nullable GpuBufferSlice projectionBuffer) {
            FeatureRenderDispatcher.PreparedFrame frame = this.dynamicFrame;
            if (frame == null || projectionBuffer == null) {
                return;
            }

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projectionBuffer, ProjectionType.ORTHOGRAPHIC);
            QuickLitematicaPreview3D.refreshCachedPreviewDynamicTransforms = true;
            try {
                frame.executeSolid(renderPass);
                frame.executeTranslucent(renderPass);
                frame.executeTranslucentAfterTerrain(renderPass);
                frame.executeAlwaysOnTop(renderPass);
            } finally {
                QuickLitematicaPreview3D.refreshCachedPreviewDynamicTransforms = false;
                RenderSystem.restoreProjectionMatrix();
            }
        }

        private void prepareDynamicStates(QuickLitematicaPreview3D.MeshData data) {
            if (this.preparedDynamicScene != null || this.dynamicStateFallback || !data.hasDynamicContent()) {
                return;
            }

            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.preparedDynamicScene = PreparedDynamicScene.EMPTY;
                data.closeDynamic();
                return;
            }

            try {
                Minecraft client = Minecraft.getInstance();
                List<PreparedBlockEntity> blockEntities = new ArrayList<>();
                scene.blockEntities().forEach((pos, entity) -> {
                    try {
                        PreparedBlockEntity prepared = prepareBlockEntity(client, pos, entity);
                        if (prepared != null) {
                            blockEntities.add(prepared);
                        }
                    } catch (Throwable ignored) {
                    }
                });

                List<PreparedEntity> entities = new ArrayList<>();
                scene.entities().forEach(renderedEntity -> {
                    try {
                        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(renderedEntity.entity(), 0.0F);
                        renderState.lightCoords = renderedEntity.light();
                        renderState.distanceToCameraSq = 0.0D;
                        entities.add(new PreparedEntity(renderState, renderedEntity.x(), renderedEntity.y(), renderedEntity.z()));
                    } catch (Throwable ignored) {
                    }
                });

                this.preparedDynamicScene = new PreparedDynamicScene(List.copyOf(blockEntities), List.copyOf(entities));
                data.closeDynamic();
            } catch (Throwable ignored) {
                this.preparedDynamicScene = null;
                this.dynamicStateFallback = true;
            }
        }

        @Nullable
        private static <T extends BlockEntity, S extends BlockEntityRenderState> PreparedBlockEntity prepareBlockEntity(
                Minecraft client,
                BlockPos pos,
                T entity
        ) {
            @SuppressWarnings("unchecked")
            BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) client.getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return null;
            }
            S renderState = renderer.createRenderState();
            renderer.extractRenderState(entity, renderState, 0.0F, Vec3.ZERO, null);
            return new PreparedBlockEntity(pos, renderer, renderState);
        }

        private void drawPreparedDynamic(
                PreparedDynamicScene scene,
                Matrix4f modelView,
                int viewSize,
                SubmitNodeCollector submitNodes
        ) {
            if (scene.isEmpty()) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
            PoseStack matrices = new PoseStack();
            matrices.mulPose(modelView);
            CameraRenderState cameraState = new CameraRenderState();

            scene.blockEntities().forEach(prepared -> {
                BlockPos pos = prepared.pos();
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }
                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    submitPreparedBlockEntity(prepared, matrices, submitNodes, cameraState);
                } catch (Throwable ignored) {
                } finally {
                    matrices.popPose();
                }
            });

            scene.entities().forEach(prepared -> {
                if (culler.isOutside((float) prepared.x(), (float) prepared.y(), (float) prepared.z())) {
                    return;
                }
                try {
                    client.getEntityRenderDispatcher().submit(
                            prepared.state(),
                            cameraState,
                            prepared.x(),
                            prepared.y(),
                            prepared.z(),
                            matrices,
                            submitNodes
                    );
                } catch (Throwable ignored) {
                }
            });
        }

        private static <T extends BlockEntity, S extends BlockEntityRenderState> void submitPreparedBlockEntity(
                PreparedBlockEntity prepared,
                PoseStack matrices,
                SubmitNodeCollector submitNodes,
                CameraRenderState cameraState
        ) {
            @SuppressWarnings("unchecked")
            BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) prepared.renderer();
            @SuppressWarnings("unchecked")
            S renderState = (S) prepared.renderState();
            renderer.submit(renderState, matrices, submitNodes, cameraState);
        }

        private void drawDynamic(QuickLitematicaPreview3D.MeshData data, Matrix4f modelView, int viewSize, SubmitNodeCollector submitNodes) {
            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
            PoseStack matrices = new PoseStack();
            matrices.mulPose(modelView);
            CameraRenderState cameraState = new CameraRenderState();

            scene.blockEntities().forEach((pos, entity) -> {
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }

                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderBlockEntity(client, entity, matrices, submitNodes, cameraState);
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
                            submitNodes
                    );
                } catch (Throwable ignored) {
                }
            });
        }

        private static <T extends BlockEntity, S extends BlockEntityRenderState> void renderBlockEntity(
                Minecraft client,
                T entity,
                PoseStack matrices,
                SubmitNodeCollector submitNodes,
                CameraRenderState cameraState
        ) {
            @SuppressWarnings("unchecked")
            BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) client.getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return;
            }
            S renderState = renderer.createRenderState();
            renderer.extractRenderState(entity, renderState, 0.0F, Vec3.ZERO, null);
            renderer.submit(renderState, matrices, submitNodes, cameraState);
        }

        private void prepareDynamicFrame(QuickLitematicaPreview3D.MeshData data) {
            if (this.dynamicFrame != null || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.dynamicBufferFallback = true;
                this.preparedDynamicScene = PreparedDynamicScene.EMPTY;
                data.closeDynamic();
                return;
            }

            StagedVertexBuffer stagedBuffer = null;
            FeatureRenderDispatcher dispatcher = null;
            FeatureRenderDispatcher.PreparedFrame frame = null;
            try {
                Minecraft client = Minecraft.getInstance();
                SubmitNodeStorage submitNodes = new SubmitNodeStorage();
                PoseStack matrices = new PoseStack();
                CameraRenderState cameraState = new CameraRenderState();

                scene.blockEntities().forEach((pos, entity) -> {
                    matrices.pushPose();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entity, matrices, submitNodes, cameraState);
                    } catch (Throwable ignored) {
                    } finally {
                        matrices.popPose();
                    }
                });

                scene.entities().forEach(renderedEntity -> {
                    try {
                        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(renderedEntity.entity(), 0.0F);
                        renderState.lightCoords = renderedEntity.light();
                        renderState.distanceToCameraSq = 0.0D;
                        client.getEntityRenderDispatcher().submit(
                                renderState,
                                cameraState,
                                renderedEntity.x(),
                                renderedEntity.y(),
                                renderedEntity.z(),
                                matrices,
                                submitNodes
                        );
                    } catch (Throwable ignored) {
                    }
                });

                stagedBuffer = new StagedVertexBuffer(() -> "QuickCraft preview dynamic", 4 * 1024 * 1024);
                dispatcher = new FeatureRenderDispatcher(
                        client.gameRenderer.renderBuffers(),
                        client.getModelManager(),
                        client.getAtlasManager(),
                        client.font,
                        client.gameRenderer.gameRenderState()
                );
                ((LitematicaFeatureRenderDispatcherAccessor) (Object) dispatcher)
                        .quickcraft$setStagedVertexBuffer(stagedBuffer);

                Matrix4fStack renderStack = RenderSystem.getModelViewStack();
                renderStack.pushMatrix();
                try {
                    renderStack.identity();
                    frame = dispatcher.prepareFrame(submitNodes);
                } finally {
                    renderStack.popMatrix();
                }

                LitematicaStagedVertexBufferAccessor stagedAccessor =
                        (LitematicaStagedVertexBufferAccessor) (Object) stagedBuffer;
                long vertexBytes = stagedAccessor.quickcraft$getCurrentVertexBuffer() == null
                        ? 0L
                        : stagedAccessor.quickcraft$getCurrentVertexBuffer().size();
                long indexBytes = stagedAccessor.quickcraft$getCurrentIndexBuffer() == null
                        ? 0L
                        : stagedAccessor.quickcraft$getCurrentIndexBuffer().size();
                if (vertexBytes + indexBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                    throw new IllegalStateException("Dynamic preview buffer exceeds limit");
                }

                stagedAccessor.quickcraft$getStagingBuffer().close();
                this.closeDynamicFrame();
                this.dynamicStagedVertexBuffer = stagedBuffer;
                this.dynamicDispatcher = dispatcher;
                this.dynamicFrame = frame;
                data.closeDynamic();
            } catch (Throwable e) {
                this.dynamicBufferFallback = true;
                closeQuietly(frame);
                closeQuietly(dispatcher);
                closeQuietly(stagedBuffer);
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
            this.prepareDynamicFrame(data);
            if (this.dynamicFrame == null) {
                this.prepareDynamicStates(data);
            }

            RenderTarget framebuffer;
            try {
                framebuffer = new TextureTarget("QuickCraft snapshot", resolution, resolution, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
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
            } catch (Throwable throwable) {
                errorCallback.accept(throwable);
                return;
            }

            copySnapshot(framebuffer, keepBackgroundOpaque, callback, errorCallback);
        }

        private void renderSnapshot(RenderTarget framebuffer, QuickLitematicaPreview3D.MeshData data, QuickLitematicaPreview3D.DragState drag) {
            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.backupProjectionMatrix();
            this.setupPreviewProjection(framebuffer.width, framebuffer.height, drag.scale);

            PoseStack matrices = new PoseStack();
            try {
                matrices.translate(framebuffer.width / 2.0F, framebuffer.height / 2.0F, 0.0F);
                matrices.scale(1.0F, -1.0F, 1.0F);
                float viewportSize = Math.max(1, drag.size);
                matrices.translate(drag.dx * framebuffer.width / viewportSize, -drag.dy * framebuffer.height / viewportSize, 0.0F);
                matrices.rotate(Axis.XP, drag.pitch);
                matrices.rotate(Axis.YP, (float) drag.angle);
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

                SubmitNodeStorage submitNodes = new SubmitNodeStorage();
                if (data.hasDynamicContent() && this.dynamicFrame == null) {
                    if (this.preparedDynamicScene != null) {
                        this.drawPreparedDynamic(this.preparedDynamicScene, modelView, framebuffer.width, submitNodes);
                    } else {
                        this.drawDynamic(data, modelView, framebuffer.width, submitNodes);
                    }
                }
                GpuBufferSlice dynamicProjection = this.dynamicFrame == null
                        ? null
                        : this.prepareDynamicProjection(modelView);

                FeatureRenderDispatcher dispatcher = Minecraft.getInstance().gameRenderer.featureRenderDispatcher();
                try (
                        FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(submitNodes);
                        RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                                () -> "QuickCraft preview snapshot",
                                framebuffer.getColorTextureView(),
                                Optional.empty(),
                                framebuffer.getDepthTextureView(),
                                OptionalDouble.empty()
                        )
                ) {
                    RenderSystem.bindDefaultUniforms(renderPass);
                    this.drawBuffers(modelView, false, renderPass);
                    if (this.dynamicFrame != null) {
                        this.drawDynamicFrame(renderPass, dynamicProjection);
                    } else {
                        FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
                    }
                    this.drawBuffers(modelView, true, renderPass);
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
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
            int pixelSize = texture.getFormat().blockSize();
            var device = RenderSystem.getDevice();
            GpuBuffer buffer = device.createBuffer(
                    () -> "QuickCraft PNG readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    width * height * pixelSize
            );
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
                ByteBuffer pixelData;
                try (var view = buffer.map(true, false)) {
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
            }, 0);
        }
    }

    private record PreparedDynamicScene(List<PreparedBlockEntity> blockEntities, List<PreparedEntity> entities) {
        private static final PreparedDynamicScene EMPTY = new PreparedDynamicScene(List.of(), List.of());

        private boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }
    }

    private record PreparedBlockEntity(
            BlockPos pos,
            BlockEntityRenderer<?, ?> renderer,
            BlockEntityRenderState renderState
    ) {
    }

    private record PreparedEntity(
            EntityRenderState state,
            double x,
            double y,
            double z
    ) {
    }

    private record LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount,
                               IndexType indexType, boolean ownsIndexBuffer) implements AutoCloseable {
        @Override
        public void close() {
            this.vertexBuffer.close();
            if (this.ownsIndexBuffer) {
                this.indexBuffer.close();
            }
        }
    }
}
