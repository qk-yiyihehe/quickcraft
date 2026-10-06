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
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.yiyihehe.quickcraft.mixin.QuickPictureInPictureRendererAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Util;
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
 * 使用 RenderPearl 管线、独立 RenderPass，静态网格与动态内容绘制到同一张 PIP 纹理。
 */
public final class QuickLitematicaPreviewAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreviewAccess.class);
    private static final int VERTEX_BYTES = 44;
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

    private static final class PreviewGuiElementRenderer extends PictureInPictureRenderer<QuickLitematicaPreview3D.PreviewGuiElement> {
        @Override
        public Class<QuickLitematicaPreview3D.PreviewGuiElement> getRenderStateClass() {
            return QuickLitematicaPreview3D.PreviewGuiElement.class;
        }

        @Override
        protected void renderToTexture(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices, SubmitNodeCollector submitNodes) {
            QuickLitematicaPreviewBackend backend = element.preview().backend();
            if (backend instanceof Backend263 backend263) {
                QuickPictureInPictureRendererAccessor accessor = (QuickPictureInPictureRendererAccessor) (Object) this;
                backend263.drawSpecial(element, matrices, accessor.quickcraft$getTextureView(), accessor.quickcraft$getDepthTextureView());
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

    private static final class Backend263 extends QuickLitematicaPreviewDynamicBackend implements QuickLitematicaPreviewBackend {
        private final QuickLitematicaPreview3D.Preview preview;
        private final Map<QuickLitematicaPreview3D.LayerKey, List<LayerBuffer>> layerBuffers = new EnumMap<>(QuickLitematicaPreview3D.LayerKey.class);
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        private boolean staticUploadComplete;
        private boolean dynamicPreparationArmed;

        private Backend263(QuickLitematicaPreview3D.Preview preview) {
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
                    if (layerMesh.layer().isTranslucent()) {
                        try (var phase = QuickLitematicaPreviewLog.phase("GPU层透明排序")) {
                            built.sortQuads(allocator, VertexSorting.byDistance(0.0F, 0.0F, 1000.0F));
                        }
                    }

                    long bufferStart = QuickLitematicaPreviewLog.startTimer();
                    var drawParameters = built.drawState();
                    vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "QuickCraft preview vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
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

                    uploaded = new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer);
                    LOGGER.info("GPU 缓冲创建完成：层={}，顶点={}，索引={}，自建索引={}，耗时={} us", layerMesh.layer(), vertexCount,
                            drawParameters.indexCount(), customIndexBuffer, QuickLitematicaPreviewLog.microsSince(bufferStart));
                } finally {
                    built.close();
                }
                this.layerBuffers.computeIfAbsent(layerMesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
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



        @Override
        public void close() {
            this.clearBuffers();
            if (this.previewLightingBuffer != null && !this.previewLightingBuffer.isClosed()) {
                this.previewLightingBuffer.close();
            }
            this.preparedDynamicScene = null;
            this.snapshotProjectionBuffer.close();
        }

        void drawSpecial(QuickLitematicaPreview3D.PreviewGuiElement element, PoseStack matrices,
                         GpuTextureView colorView, GpuTextureView depthView) {
            QuickLitematicaPreview3D.MeshData data = this.preview.meshData();
            QuickLitematicaPreview3D.PreviewDimensions dimensions = this.preview.dimensions();
            if (dimensions == null || this.preview.isCancelled()) {
                return;
            }

            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.backupProjectionMatrix();
            try {
                this.setupPreviewProjection(colorView.getWidth(0), colorView.getHeight(0), element.dragScale());
                this.applyLight(element.pitch(), element.angle());
                matrices.pushPose();
                Matrix4f modelView;
                try {
                    matrices.scale(1.0F, -1.0F, -1.0F);
                    matrices.translate(element.dragX(), -element.dragY(), 0.0F);
                    matrices.rotate(Axis.XP, element.pitch());
                    matrices.rotate(Axis.YP, (float) element.angle());
                    float scale = dimensions.scaleFactor(element.size(), element.size()) * element.size() * 0.5F * element.dragScale();
                    matrices.scale(scale, scale, scale);
                    matrices.translate(-dimensions.sizeX() / 2.0F, -dimensions.sizeY() / 2.0F, -dimensions.sizeZ() / 2.0F);
                    modelView = new Matrix4f(matrices.last().pose());
                } finally {
                    matrices.popPose();
                }

                // RenderPearl 的 pass 打开后不能上传纹理或缓冲；静态层和动态帧都在此之前准备。
                List<LayerDraw> layers = this.prepareBuffers(modelView);
                try (FeatureRenderDispatcher.PreparedFrame frame = this.prepareCurrentDynamicFrame(data, modelView, element.size(), this.dynamicPreparationArmed);
                     RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                             () -> "QuickCraft preview", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    drawBuffers(layers, false, pass);
                    if (frame != null) {
                        FeatureRenderDispatcher.renderAllFeatures(pass, frame);
                    }
                    drawBuffers(layers, true, pass);
                    this.dynamicPreparationArmed = true;
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.setShaderLights(previousLights);
            }
        }

        @Nullable
        private FeatureRenderDispatcher.PreparedFrame prepareCurrentDynamicFrame(
                @Nullable QuickLitematicaPreview3D.MeshData data, Matrix4f modelView, int viewSize, boolean armed) {
            if (data == null || !this.staticUploadComplete || !data.hasDynamicContent()) {
                return null;
            }
            this.prepareDynamicStates(data);
            if (!armed) {
                return null;
            }
            SubmitNodeStorage submitNodes = new SubmitNodeStorage();
            if (this.preparedDynamicScene != null) {
                this.drawPreparedDynamic(this.preparedDynamicScene, modelView, viewSize, submitNodes);
            } else {
                this.drawDynamic(data, modelView, viewSize, submitNodes);
            }
            // 借用游戏 dispatcher 的池化帧，用完必须 close；不持有或关闭游戏 dispatcher。
            return Minecraft.getInstance().gameRenderer.featureRenderDispatcher().prepareFrame(submitNodes);
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

        private List<LayerDraw> prepareBuffers(Matrix4f modelView) {
            List<LayerDraw> draws = new ArrayList<>();
            Matrix4fStack renderStack = RenderSystem.getModelViewStack();
            renderStack.pushMatrix();
            try {
                renderStack.set(modelView);
                for (QuickLitematicaPreview3D.LayerKey layer : QuickLitematicaPreview3D.LayerKey.DRAW_ORDER) {
                    if (layer.isTranslucent() && !this.staticUploadComplete) {
                        continue;
                    }
                    List<LayerBuffer> buffers = this.layerBuffers.get(layer);
                    if (buffers == null || buffers.isEmpty()) {
                        continue;
                    }
                    RenderType renderLayer = layer.renderLayer();
                    PreparedRenderType prepared = renderLayer.prepare();
                    for (LayerBuffer buffer : buffers) {
                        var sequentialIndices = buffer.ownsIndexBuffer()
                                ? null : RenderSystem.getSequentialBuffer(renderLayer.primitiveTopology());
                        if (sequentialIndices != null) {
                            // 扩容会关闭旧索引缓冲；先确保容量，绘制时由原版读取当前共享缓冲。
                            sequentialIndices.getBuffer(buffer.indexCount());
                        }
                        StagedVertexBuffer.ExecuteInfo info = new StagedVertexBuffer.ExecuteInfo(
                                buffer.vertexBuffer(), buffer.ownsIndexBuffer() ? buffer.indexBuffer() : null,
                                buffer.ownsIndexBuffer() ? buffer.indexType() : sequentialIndices.type(),
                                0, 0, buffer.indexCount(), renderLayer.primitiveTopology());
                        draws.add(new LayerDraw(prepared, info, layer.drawAfterEntities()));
                    }
                }
            } finally {
                renderStack.popMatrix();
            }
            return draws;
        }

        private static void drawBuffers(List<LayerDraw> layers, boolean afterEntities, RenderPass pass) {
            for (LayerDraw layer : layers) {
                if (layer.afterEntities() == afterEntities) {
                    layer.renderType().drawFromBuffer(layer.info(), pass);
                }
            }
        }

        private record LayerDraw(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info, boolean afterEntities) {
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
                this.prepareDynamicStates(data);

                RenderTarget framebuffer = null;
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
                    if (framebuffer != null) {
                        framebuffer.destroyBuffers();
                    }
                    LOGGER.error("离屏渲染失败：分辨率={}", resolution, throwable);
                    errorCallback.accept(throwable);
                    return;
                }

                copySnapshot(framebuffer, keepBackgroundOpaque, this.preview.trace.wrapConsumer(image -> {
                    this.preview.trace.milestone("快照像素读回完成：分辨率={}", resolution);
                    callback.accept(image);
                }), this.preview.trace.wrapConsumer(errorCallback));
            }
        }

        private void renderSnapshot(RenderTarget framebuffer, QuickLitematicaPreview3D.MeshData data, QuickLitematicaPreview3D.DragState drag) {
            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.backupProjectionMatrix();
            PoseStack matrices = new PoseStack();
            try {
                this.setupPreviewProjection(framebuffer.width, framebuffer.height, drag.scale);
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

                List<LayerDraw> layers = this.prepareBuffers(modelView);
                try (FeatureRenderDispatcher.PreparedFrame frame = this.prepareCurrentDynamicFrame(data, modelView, framebuffer.width, true);
                     RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                             () -> "QuickCraft preview snapshot", framebuffer.getColorTextureView(), Optional.empty(),
                             framebuffer.getDepthTextureView(), OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    drawBuffers(layers, false, pass);
                    if (frame != null) {
                        FeatureRenderDispatcher.renderAllFeatures(pass, frame);
                    }
                    drawBuffers(layers, true, pass);
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
