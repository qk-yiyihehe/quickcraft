package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.CacheFile;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.updateDigest;

import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.VertexSorter;
import com.yiyihehe.quickcraft.mixin.RenderLayerMultiPhaseAccessor;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.render.RenderUtils;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.renderer.v1.render.BlockVertexConsumerProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.RawProjectionMatrix;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;

/** 此 API 时期的顶点、世界模型和 GPU 渲染适配。 */
final class QuickLitematicaPreviewAccess {

    // v16：API 审查后的 1.21 渲染语义重新建缓存，避免旧 v15 网格被误读。
    // v15：缓存文件固定绑定投影路径，内容哈希和材质包签名只决定是否原地重建。
    // v14：UV 恢复 float32，并保留完整 light 坐标，避免斜视面跨出方块图集 sprite 边界。
    // v13：回退箱子静态化（entity atlas 纹理与方块 VBO 不兼容，紫色方块）；保留 GZIP+量化+视口剔除+邻居登记修复。
    // v12：箱子顶点静态化到独立 VBO，缓存追加 chestVertices 字段。
    // v11：保留 v10 的 GZIP + 顶点量化；箱子方块实体改回动态渲染，避免 chest atlas 被写进方块 VBO。
    // v17：传送门、流体和普通半透明模型拆分缓存，避免玻璃遮挡传送门及流体断层。
    // 升版本会让旧缓存一次性失效；之后 mod 版本号变化不再清缓存（token 已不含 mod 版本）。
    static final int CACHE_FORMAT_VERSION = 17;
    static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v16-api-audit-stable-path-content-resource-signature-mc1.21.8-entity-center-block-position-seed-ctm-overlay-equipment-v1";
    // 静态顶点磁盘编码：12B 位置 + 4B 颜色 + 8B UV(float32×2) + 2B overlay + 4B lightmap + 2B 法线(octahedral) = 32B。
    static final int QUANTIZED_VERTEX_BYTES = 32;
    static final AtomicBoolean SPECIAL_RENDERER_REGISTERED = new AtomicBoolean();

    public static void registerSpecialRenderer() {
        if (SPECIAL_RENDERER_REGISTERED.compareAndSet(false, true)) {
            SpecialGuiElementRegistry.register(context -> new PreviewGuiElementRenderer(context.vertexConsumers()));
        }
    }

    public static boolean isShaderPackActive() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return (boolean) apiClass.getMethod("isShaderPackInUse").invoke(api);
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable throwable) {
            if (SHADER_API_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shader state could not be queried; disabling QuickCraft 3D previews for this session", throwable);
            }
            return true;
        }
    }

    static boolean isSupportedLitematic(DirectoryEntry entry) {
        return Files.isRegularFile(entry.getFullPath()) && FileType.fromFile(entry.getFullPath()) == FileType.LITEMATICA_SCHEMATIC;
    }
    static final class NativePreview extends Preview {
        final RawProjectionMatrix previewProjection = new RawProjectionMatrix("QuickCraft preview projection");

        @Nullable
        GpuBuffer previewLightingBuffer;

        final Map<LayerKey, List<LayerBuffer>> layerBuffers = new EnumMap<>(LayerKey.class);

        List<DynamicLayerBuffer> dynamicBuffers = List.of();

        void drawPreview(DrawContext context, int x, int y, int size, DragState drag) {
            context.state.addSpecialElement(new PreviewGuiElement(
                            this,
                            x,
                            y,
                            size,
                            drag.dx,
                            drag.dy,
                            drag.angle,
                            drag.pitch,
                            drag.scale,
                            context.scissorStack.peekLast()
                    ));
        }

        @Nullable
        static LayerBuffer uploadLayer(LayerMesh layerMesh) {
            int vertexCount = layerMesh.vertexCount();
            int allocatorSize = allocatorSize(vertexCount);
            BufferAllocator allocator = new BufferAllocator(allocatorSize);
            try {
                RenderLayer renderLayer = layerMesh.layer().renderLayer();
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.getDrawMode(), renderLayer.getVertexFormat());
                CacheFile.decodeQuantizedToBuilder(layerMesh.quantizedVertices(), builder);

                BuiltBuffer built = builder.endNullable();
                if (built == null) {
                    return null;
                }

                try {
                    if (layerMesh.layer().isTranslucent()) {
                        built.sortQuads(allocator, VertexSorter.byDistance(0.0F, 0.0F, 1000.0F));
                    }

                    var drawParameters = built.getDrawParameters();
                    GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "QuickCraft preview vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            built.getBuffer()
                    );
                    boolean customIndexBuffer = built.getSortedBuffer() != null;
                    GpuBuffer indexBuffer = customIndexBuffer
                            ? RenderSystem.getDevice().createBuffer(
                                    () -> "QuickCraft preview indices",
                                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                    built.getSortedBuffer()
                            )
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).getIndexBuffer(drawParameters.indexCount());
                    var indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.mode()).getIndexType();
                    return new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer);
                } finally {
                    built.close();
                }
            } finally {
                allocator.close();
            }
        }

        static int allocatorSize(int vertexCount) {
            long bytes = Math.max(256L, (long) vertexCount * VERTEX_BYTES);
            return (int) Math.min(Integer.MAX_VALUE - 8L, bytes);
        }

        void drawSpecial(PreviewGuiElement element) {
            MeshData data = this.meshData;
            PreviewDimensions dimensions = this.dimensions;
            if (dimensions == null || this.cancelled.get()) {
                return;
            }

            var previousLights = RenderSystem.getShaderLights();
            Matrix4f projection = new Matrix4f().setOrtho(-1.0F, 1.0F, -1.0F, 1.0F, -1000.0F, 3000.0F);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(this.previewProjection.set(projection), ProjectionType.ORTHOGRAPHIC);

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            try {
                modelView.identity();
                modelView.translate(
                        2.0F * element.dragX() / Math.max(1, element.size()),
                        -2.0F * element.dragY() / Math.max(1, element.size()),
                        0.0F
                );
                modelView.rotate(RotationAxis.POSITIVE_X.rotation(element.pitch()));
                modelView.rotate(RotationAxis.POSITIVE_Y.rotation((float) element.angle()));
                float scale = dimensions.scaleFactor(element.size(), element.size()) * element.dragScale();
                modelView.scale(scale, scale, scale);
                modelView.translate(-dimensions.sizeX() / 2.0F, -dimensions.sizeY() / 2.0F, -dimensions.sizeZ() / 2.0F);
                this.applyLight(modelView);
                Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
                this.drawSnapshotBuffers(framebuffer, false);
                if (data != null && this.staticUploadComplete) {
                    if (this.dynamicPreparationArmed) {
                        this.prepareDynamicBuffers(data);
                        if (this.dynamicBuffersReady) {
                            this.drawSnapshotDynamicBuffers(framebuffer);
                        } else if (this.dynamicBufferFallback) {
                            this.drawDynamicUnculled(data);
                        }
                    } else {
                        this.dynamicPreparationArmed = true;
                    }
                }
                this.drawSnapshotBuffers(framebuffer, true);
            } finally {
                modelView.popMatrix();
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.setShaderLights(previousLights);
            }
        }

        void drawDynamicUnculled(MeshData data) {
            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            MatrixStack matrices = new MatrixStack();
            scene.blockEntities().forEach((pos, entity) -> {
                matrices.push();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderBlockEntity(client, entity, matrices, client.getBufferBuilders().getEntityVertexConsumers());
                } catch (Throwable ignored) {
                } finally {
                    matrices.pop();
                }
            });

            scene.entities().forEach(entity -> {
                try {
                    client.getEntityRenderDispatcher().render(
                            entity.entity(),
                            entity.x(),
                            entity.y(),
                            entity.z(),
                            0.0F,
                            matrices,
                            client.getBufferBuilders().getEntityVertexConsumers(),
                            entity.light()
                    );
                } catch (Throwable ignored) {
                }
            });
            this.flushDynamic();
        }

        // 1.21.6+ 的地形明暗已烘焙进顶点颜色；独立 UBO 只修正动态方块实体和实体，且不污染原版全局光照。
        void applyLight(Matrix4f viewMatrix) {
            Matrix4f lightTransform = new Matrix4f(viewMatrix);
            Vector4f lightDirection = new Vector4f(0.0F, 0.35F, 0.25F, 0.0F);
            lightTransform.invert();
            lightDirection.mul(lightTransform);
            Vector3f transformed = new Vector3f(lightDirection.x, lightDirection.y, lightDirection.z);

            if (this.previewLightingBuffer == null) {
                this.previewLightingBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "QuickCraft preview lighting",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        DiffuseLighting.UBO_SIZE
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                var data = Std140Builder.onStack(stack, DiffuseLighting.UBO_SIZE)
                        .putVec3(transformed)
                        .putVec3(transformed)
                        .get();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.previewLightingBuffer.slice(), data);
            }
            RenderSystem.setShaderLights(this.previewLightingBuffer.slice());
        }

        static void drawLayerBuffer(
                RenderLayer renderLayer,
                LayerBuffer buffer,
                Framebuffer framebuffer
        ) {
            renderLayer.startDrawing();
            try {
                RenderLayerMultiPhaseAccessor layerAccessor = (RenderLayerMultiPhaseAccessor) (Object) renderLayer;
                RenderPipeline pipeline = buffer.pipeline(renderLayer, layerAccessor.quickcraft$getPipeline());
                var target = framebuffer;
                var colorAttachment = RenderSystem.outputColorTextureOverride != null
                        ? RenderSystem.outputColorTextureOverride
                        : target.getColorAttachmentView();
                var depthAttachment = target.useDepthAttachment
                        ? RenderSystem.outputDepthTextureOverride != null
                                ? RenderSystem.outputDepthTextureOverride
                                : target.getDepthAttachmentView()
                        : null;
                var dynamicTransforms = RenderSystem.getDynamicUniforms().write(
                        RenderSystem.getModelViewMatrix(),
                        new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                        RenderSystem.getModelOffset(),
                        RenderSystem.getTextureMatrix(),
                        RenderSystem.getShaderLineWidth()
                );
                // ShapeIndexBuffer 扩容会关闭旧 GPU buffer，非自有索引不能跨帧缓存其引用。
                var sequentialIndices = buffer.ownsIndexBuffer() ? null : RenderSystem.getSequentialBuffer(renderLayer.getDrawMode());
                GpuBuffer indexBuffer = buffer.ownsIndexBuffer()
                        ? buffer.indexBuffer()
                        : sequentialIndices.getIndexBuffer(buffer.indexCount());
                VertexFormat.IndexType indexType = buffer.ownsIndexBuffer()
                        ? buffer.indexType()
                        : sequentialIndices.getIndexType();
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
                    for (int textureUnit = 0; textureUnit < 12; textureUnit++) {
                        var texture = RenderSystem.getShaderTexture(textureUnit);
                        if (texture != null) {
                            pass.bindSampler("Sampler" + textureUnit, texture);
                        }
                    }
                    pass.setIndexBuffer(indexBuffer, indexType);
                    pass.drawIndexed(0, 0, buffer.indexCount(), 1);
                }
            } finally {
                renderLayer.endDrawing();
            }
        }

        void prepareDynamicBuffers(MeshData data) {
            if (this.dynamicBuffersReady || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.dynamicBuffersReady = true;
                data.closeDynamic();
                return;
            }

            DynamicMeshCollector collector = new DynamicMeshCollector();
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                MatrixStack matrices = new MatrixStack();
                scene.blockEntities().forEach((pos, entity) -> {
                    matrices.push();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entity, matrices, collector);
                    } catch (DynamicBufferTooLargeException e) {
                        throw e;
                    } catch (Throwable ignored) {
                    } finally {
                        matrices.pop();
                    }
                });

                scene.entities().forEach(entity -> {
                    try {
                        client.getEntityRenderDispatcher().render(
                                entity.entity(),
                                entity.x(),
                                entity.y(),
                                entity.z(),
                                0.0F,
                                matrices,
                                collector,
                                entity.light()
                        );
                    } catch (DynamicBufferTooLargeException e) {
                        throw e;
                    } catch (Throwable ignored) {
                    }
                });

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

        void drawSnapshotBuffers(Framebuffer framebuffer, boolean afterEntities) {
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
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
                    drawLayerBuffer(layer.renderLayer(), buffer, framebuffer);
                }
            }
        }

        void drawSnapshotDynamicBuffers(Framebuffer framebuffer) {
            for (DynamicLayerBuffer layerBuffer : this.dynamicBuffers) {
                drawLayerBuffer(layerBuffer.layer(), layerBuffer.buffer(), framebuffer);
            }
        }

        void renderSnapshot(Framebuffer framebuffer, MeshData data, DragState drag) {
            var previousColorTarget = RenderSystem.outputColorTextureOverride;
            var previousDepthTarget = RenderSystem.outputDepthTextureOverride;
            var previousScissor = RenderSystem.getScissorStateForRenderTypeDraws();
            boolean hadScissor = previousScissor.method_72091();
            int previousScissorX = previousScissor.method_72092();
            int previousScissorY = previousScissor.method_72093();
            int previousScissorWidth = previousScissor.method_72094();
            int previousScissorHeight = previousScissor.method_72095();
            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.outputColorTextureOverride = framebuffer.getColorAttachmentView();
            RenderSystem.outputDepthTextureOverride = framebuffer.getDepthAttachmentView();
            RenderSystem.disableScissorForRenderTypeDraws();

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    this.previewProjection.set(new Matrix4f().setOrtho(-1.0F, 1.0F, -1.0F, 1.0F, -1000.0F, 3000.0F)),
                    ProjectionType.ORTHOGRAPHIC
            );

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            try {
                modelView.identity();
                float viewportSize = Math.max(1, drag.size);
                modelView.translate(2.0F * drag.dx / viewportSize, -2.0F * drag.dy / viewportSize, 0.0F);
                modelView.rotate(RotationAxis.POSITIVE_X.rotation(drag.pitch));
                modelView.rotate(RotationAxis.POSITIVE_Y.rotation((float) drag.angle));
                double diagonal = Math.sqrt(
                        (double) data.sizeX() * data.sizeX()
                                + (double) data.sizeY() * data.sizeY()
                                + (double) data.sizeZ() * data.sizeZ()
                );
                float scale = (float) (2.0 * PREVIEW_FIT_PADDING / Math.max(1.0, diagonal)) * drag.scale;
                modelView.scale(scale, scale, scale);
                modelView.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);

                this.applyLight(modelView);
                this.drawSnapshotBuffers(framebuffer, false);
                if (this.dynamicBuffersReady) {
                    this.drawSnapshotDynamicBuffers(framebuffer);
                }
                this.drawSnapshotBuffers(framebuffer, true);
            } finally {
                modelView.popMatrix();
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.outputColorTextureOverride = previousColorTarget;
                RenderSystem.outputDepthTextureOverride = previousDepthTarget;
                RenderSystem.setShaderLights(previousLights);
                if (hadScissor) {
                    RenderSystem.enableScissorForRenderTypeDraws(previousScissorX, previousScissorY, previousScissorWidth, previousScissorHeight);
                } else {
                    RenderSystem.disableScissorForRenderTypeDraws();
                }
            }
        }

        static void copySnapshot(
                Framebuffer framebuffer,
                boolean keepBackgroundOpaque,
                Consumer<NativeImage> callback,
                Consumer<Throwable> errorCallback
        ) {
            var texture = Objects.requireNonNull(framebuffer.getColorAttachment());
            int width = framebuffer.textureWidth;
            int height = framebuffer.textureHeight;
            int pixelSize = texture.getFormat().pixelSize();
            var device = RenderSystem.getDevice();
            GpuBuffer buffer = device.createBuffer(
                    () -> "QuickCraft PNG readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    width * height * pixelSize
            );
            var mapEncoder = device.createCommandEncoder();
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
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
                        framebuffer.delete();
                    }
                }

                Util.getMainWorkerExecutor().execute(() -> {
                    NativeImage image = null;
                    try {
                        image = new NativeImage(width, height, false);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int color = pixelData.getInt((x + y * width) * pixelSize);
                                image.setColor(x, height - y - 1, keepBackgroundOpaque ? color | 0xFF000000 : color);
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

        // Minecraft 客户端会启用 java.awt.headless，图片剪贴板必须绕过 AWT 直接写 Win32。

        static int readImageArgb(NativeImage image, int x, int y) {
            return image.getColorArgb(x, y);
        }

        void flushDynamic() {
            MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers().draw();
        }

        void closeBuffers() {
            this.layerBuffers.values().forEach(buffers -> buffers.forEach(LayerBuffer::close));
            this.layerBuffers.clear();
            this.staticUploadComplete = false;
            this.dynamicPreparationArmed = false;
            this.closeDynamicBuffers();
        }

        void closeDynamicBuffers() {
            this.dynamicBuffers.forEach(layerBuffer -> layerBuffer.buffer().close());
            this.dynamicBuffers = List.of();
            this.dynamicBuffersReady = false;
        }

        NativePreview(
                Path sourcePath,
                Path cachePath,
                Path tmpPath,
                String cacheSlot,
                String resourcePackSignature
        ) {
            super(sourcePath, cachePath, tmpPath, cacheSlot, resourcePackSignature);
        }

        void uploadMesh(LayerMesh mesh) {
            LayerBuffer uploaded = uploadLayer(mesh);
            if (uploaded == null) return;
            try {
                if (this.cancelled.get()) { uploaded.close(); return; }
                this.layerBuffers.computeIfAbsent(mesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
            } catch (Throwable failure) {
                uploaded.close();
                throw failure;
            }
        }

        boolean hasUploadedBuffers() { return !this.layerBuffers.isEmpty(); }

        void closeRenderer() {
                this.closeBuffers();
                if (this.previewLightingBuffer != null && !this.previewLightingBuffer.isClosed()) {
                    this.previewLightingBuffer.close();
                }
                this.previewProjection.close();
                }

        void takeNativeSnapshot(int resolution, int backgroundColor, DragState drag, MeshData data,
                                Consumer<NativeImage> imageCallback, Consumer<Throwable> failureCallback) {
            Framebuffer framebuffer;
            try {
                framebuffer = new SimpleFramebuffer("QuickCraft preview snapshot", resolution, resolution, true);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        Objects.requireNonNull(framebuffer.getColorAttachment()),
                        backgroundColor,
                        Objects.requireNonNull(framebuffer.getDepthAttachment()),
                        1.0D
                );
                this.renderSnapshot(framebuffer, data, drag);
            } catch (Throwable ignored) {

                failureCallback.accept(ignored);
                return;
            }

            boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
            copySnapshot(framebuffer, keepBackgroundOpaque, imageCallback, throwable -> {

                failureCallback.accept(throwable);
            });
        }
    }

    record PreviewGuiElement(
            NativePreview preview,
            int x1,
            int y1,
            int size,
            float dragX,
            float dragY,
            double angle,
            float pitch,
            float dragScale,
            ScreenRect scissorArea,
            ScreenRect bounds
    ) implements SpecialGuiElementRenderState {
        PreviewGuiElement(
                NativePreview preview,
                int x,
                int y,
                int size,
                float dragX,
                float dragY,
                double angle,
                float pitch,
                float dragScale,
                @Nullable ScreenRect scissorArea
        ) {
            this(
                    preview,
                    x,
                    y,
                    size,
                    dragX,
                    dragY,
                    angle,
                    pitch,
                    dragScale,
                    scissorArea,
                    SpecialGuiElementRenderState.createBounds(x, y, x + size, y + size, scissorArea)
            );
        }

        @Override
        public int x2() {
            return this.x1 + this.size;
        }

        @Override
        public int y2() {
            return this.y1 + this.size;
        }

        @Override
        public float scale() {
            return 1.0F;
        }
    }

    static final class PreviewGuiElementRenderer extends SpecialGuiElementRenderer<PreviewGuiElement> {
        PreviewGuiElementRenderer(VertexConsumerProvider.Immediate vertexConsumers) {
            super(vertexConsumers);
        }

        @Override
        public Class<PreviewGuiElement> getElementClass() {
            return PreviewGuiElement.class;
        }

        @Override
        protected void render(PreviewGuiElement element, MatrixStack matrices) {
            element.preview().drawSpecial(element);
        }

        @Override
        protected String getName() {
            return "quickcraft:schematic_preview";
        }
    }

    record DynamicLayerBuffer(RenderLayer layer, LayerBuffer buffer) {
    }

    static final class DynamicMeshCollector implements VertexConsumerProvider, AutoCloseable {
        final Map<RenderLayer, DynamicMeshBuilder> sharedBuilders = new LinkedHashMap<>();
        final List<DynamicMeshBuilder> builders = new ArrayList<>();
        long allocatedBytes;

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
            DynamicMeshBuilder meshBuilder;
            if (!layer.areVerticesNotShared()) {
                meshBuilder = this.createBuilder(layer);
            } else {
                meshBuilder = this.sharedBuilders.computeIfAbsent(layer, this::createBuilder);
            }

            int vertexBytes = layer.getVertexFormat().getVertexSize();
            if (layer.getDrawMode() == VertexFormat.DrawMode.LINES || layer.getDrawMode() == VertexFormat.DrawMode.LINE_STRIP) {
                vertexBytes *= 2;
            }
            return new LimitedVertexConsumer(meshBuilder.builder(), this, vertexBytes);
        }

        DynamicMeshBuilder createBuilder(RenderLayer layer) {
            if (this.builders.size() >= MAX_DYNAMIC_RENDER_LAYERS) {
                throw new DynamicBufferTooLargeException("动态渲染层超过 1024 个上限");
            }
            int initialBytes = !layer.areVerticesNotShared()
                    ? 256
                    : Math.max(256, Math.min(layer.getExpectedBufferSize(), DYNAMIC_LAYER_INITIAL_BYTES));
            DynamicMeshBuilder meshBuilder = new DynamicMeshBuilder(
                    layer,
                    new BufferAllocator(initialBytes)
            );
            this.builders.add(meshBuilder);
            return meshBuilder;
        }

        void reserve(int bytes) {
            this.allocatedBytes += bytes;
            if (this.allocatedBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                throw new DynamicBufferTooLargeException("动态顶点超过 128 MiB 上限");
            }
        }

        List<DynamicLayerBuffer> upload() {
            List<DynamicLayerBuffer> uploaded = new ArrayList<>();
            try {
                for (DynamicMeshBuilder meshBuilder : this.builders) {
                    try {
                        try (BuiltBuffer built = meshBuilder.builder().endNullable()) {
                            if (built == null) {
                                continue;
                            }
                            if (meshBuilder.layer().isTranslucent()) {
                                built.sortQuads(meshBuilder.allocator(), VertexSorter.byDistance(0.0F, 0.0F, 1000.0F));
                            }

                            uploaded.add(new DynamicLayerBuffer(meshBuilder.layer(), uploadBuiltBuffer(built)));
                        }
                    } finally {
                        meshBuilder.allocator().close();
                    }
                }
                return List.copyOf(uploaded);
            } catch (Throwable throwable) {
                uploaded.forEach(layerBuffer -> layerBuffer.buffer().close());
                throw throwable;
            }
        }

        static LayerBuffer uploadBuiltBuffer(BuiltBuffer built) {
            var drawParameters = built.getDrawParameters();
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "QuickCraft dynamic preview vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    built.getBuffer()
            );
            GpuBuffer indexBuffer = null;
            boolean customIndexBuffer = built.getSortedBuffer() != null;
            try {
                indexBuffer = customIndexBuffer
                        ? RenderSystem.getDevice().createBuffer(
                                () -> "QuickCraft dynamic preview indices",
                                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                built.getSortedBuffer()
                        )
                        : RenderSystem.getSequentialBuffer(drawParameters.mode()).getIndexBuffer(drawParameters.indexCount());
                var indexType = customIndexBuffer
                        ? drawParameters.indexType()
                        : RenderSystem.getSequentialBuffer(drawParameters.mode()).getIndexType();
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
        public void close() {
            this.builders.forEach(meshBuilder -> meshBuilder.allocator().close());
            this.builders.clear();
            this.sharedBuilders.clear();
        }
    }

    record DynamicMeshBuilder(RenderLayer layer, BufferAllocator allocator, BufferBuilder builder) {
        DynamicMeshBuilder(RenderLayer layer, BufferAllocator allocator) {
            this(layer, allocator, new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat()));
        }
    }

    static final class LimitedVertexConsumer implements VertexConsumer {
        final VertexConsumer delegate;
        final DynamicMeshCollector collector;
        final int vertexBytes;

        LimitedVertexConsumer(VertexConsumer delegate, DynamicMeshCollector collector, int vertexBytes) {
            this.delegate = delegate;
            this.collector = collector;
            this.vertexBytes = vertexBytes;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.collector.reserve(this.vertexBytes);
            this.delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.delegate.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            this.delegate.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            this.delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.collector.reserve(this.vertexBytes);
            this.delegate.vertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    record LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount,
                               VertexFormat.IndexType indexType,
                               boolean ownsIndexBuffer) implements AutoCloseable {
        RenderPipeline pipeline(RenderLayer renderLayer, RenderPipeline defaultPipeline) {
            return defaultPipeline;
        }

        @Override
        public void close() {
            if (!this.vertexBuffer.isClosed()) {
                this.vertexBuffer.close();
            }
            if (this.ownsIndexBuffer && !this.indexBuffer.isClosed()) {
                this.indexBuffer.close();
            }
        }
    }

    // 渲染方块实体到指定 VertexConsumerProvider。动态 BE 会走各自专用 atlas，不能录进普通方块 VBO。
    static <T extends BlockEntity> void renderBlockEntity(MinecraftClient client, T entity, MatrixStack matrices, VertexConsumerProvider consumers) {
        BlockEntityRenderer<T> renderer = client.getBlockEntityRenderDispatcher().get(entity);
        if (renderer == null) {
            return;
        }

        renderer.render(
                entity,
                0.0F,
                matrices,
                consumers,
                LightmapTextureManager.MAX_LIGHT_COORDINATE,
                OverlayTexture.DEFAULT_UV,
                new net.minecraft.util.math.Vec3d(0.0D, 0.0D, 0.0D)
        );
    }

    static String currentResourcePackSignature() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, SharedConstants.getGameVersion().name());
            MinecraftClient.getInstance().getResourcePackManager().getEnabledProfiles()
                    .forEach(profile -> updateDigest(digest, profile.getId()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
    static final class MeshRenderer {
        final MeshCollector collector;
        final BlockRenderManager blockRenderManager;
        final MatrixStack matrices;
        final Random random;

        MeshRenderer(MeshCollector collector, BlockRenderManager manager, MatrixStack matrices, Random random, ClientWorld world) {
            this.collector = collector;
            this.blockRenderManager = manager;
            this.matrices = matrices;
            this.random = random;
        }

        void renderFluid(RegionBlockView view, BlockState state, BlockPos pos, BlockPos renderPos) {
            renderFluidIfPresent(this.collector, this.blockRenderManager, this.matrices, view, state, pos, renderPos);
        }

        void renderBlock(RegionBlockView view, BlockState state, BlockPos pos, BlockPos renderPos) {
            renderBlockModel(this.collector, this.blockRenderManager, this.matrices, view, state, pos, renderPos, this.random);
        }

        static void renderFluidIfPresent(
                MeshCollector collector,
                BlockRenderManager blockRenderManager,
                MatrixStack matrices,
                RegionBlockView view,
                BlockState state,
                BlockPos pos,
                BlockPos renderPos
        ) {
            FluidState fluidState = state.getFluidState();
            if (fluidState.isEmpty()) {
                return;
            }

            BlockRenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);
            matrices.push();
            matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());
            blockRenderManager.renderFluid(pos, view, new FluidVertexConsumer(collector.consumerFor(LayerKey.fromFluid(fluidLayer)), matrices.peek().getPositionMatrix()), state, fluidState);
            matrices.pop();
        }

        static void renderBlockModel(
                MeshCollector collector,
                BlockRenderManager blockRenderManager,
                MatrixStack matrices,
                RegionBlockView view,
                BlockState state,
                BlockPos pos,
                BlockPos renderPos,
                Random random
        ) {
            if (state.getRenderType() != BlockRenderType.MODEL) {
                return;
            }

            matrices.push();
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

            var model = blockRenderManager.getModel(state);
            if (PreviewCtm.isContinuityModel(model) && PreviewCtm.emit(model, matrices, collector, view, state, pos)) {
                matrices.pop();
                return;
            }
            BlockRenderLayer blockLayer = RenderLayers.getBlockLayer(state);
            random.setSeed(state.getRenderingSeed(pos));
            blockRenderManager.renderBlock(
                    state,
                    pos,
                    view,
                    matrices,
                    collector.consumerFor(state.isOf(Blocks.NETHER_PORTAL) ? LayerKey.PORTAL : LayerKey.from(blockLayer)),
                    true,
                    model.getParts(random)
            );

            matrices.pop();
        }

        /**
         * Continuity 的连接纹理模型需要经过 Fabric Renderer 的 emitQuads 路径；
         * 1.21 的原版 getParts 路径可能跳过连接纹理模型包装。
         */
        static final class PreviewCtm {
            static final boolean ACTIVE = FabricLoader.getInstance().isModLoaded("continuity");
            @Nullable
            static String cachedRuntimeToken;

            static boolean isContinuityModel(Object model) {
                return ACTIVE
                        && model != null
                        && model.getClass().getName().startsWith("me.pepperbell.continuity.");
            }

            static boolean emit(
                    Object model,
                    MatrixStack matrices,
                    MeshCollector collector,
                    RegionBlockView view,
                    BlockState state,
                    BlockPos pos
            ) {
                try {
                    Renderer renderer = Renderer.get();
                    if (renderer != null) {
                        renderer.render(
                                MinecraftClient.getInstance().getBlockRenderManager().getModelRenderer(),
                                view,
                                (net.minecraft.client.render.model.BlockStateModel) model,
                                state,
                                pos,
                                matrices,
                                collector,
                                true,
                                state.getRenderingSeed(pos),
                                OverlayTexture.DEFAULT_UV
                        );
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("QuickCraft PreviewCtm failed to render model for {}", state, t);
                }
                return false;
            }

            static String runtimeToken() {
                String token = cachedRuntimeToken;
                if (token != null) {
                    return token;
                }

                token = "none";
                if (ACTIVE) {
                    token = FabricLoader.getInstance().getModContainer("continuity")
                            .map(container -> container.getMetadata().getVersion().getFriendlyString())
                            .orElse("loaded-unknown");
                }
                cachedRuntimeToken = token;
                return token;
            }
        }
    }

    enum LayerKey {
        SOLID(0) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getSolid();
            }
        },
        CUTOUT_MIPPED(1) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getCutoutMipped();
            }
        },
        CUTOUT(2) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getCutout();
            }
        },
        TRIPWIRE(3) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTripwire();
            }
        },
        TRANSLUCENT(4) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTranslucentMovingBlock();
            }
        },
        PORTAL(5) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTranslucentMovingBlock();
            }
        },
        FLUID(6) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTranslucentMovingBlock();
            }
        };

        static final LayerKey[] DRAW_ORDER = {SOLID, CUTOUT_MIPPED, CUTOUT, TRIPWIRE, PORTAL, FLUID, TRANSLUCENT};
        final int id;

        LayerKey(int id) {
            this.id = id;
        }

        abstract RenderLayer renderLayer();

        boolean isTranslucent() {
            return this == PORTAL || this == FLUID || this == TRANSLUCENT;
        }

        boolean drawAfterEntities() {
            return this == TRANSLUCENT;
        }

        static LayerKey from(RenderLayer layer) {
            if (layer == RenderLayer.getSolid()) {
                return SOLID;
            }
            if (layer == RenderLayer.getCutoutMipped()) {
                return CUTOUT_MIPPED;
            }
            if (layer == RenderLayer.getCutout()) {
                return CUTOUT;
            }
            if (layer == RenderLayer.getTripwire()) {
                return TRIPWIRE;
            }
            if (layer == RenderLayer.getTranslucentMovingBlock() || layer.isTranslucent()) {
                return TRANSLUCENT;
            }
            return SOLID;
        }

        static LayerKey from(BlockRenderLayer layer) {
            return switch (layer) {
                case SOLID -> SOLID;
                case CUTOUT_MIPPED -> CUTOUT_MIPPED;
                case CUTOUT -> CUTOUT;
                case TRIPWIRE -> TRIPWIRE;
                case TRANSLUCENT -> TRANSLUCENT;
            };
        }

        static LayerKey fromFluid(BlockRenderLayer layer) {
            LayerKey key = from(layer);
            return key == TRANSLUCENT ? FLUID : key;
        }

        @Nullable
        static LayerKey byId(int id) {
            for (LayerKey value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return null;
        }
    }

    static final class MeshCollector implements BlockVertexConsumerProvider {
        final EnumMap<LayerKey, RecordingVertexConsumer> consumers = new EnumMap<>(LayerKey.class);
        int vertexCount;

        VertexConsumer consumerFor(BlockRenderLayer renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        VertexConsumer consumerFor(LayerKey layer) {
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        @Override
        public VertexConsumer getBuffer(BlockRenderLayer renderLayer) {
            return this.consumerFor(renderLayer);
        }

        void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            if (this.vertexCount >= MAX_UPLOAD_VERTICES) {
                throw new PreviewTooLargeException();
            }

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
        }

        boolean shouldPublishOpaqueBatch() {
            int vertices = 0;
            for (Map.Entry<LayerKey, RecordingVertexConsumer> entry : this.consumers.entrySet()) {
                if (!entry.getKey().isTranslucent()) {
                    vertices += entry.getValue().vertices.vertexCount();
                }
            }
            return vertices >= STATIC_BATCH_TARGET_VERTICES;
        }

        List<LayerMesh> drainOpaqueMeshes() {
            return this.drainMeshes(false);
        }

        List<LayerMesh> drainAllMeshes() {
            return this.drainMeshes(true);
        }

        List<LayerMesh> drainMeshes(boolean includeTranslucent) {
            List<LayerMesh> meshes = new ArrayList<>();
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                if (!includeTranslucent && layer.isTranslucent()) {
                    continue;
                }
                RecordingVertexConsumer consumer = this.consumers.get(layer);
                if (consumer != null && !consumer.vertices.isEmpty()) {
                    meshes.add(new LayerMesh(layer, consumer.vertices.takeBytes()));
                    this.consumers.remove(layer);
                }
            }
            return List.copyOf(meshes);
        }
    }

    static final class RecordingVertexConsumer implements VertexConsumer {
        final MeshCollector collector;
        final QuantizedVertexBuffer vertices = new QuantizedVertexBuffer();
        float x;
        float y;
        float z;
        int argb = 0xFFFFFFFF;
        float u;
        float v;
        int overlay = OverlayTexture.DEFAULT_UV;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        RecordingVertexConsumer(MeshCollector collector) {
            this.collector = collector;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.argb = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer color(int argb) {
            this.argb = argb;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            this.overlay = OverlayTexture.packUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int uv) {
            this.overlay = uv;
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            this.light = LightmapTextureManager.pack(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int uv) {
            this.light = uv;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.collector.addVertex(this.vertices, this.x, this.y, this.z, this.argb, this.u, this.v, this.overlay, this.light, x, y, z);
            this.overlay = OverlayTexture.DEFAULT_UV;
            this.light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            return this;
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.collector.addVertex(this.vertices, x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    static final class FluidVertexConsumer implements VertexConsumer {
        final VertexConsumer delegate;
        final Matrix4f transform;

        FluidVertexConsumer(VertexConsumer delegate, Matrix4f transform) {
            this.delegate = delegate;
            this.transform = transform;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.delegate.vertex(this.transform, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.delegate.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            this.delegate.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            this.delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.delegate.normal(x, y, z);
            return this;
        }

    }

    static final class QuantizedVertexBuffer {
        byte[] bytes = new byte[QUANTIZED_VERTEX_BYTES * 256];
        int position;

        boolean isEmpty() {
            return this.position == 0;
        }

        int vertexCount() {
            return this.position / QUANTIZED_VERTEX_BYTES;
        }

        void add(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            this.ensureCapacity(this.position + QUANTIZED_VERTEX_BYTES);
            this.writeInt(Float.floatToIntBits(x));
            this.writeInt(Float.floatToIntBits(y));
            this.writeInt(Float.floatToIntBits(z));
            this.writeInt(argb);
            this.writeInt(Float.floatToIntBits(u));
            this.writeInt(Float.floatToIntBits(v));
            this.writeShort(CacheFile.encodeOverlay(overlay));
            this.writeInt(light);
            this.writeShort(CacheFile.encodeNormal(nx, ny, nz));
        }

        byte[] takeBytes() {
            byte[] result = this.bytes.length == this.position ? this.bytes : Arrays.copyOf(this.bytes, this.position);
            this.bytes = new byte[0];
            this.position = 0;
            return result;
        }

        void ensureCapacity(int needed) {
            if (needed <= this.bytes.length) {
                return;
            }
            if (needed > MAX_QUANTIZED_LAYER_BYTES) {
                throw new PreviewTooLargeException();
            }

            int newLength = this.bytes.length;
            while (newLength < needed) {
                newLength = Math.min(MAX_QUANTIZED_LAYER_BYTES, newLength << 1);
            }
            this.bytes = Arrays.copyOf(this.bytes, newLength);
        }

        void writeInt(int value) {
            this.bytes[this.position++] = (byte) (value >>> 24);
            this.bytes[this.position++] = (byte) (value >>> 16);
            this.bytes[this.position++] = (byte) (value >>> 8);
            this.bytes[this.position++] = (byte) value;
        }

        void writeShort(short value) {
            this.bytes[this.position++] = (byte) (value >>> 8);
            this.bytes[this.position++] = (byte) value;
        }
    }

    static RegistryEntryLookup<Block> blockLookup(DynamicRegistryManager registryManager) {
        return registryManager.getOrThrow(RegistryKeys.BLOCK);
    }

    record BlockStateData(int x, int y, int z, NbtCompound stateNbt) {
        BlockState state(DynamicRegistryManager registryManager) {
            return NbtHelper.toBlockState(blockLookup(registryManager), this.stateNbt);
        }
    }

    record EntityData(double x, double y, double z, NbtCompound entityNbt) {
        @Nullable
        RenderedEntity instantiate(DummyWorld world) {
            try {
                Entity entity = EntityUtils.createEntityAndPassengersFromNBT(this.entityNbt.copy(), world);
                if (entity == null) {
                    return null;
                }

                entity.setPosition(this.x, this.y, this.z);
                int light = MinecraftClient.getInstance().getEntityRenderDispatcher().getLight(entity, 0.0F);
                return new RenderedEntity(entity, this.x, this.y, this.z, light);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    record BlockEntityData(int x, int y, int z, NbtCompound stateNbt, NbtCompound entityNbt) {
        @Nullable
        BlockEntity instantiate(DummyWorld world) {
            BlockState state = NbtHelper.toBlockState(blockLookup(world.getRegistryManager()), this.stateNbt);
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
                return null;
            }

            BlockPos pos = new BlockPos(this.x, this.y, this.z);
            try {
                BlockEntity blockEntity = provider.createBlockEntity(pos, state);
                if (blockEntity == null) {
                    return null;
                }

                setPreviewBlockEntityState(blockEntity, state);
                if (!this.entityNbt.isEmpty()) {
                    blockEntity.read(NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), this.entityNbt.copy()));
                }
                blockEntity.setWorld(world);
                return blockEntity;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    static final class DummyWorld extends WorldSchematic {
        Map<BlockPos, BlockState> blockStates = Map.of();
        Map<BlockPos, BlockEntity> blockEntities = Map.of();

        DummyWorld(MutableWorldProperties properties, DynamicRegistryManager registryManager, RegistryEntry<DimensionType> dimensionEntry, WorldRendererSchematic renderer) {
            super(properties, registryManager, dimensionEntry, renderer);
        }

        static DummyWorld fromWorld(ClientWorld world) {
            return new DummyWorld(world.getLevelProperties(), world.getRegistryManager(), world.getDimensionEntry(), new WorldRendererSchematic(MinecraftClient.getInstance()));
        }

        void setBlockStates(Map<BlockPos, BlockState> blockStates) {
            this.blockStates = Map.copyOf(blockStates);
        }

        void setBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
            this.blockEntities = Map.copyOf(blockEntities);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.blockStates.getOrDefault(pos, LitematicaBlockStateContainer.AIR_BLOCK_STATE);
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return this.blockEntities.get(pos);
        }
    }

    // 渲染线程调用：把量化字节数组直接解码进 BufferBuilder，跳过 PreviewVertex 对象。
    static void decodeQuantizedToBuilder(byte[] quantized, BufferBuilder builder) {
        float[] normal = new float[3];
        for (int offset = 0; offset < quantized.length; offset += QUANTIZED_VERTEX_BYTES) {
            float x = Float.intBitsToFloat(CacheFile.readInt(quantized, offset));
            float y = Float.intBitsToFloat(CacheFile.readInt(quantized, offset + 4));
            float z = Float.intBitsToFloat(CacheFile.readInt(quantized, offset + 8));
            int argb = CacheFile.readInt(quantized, offset + 12);
            float u = Float.intBitsToFloat(CacheFile.readInt(quantized, offset + 16));
            float v = Float.intBitsToFloat(CacheFile.readInt(quantized, offset + 20));
            int overlay = CacheFile.decodeOverlay(CacheFile.readShort(quantized, offset + 24));
            int light = CacheFile.readInt(quantized, offset + 26);
            CacheFile.decodeNormal(CacheFile.readShort(quantized, offset + 30), normal);
            builder.vertex(x, y, z, argb, u, v, overlay, light, normal[0], normal[1], normal[2]);
        }
    }
    static Path entryPath(DirectoryEntry entry) { return entry.getFullPath(); }

    static void drawOutlinedBox(DrawContext context, int x, int y, int w, int h, int fill, int border) { RenderUtils.drawOutlinedBox(context, x, y, w, h, fill, border); }

    static void renderShaderDisabled(DrawContext context, int x, int y, int size) {
        MinecraftClient client = MinecraftClient.getInstance();
        RenderUtils.drawOutlinedBox(context, x, y, size, size, 0xB0101010, 0xFF707070);
        Text message = Text.translatable("quickcraft.message.litematica.preview_3d.shader_disabled");
        var lines = client.textRenderer.wrapLines(message, Math.max(1, size - 16));
        int lineStep = client.textRenderer.fontHeight + 2;
        int textY = y + (size - lines.size() * lineStep) / 2;
        for (var line : lines) {
            context.drawCenteredTextWithShadow(client.textRenderer, line, x + size / 2, textY, 0xFFFFCC55);
            textY += lineStep;
        }
    }
    static boolean writeSchematic(LitematicaSchematic schematic, Path parent, String name) { return schematic.writeToFile(parent, name, true); }

    static String ctmRuntimeToken() { return MeshRenderer.PreviewCtm.runtimeToken(); }
    static boolean containsCompound(NbtCompound tag, String name) { return tag.contains(name); }
    static boolean containsList(NbtCompound tag, String name) { return tag.contains(name); }
    static NbtCompound readCompound(NbtCompound tag, String name) { return tag.getCompoundOrEmpty(name); }
    static NbtList readCompoundList(NbtCompound tag, String name) { return tag.getListOrEmpty(name); }
    static NbtCompound readListCompound(NbtList list, int index) { return list.getCompoundOrEmpty(index); }
    static String readString(NbtCompound tag, String name) { return tag.getString(name, ""); }
    static Vec3d entityPos(LitematicaSchematic.EntityInfo info) { return info.posVec; }
    static NbtCompound entityNbt(LitematicaSchematic.EntityInfo info) { return info.nbt; }
    static NbtCompound previewBlockEntityNbt(LitematicaSchematic schematic, String region, BlockPos pos) {
        Map<BlockPos, NbtCompound> source = schematic.getBlockEntityMapForRegion(region);
        return source == null ? new NbtCompound() : source.getOrDefault(pos, new NbtCompound());
    }
    static boolean tryDisableShaders() {
        try {
            // Iris 是可选依赖，只在后端访问其公开 v0 API。
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object config = apiClass.getMethod("getConfig").invoke(api);
            Class<?> configClass = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            configClass.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
            return true;
        } catch (Throwable failure) {
            if (SHADER_DISABLE_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shaders could not be disabled before opening a QuickCraft 3D preview", failure);
            }
            return false;
        }
    }
}
