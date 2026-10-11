package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.CacheFile;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.updateDigest;

import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.VertexSorter;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.render.RenderUtils;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.WorldMesherRenderContext;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUsage;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
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
    static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v16-api-audit-stable-path-content-resource-signature-mc1.21.3-entity-center-block-ctm-overlay-equipment-v1";
    // 静态顶点磁盘编码：12B 位置 + 4B 颜色 + 8B UV(float32×2) + 4B light + 2B 法线(octahedral) = 30B。
    static final int QUANTIZED_VERTEX_BYTES = 30;


    static boolean isSupportedLitematic(DirectoryEntry entry) {
        return entry.getFullPath().isFile() && FileType.fromFile(entry.getFullPath().toPath()) == FileType.LITEMATICA_SCHEMATIC;
    }
    static final class NativePreview extends Preview {
        private final QuickLitematicaPreviewLoadingRenderer loadingRenderer = new QuickLitematicaPreviewLoadingRenderer();
        final Map<LayerKey, List<VertexBuffer>> vertexBuffers = new EnumMap<>(LayerKey.class);
        private final QuickLitematicaPreviewTransparentRenderer transparentRenderer = new QuickLitematicaPreviewTransparentRenderer();

        final List<DynamicLayerBuffer> dynamicBuffers = new ArrayList<>();
        private QuickLitematicaPreviewDynamicBatch<RenderLayer> dynamicBuild;
        private boolean dynamicBatchingDisabled;
        @Nullable private QuickLitematicaPreviewDynamicBatch<RenderLayer> dynamicFullBuild;
        @Nullable private DynamicMeshCollector dynamicFullCollector;

        void drawPreview(DrawContext context, int x, int y, int size, DragState drag) {
            if (QuickLitematicaPreviewLog.enabled()) this.trace.frame(this.staticUploadComplete, () -> this.drawFrame(context, x, y, size, drag));
            else this.drawFrame(context, x, y, size, drag);
        }

        private void drawFrame(DrawContext context, int x, int y, int size, DragState drag) {
            if (this.loadingRenderer.draw(this, context, x, y, size, drag)) return;
            this.loadingRenderer.close();
            this.drawMesh(context, x, y, size, drag);
        }

        @Nullable
        VertexBuffer uploadLayer(LayerMesh layerMesh) {
            int vertexCount = layerMesh.vertexCount();
            int allocatorSize = allocatorSize(vertexCount);
            BufferAllocator allocator = new BufferAllocator(allocatorSize);
            try {
                BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
                CacheFile.decodeQuantizedToBuilder(layerMesh.quantizedVertices(), builder);

                var built = builder.endNullable();
                if (built == null) {
                    return null;
                }

                net.minecraft.client.render.BuiltBuffer.SortState translucentSort = null;
                if (layerMesh.needsTranslucentSorting()) {
                    translucentSort = built.sortQuads(allocator, points -> QuickLitematicaPreviewIndices.initialOrder(points.length));
                }

                if (translucentSort != null && this.transparentRenderer.uploadBatch(layerMesh, built, translucentSort,
                        this.meshData, this.vertexBuffers, this::uploadLayer)) return null;

                VertexBuffer buffer = new VertexBuffer(GlUsage.STATIC_WRITE);
                boolean uploaded = false;
                try {
                    buffer.bind();
                    buffer.upload(built);
                    if (translucentSort != null) this.transparentRenderer.register(buffer, translucentSort);
                    uploaded = true;
                    return buffer;
                } finally {
                    VertexBuffer.unbind();
                    if (!uploaded && !buffer.isClosed()) {
                        buffer.close();
                    }
                }
            } finally {
                allocator.close();
            }
        }

        static int allocatorSize(int vertexCount) {
            long bytes = Math.max(256L, (long) vertexCount * VERTEX_BYTES);
            return (int) Math.min(Integer.MAX_VALUE - 8L, bytes);
        }

        void drawMesh(DrawContext context, int x, int y, int size, DragState drag) {
            MeshData data = this.meshData;
            PreviewDimensions dimensions = this.dimensions;
            MinecraftClient client = MinecraftClient.getInstance();
            if (dimensions == null || client.currentScreen == null) {
                return;
            }

            context.enableScissor(x + 1, y + 1, x + size - 2, y + size - 2);
            RenderSystem.backupProjectionMatrix();

            float aspectRatio = client.getWindow().getFramebufferWidth() / (float) client.getWindow().getFramebufferHeight();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-aspectRatio, aspectRatio, -1.0F, 1.0F, -1000.0F, 3000.0F), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            try {
                modelView.identity();
                translateToScreen(modelView, client, x + size / 2.0F + drag.dx, y + size / 2.0F + drag.dy);
                modelView.rotate(RotationAxis.POSITIVE_X.rotation(drag.pitch));
                modelView.rotate(RotationAxis.POSITIVE_Y.rotation((float) drag.angle));
                float scale = dimensions.scaleFactor(size, client.currentScreen.height) * drag.scale;
                modelView.scale(scale, scale, scale);
                modelView.translate(-dimensions.sizeX() / 2.0F, -dimensions.sizeY() / 2.0F, -dimensions.sizeZ() / 2.0F);

                this.applyLight(modelView);
                this.drawBuffers(modelView, null, false, false);
                if (data != null && this.staticUploadComplete) {
                    if (this.dynamicPreparationArmed) {
                        this.prepareDynamicBuffers(data);
                        if (this.dynamicBuffersReady) {
                            this.drawDynamicBuffers(modelView, null, false);
                        } else if (this.dynamicBufferFallback) {
                            this.drawDynamic(data, modelView, x, y, size);
                        }
                    } else {
                        this.dynamicPreparationArmed = true;
                    }
                }
                this.drawBuffers(modelView, null, false, true);
            } finally {
                VertexBuffer.unbind();
                modelView.popMatrix();
                RenderSystem.disableDepthTest();
                RenderSystem.disableBlend();
                RenderSystem.restoreProjectionMatrix();
                context.disableScissor();
            }
        }

        void drawBuffers(Matrix4f modelView, @Nullable Framebuffer target, boolean keepTargetOpaque, boolean afterEntities) {
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                if (layer.drawAfterEntities() != afterEntities) {
                    continue;
                }
                if (layer.isTranslucent() && !this.staticUploadComplete) {
                    continue;
                }
                List<VertexBuffer> buffers = this.vertexBuffers.get(layer);
                if (buffers == null || buffers.isEmpty()) {
                    continue;
                }

                RenderLayer renderLayer = layer.renderLayer();
                renderLayer.startDrawing();
                if (target != null) {
                    target.beginWrite(false);
                }
                if (keepTargetOpaque) {
                    RenderSystem.colorMask(true, true, true, false);
                }
                try {
                    if (layer.isTranslucent() && buffers.size() > 1) {
                        for (var run : this.transparentRenderer.runs(modelView)) {
                            this.drawTransparentRange(buffers.get(run.buffer()), modelView, run.firstIndex(), run.indexCount());
                        }
                        continue;
                    }
                    for (VertexBuffer buffer : buffers) {
                        if (!buffer.isClosed()) {
                            buffer.bind();
                            this.transparentRenderer.resort(buffer, modelView);
                            buffer.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                        }
                    }
                } finally {
                    if (keepTargetOpaque) {
                        RenderSystem.colorMask(true, true, true, true);
                    }
                    renderLayer.endDrawing();
                }
            }
            VertexBuffer.unbind();
        }

        void mergeTransparentBuffers() {
            this.transparentRenderer.merge(this.vertexBuffers, this::uploadLayer);
        }

        private void drawTransparentRange(VertexBuffer buffer, Matrix4f modelView, int firstIndex, int indexCount) {
            if (buffer.isClosed()) return;
            buffer.bind();
            this.transparentRenderer.resort(buffer, modelView);
            var shader = RenderSystem.getShader();
            shader.initializeUniforms(VertexFormat.DrawMode.QUADS, modelView,
                    RenderSystem.getProjectionMatrix(), MinecraftClient.getInstance().getWindow());
            shader.bind();
            try {
                var type = this.transparentRenderer.indexType(buffer);
                org.lwjgl.opengl.GL11.glDrawElements(VertexFormat.DrawMode.QUADS.glMode,
                        indexCount, type.glType, (long) firstIndex * type.size);
            } finally {
                shader.unbind();
            }
        }

        void prepareDynamicBuffers(MeshData data) {
            if (this.dynamicBuffersReady || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            DynamicScene fullScene = data.dynamicSceneIfReady();

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
                // 特殊内容尚无耗时基线，首批从最小记录预算开始。
                this.dynamicFullBuild = new QuickLitematicaPreviewDynamicBatch<>(fullScene, QuickLitematicaPreviewBatchBudget.MIN_SIZE);
                this.dynamicFullCollector = new DynamicMeshCollector(data.forceRender);
            }
            var fullBuild = this.dynamicFullBuild;
            DynamicScene scene = batch == null ? fullBuild.nextScene() : batch.nextScene();
            long batchStart = System.nanoTime();
            DynamicMeshCollector collector = batch == null ? this.dynamicFullCollector : new DynamicMeshCollector(data.forceRender);
            boolean retainCollector = false;
            collector.batch = batch;
            if (batch != null) collector.allocatedBytes = batch.allocatedBytes;
            try (var phase = QuickLitematicaPreviewLog.phase(batch == null
                    ? "动态完整缓存帧几何构建/上传" : "动态单批提交/几何构建/上传")) {
                MinecraftClient client = MinecraftClient.getInstance();
                MatrixStack matrices = new MatrixStack();
                scene.blockEntities().forEach((pos, entity) -> {
                    matrices.push();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entity, matrices, collector);
                    } catch (DynamicBufferTooLargeException | QuickLitematicaPreviewDynamicBatch.IncompatibleException e) {
                        throw e;
                    } catch (RuntimeException ignored) {
                        LOGGER.warn("原生预览局部失败：入口=prepareDynamicBuffers，继续原有跳过/回退", ignored);
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
                    } catch (DynamicBufferTooLargeException | QuickLitematicaPreviewDynamicBatch.IncompatibleException e) {
                        throw e;
                    } catch (RuntimeException ignored) {
                        LOGGER.warn("原生预览局部失败：入口=prepareDynamicBuffers，继续原有跳过/回退", ignored);
                    }
                });

                if (batch == null && !fullBuild.finishCollected(System.nanoTime() - batchStart)) {
                    this.dynamicProgress = fullBuild.progress();
                    retainCollector = true;
                    return;
                }
                var uploaded = collector.upload();
                try {
                    this.dynamicBuffers.addAll(uploaded);
                } catch (RuntimeException | Error failure) {
                    uploaded.forEach(layerBuffer -> layerBuffer.buffer().close());
                    throw failure;
                }
                if (batch == null || batch.finish(collector.allocatedBytes, System.nanoTime() - batchStart)) {
                    this.dynamicBuffersReady = true;
                    this.dynamicBuild = null;
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
            } finally {
                if (!retainCollector) {
                    if (this.dynamicFullCollector == collector) {
                        this.dynamicFullCollector = null;
                        this.dynamicFullBuild = null;
                    }
                    collector.close();
                }
            }
        }

        void drawDynamicBuffers(Matrix4f modelView, @Nullable Framebuffer target, boolean keepTargetOpaque) {
            for (DynamicLayerBuffer layerBuffer : this.dynamicBuffers) {
                VertexBuffer buffer = layerBuffer.buffer();
                if (buffer.isClosed()) {
                    continue;
                }

                RenderLayer renderLayer = layerBuffer.layer();
                renderLayer.startDrawing();
                if (target != null) {
                    target.beginWrite(false);
                }
                if (keepTargetOpaque) {
                    RenderSystem.colorMask(true, true, true, false);
                }
                try {
                    buffer.bind();
                    buffer.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                } finally {
                    if (keepTargetOpaque) {
                        RenderSystem.colorMask(true, true, true, true);
                    }
                    renderLayer.endDrawing();
                }
            }
            VertexBuffer.unbind();
        }

        static int readImageArgb(NativeImage image, int x, int y) {
            return image.getColorArgb(x, y);
        }

        void renderSnapshot(Framebuffer framebuffer, MeshData data, DragState drag, boolean keepBackgroundOpaque) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(-1.0F, 1.0F, -1.0F, 1.0F, -1000.0F, 3000.0F),
                    ProjectionType.ORTHOGRAPHIC
            );
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            boolean previousSnapshotMode = this.transparentRenderer.snapshotRendering;
            this.transparentRenderer.snapshotRendering = true;
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
                framebuffer.beginWrite(true);
                this.drawBuffers(modelView, framebuffer, keepBackgroundOpaque, false);
                if (this.dynamicBuffersReady) {
                    this.drawDynamicBuffers(modelView, framebuffer, keepBackgroundOpaque);
                }
                this.drawBuffers(modelView, framebuffer, keepBackgroundOpaque, true);
            } finally {
                this.transparentRenderer.snapshotRendering = previousSnapshotMode;
                VertexBuffer.unbind();
                RenderSystem.colorMask(true, true, true, true);
                modelView.popMatrix();
                RenderSystem.disableDepthTest();
                RenderSystem.disableBlend();
                RenderSystem.restoreProjectionMatrix();
            }
        }

        static NativeImage takeSnapshot(Framebuffer framebuffer) {
            NativeImage image = new NativeImage(framebuffer.textureWidth, framebuffer.textureHeight, false);
            try {
                RenderSystem.bindTexture(framebuffer.getColorAttachment());
                // 1.21 的 ScreenshotRecorder.takeScreenshot() 会强制把 Alpha 全部改成 255，透明导出必须直接读取纹理。
                image.loadFromTextureImage(0, false);
                image.mirrorVertically();
                return image;
            } catch (Throwable throwable) {
                image.close();
                throw throwable;
            }
        }

        void drawDynamic(MeshData data, Matrix4f modelView, int viewX, int viewY, int viewSize) {
            DynamicScene scene = data.dynamicSceneIfReady();
            if (scene == null) return;
            if (scene.isEmpty()) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            // 视口剔除器：把每个动态对象变换到 framebuffer 像素，落在预览框外的直接跳过。
            // 预览框本身已有 scissor 裁剪，剔除框外对象纯属减负，不影响可见内容。
            ViewportCuller culler = new ViewportCuller(modelView, RenderSystem.getProjectionMatrix(), client, viewX, viewY, viewSize);
            MatrixStack matrices = new MatrixStack();
            scene.blockEntities().forEach((pos, entity) -> {
                // 用方块中心点判定，覆盖大多数方块实体模型
                if (!(entity instanceof net.minecraft.block.entity.BeaconBlockEntity)
                        && culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }
                matrices.push();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    // 高层方块实体渲染会按真实相机做距离判断，预览里的离屏假世界不能走那条路径。
                    renderBlockEntity(client, entity, matrices, client.getBufferBuilders().getEntityVertexConsumers());
                } catch (Throwable ignored) {
                    LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
                } finally {
                    matrices.pop();
                }
            });

            scene.entities().forEach(entity -> {
                if (culler.isOutside((float) entity.x(), (float) entity.y(), (float) entity.z())) {
                    return;
                }
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
                    LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
                }
            });
            // BE 和实体共用同一个 EntityVertexConsumers，一次 flush 提交所有动态顶点。
            this.flushDynamic();
        }

        void flushDynamic() {
            MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers().draw();
        }

        void applyLight(Matrix4f viewMatrix) {
            Matrix4f lightTransform = new Matrix4f(viewMatrix);
            Vector4f lightDirection = new Vector4f(0.0F, 0.35F, 0.25F, 0.0F);
            lightTransform.invert();
            lightDirection.mul(lightTransform);

            Vector3f transformed = new Vector3f(lightDirection.x, lightDirection.y, lightDirection.z);
            RenderSystem.setShaderLights(transformed, transformed);
        }

        void closeBuffers() {
            this.loadingRenderer.close();
            this.transparentRenderer.close();
            this.vertexBuffers.values().forEach(buffers -> buffers.forEach(buffer -> {
                if (!buffer.isClosed()) {
                    buffer.close();
                }
            }));
            this.vertexBuffers.clear();
            this.staticUploadComplete = false;
            this.dynamicPreparationArmed = false;
            this.closeDynamicBuffers();
        }

        void closeDynamicBuffers() {
            var fullCollector = this.dynamicFullCollector;
            this.dynamicFullCollector = null;
            this.dynamicFullBuild = null;
            if (fullCollector != null) fullCollector.close();
            this.dynamicBuffers.forEach(layerBuffer -> {
                if (!layerBuffer.buffer().isClosed()) {
                    layerBuffer.buffer().close();
                }
            });
            this.dynamicBuffers.clear();
            this.dynamicBuild = null;
            this.dynamicBatchingDisabled = false;
            this.dynamicProgress = 0.0F;
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
            VertexBuffer uploaded = uploadLayer(mesh);
            if (uploaded == null) return;
            try {
                if (this.cancelled.get()) { uploaded.close(); return; }
                this.vertexBuffers.computeIfAbsent(mesh.layer(), ignored -> new ArrayList<>()).add(uploaded);
                if (mesh.layer().isTranslucent()) this.transparentRenderer.publish(uploaded);
            } catch (Throwable failure) {
                uploaded.close();
                throw failure;
            }
        }

        boolean hasUploadedBuffers() { return !this.vertexBuffers.isEmpty(); }

        void closeRenderer() { this.closeBuffers(); }

        void renderLoadingFrame(Framebuffer target, PreviewDimensions dimensions, DragState drag) {
            var data = new MeshData(List.of(), List.of(), List.of(), List.of(),
                    dimensions.sizeX(), dimensions.sizeY(), dimensions.sizeZ());
            this.renderSnapshot(target, data, drag, false);
        }

        void takeNativeSnapshot(int resolution, int backgroundColor, DragState drag, MeshData data,
                                Consumer<NativeImage> imageCallback, Consumer<Throwable> failureCallback) {
            Framebuffer framebuffer = null;
            try {
                framebuffer = new SimpleFramebuffer(resolution, resolution, true);
                RenderSystem.colorMask(true, true, true, true);
                framebuffer.setClearColor(
                        ((backgroundColor >> 16) & 0xFF) / 255.0F,
                        ((backgroundColor >> 8) & 0xFF) / 255.0F,
                        (backgroundColor & 0xFF) / 255.0F,
                        ((backgroundColor >>> 24) & 0xFF) / 255.0F
                );
                framebuffer.clear();
                this.renderSnapshot(framebuffer, data, drag, ((backgroundColor >>> 24) & 0xFF) == 0xFF);
                NativeImage image = takeSnapshot(framebuffer);
                try {
                    imageCallback.accept(image);
                } catch (Throwable failure) {
                    image.close();
                    throw failure;
                }
            } catch (Throwable ignored) {
                LOGGER.warn("原生预览局部失败：入口=takeNativeSnapshot，继续原有跳过/回退", ignored);

                failureCallback.accept(ignored);
                return;
            } finally {
                try {
                    if (framebuffer != null) {
                        framebuffer.delete();
                    }
                } finally {
                    MinecraftClient.getInstance().getFramebuffer().beginWrite(true);
                }
            }
        }
    }

    record DynamicLayerBuffer(RenderLayer layer, VertexBuffer buffer) {
    }

    static final class DynamicMeshCollector implements VertexConsumerProvider, AutoCloseable {
        final Map<RenderLayer, DynamicMeshBuilder> sharedBuilders = new LinkedHashMap<>();
        final List<DynamicMeshBuilder> builders = new ArrayList<>();
        final boolean forceRender;
        long allocatedBytes;
        private QuickLitematicaPreviewDynamicBatch<RenderLayer> batch;

        DynamicMeshCollector(boolean forceRender) {
            this.forceRender = forceRender;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
            if (this.batch != null) this.batch.checkLayer(layer, layer == net.minecraft.client.render.TexturedRenderLayers.getChest() && !layer.isTranslucent());
            DynamicMeshBuilder meshBuilder;
            if (!layer.areVerticesNotShared()) {
                meshBuilder = this.createBuilder(layer);
            } else {
                meshBuilder = this.sharedBuilders.computeIfAbsent(layer, this::createBuilder);
            }

            int vertexBytes = layer.getVertexFormat().getVertexSizeByte();
            if (layer.getDrawMode() == VertexFormat.DrawMode.LINES || layer.getDrawMode() == VertexFormat.DrawMode.LINE_STRIP) {
                vertexBytes *= 2;
            }
            return new LimitedVertexConsumer(meshBuilder.builder(), this, vertexBytes);
        }

        DynamicMeshBuilder createBuilder(RenderLayer layer) {
            if (!this.forceRender && this.builders.size() >= MAX_DYNAMIC_RENDER_LAYERS) {
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
            if (!this.forceRender && this.allocatedBytes > MAX_DYNAMIC_BUFFER_BYTES) {
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

                            VertexBuffer buffer = new VertexBuffer(GlUsage.STATIC_WRITE);
                            try {
                                buffer.bind();
                                buffer.upload(built);
                                uploaded.add(new DynamicLayerBuffer(meshBuilder.layer(), buffer));
                            } catch (Throwable throwable) {
                                if (!buffer.isClosed()) {
                                    buffer.close();
                                }
                                throw throwable;
                            }
                        }
                    } finally {
                        meshBuilder.allocator().close();
                    }
                }
                return List.copyOf(uploaded);
            } catch (Throwable throwable) {
                uploaded.forEach(layerBuffer -> {
                    if (!layerBuffer.buffer().isClosed()) {
                        layerBuffer.buffer().close();
                    }
                });
                throw throwable;
            } finally {
                VertexBuffer.unbind();
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

    // 渲染方块实体到指定 VertexConsumerProvider。动态 BE 会走各自专用 atlas，不能录进普通方块 VBO。
    static <T extends BlockEntity> void renderBlockEntity(MinecraftClient client, T entity, MatrixStack matrices, VertexConsumerProvider consumers) {
        BlockEntityRenderer<T> renderer = client.getBlockEntityRenderDispatcher().get(entity);
        if (renderer == null) {
            return;
        }

        int light = entity.getWorld() != null
                ? WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos())
                : LightmapTextureManager.MAX_LIGHT_COORDINATE;
        renderer.render(entity, 0.0F, matrices, consumers, light, OverlayTexture.DEFAULT_UV);
    }

    static String currentResourcePackSignature() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, SharedConstants.getGameVersion().getName());
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
        final WorldMesherRenderContext fabricContext;

        MeshRenderer(MeshCollector collector, BlockRenderManager manager, MatrixStack matrices, Random random, ClientWorld world) {
            this.collector = collector;
            this.blockRenderManager = manager;
            this.matrices = matrices;
            this.random = random;
            this.fabricContext = createFabricContext(collector, world);
        }

        void renderFluid(RegionBlockView view, BlockState state, BlockPos pos, BlockPos renderPos) {
            renderFluidIfPresent(this.collector, this.blockRenderManager, this.matrices, view, state, pos, renderPos);
        }

        void renderBlock(RegionBlockView view, BlockState state, BlockPos pos, BlockPos renderPos) {
            renderBlockModel(this.collector, this.blockRenderManager, this.fabricContext, this.matrices, view, state, pos, renderPos, this.random);
        }

        @Nullable
        static WorldMesherRenderContext createFabricContext(MeshCollector collector, ClientWorld world) {
            try {
                if (RendererAccess.INSTANCE.hasRenderer()) {
                    var renderer = RendererAccess.INSTANCE.getRenderer();
                    if (renderer != null && renderer.getClass().getName().contains("indigo")) {
                        DummyWorld dummyWorld = DummyWorld.fromWorld(world);
                        return new WorldMesherRenderContext(dummyWorld, layer -> collector.consumerFor(layer));
                    }
                }
            } catch (Throwable ignored) {
                LOGGER.warn("原生预览局部失败：入口=createFabricContext，继续原有跳过/回退", ignored);
            }
            return null;
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
            if (fluidState.isEmpty() || view.fluidVisibility.skipEnclosed(view, pos, fluidState)) {
                return;
            }

            RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);
            matrices.push();
            matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());
            try {
                blockRenderManager.renderFluid(pos, view, new FluidVertexConsumer(collector.consumerFor(LayerKey.fromFluid(fluidLayer)), matrices.peek().getPositionMatrix()), state, fluidState);
            } finally {
                matrices.pop();
            }
        }

        static void renderBlockModel(
                MeshCollector collector,
                BlockRenderManager blockRenderManager,
                @Nullable WorldMesherRenderContext fabricContext,
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
            boolean rendered = false;
            if (fabricContext != null
                    && (!model.isVanillaAdapter() || QuickLitematicaPreviewCompat.isContinuityModel(model))) {
                matrices.push();
                try {
                    fabricContext.tessellateBlock(view, state, pos, model, matrices);
                    rendered = true;
                } catch (Throwable t) {
                    LOGGER.warn("QuickCraft fallback to modelRenderer for {}", state, t);
                } finally {
                    matrices.pop();
                }
            }
            if (!rendered) {
                RenderLayer blockLayer = RenderLayers.getBlockLayer(state);
                blockRenderManager.getModelRenderer().render(
                        view,
                        model,
                        state,
                        pos,
                        matrices,
                        collector.consumerFor(state.isOf(Blocks.NETHER_PORTAL) ? LayerKey.PORTAL : LayerKey.from(blockLayer)),
                        true,
                        random,
                        state.getRenderingSeed(pos),
                        OverlayTexture.DEFAULT_UV
                );
            }

            matrices.pop();
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
                return RenderLayer.getTranslucent();
            }
        },
        PORTAL(5) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTranslucent();
            }
        },
        FLUID(6) {
            @Override
            RenderLayer renderLayer() {
                return RenderLayer.getTranslucent();
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
            if (layer == RenderLayer.getTranslucent() || layer.isTranslucent()) {
                return TRANSLUCENT;
            }
            return SOLID;
        }

        static LayerKey fromFluid(RenderLayer layer) {
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

    static final class MeshCollector {
        final EnumMap<LayerKey, RecordingVertexConsumer> consumers = new EnumMap<>(LayerKey.class);
        final int vertexLimit = QuickLitematicaPreviewVertexBudget.vertexLimit();
        int vertexCount;

        VertexConsumer consumerFor(RenderLayer renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        VertexConsumer consumerFor(LayerKey layer) {
            // 这些材质共用透明管线，必须一起排序，避免水先写深度后遮掉玻璃。
            layer = layer.isTranslucent() ? LayerKey.TRANSLUCENT : layer;
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            QuickLitematicaPreviewVertexBudget.checkVertexCount(this.vertexCount, this.vertexLimit);

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, light, nx, ny, nz);
        }

        boolean shouldPublishBatch() {
            return QuickLitematicaPreviewMeshBatches.shouldPublish(this.consumers);
        }

        List<LayerMesh> drainReadyMeshes() {
            return this.drainMeshes(false);
        }

        List<LayerMesh> drainAllMeshes() {
            return this.drainMeshes(true);
        }

        List<LayerMesh> drainMeshes(boolean finalBatch) {
            return QuickLitematicaPreviewMeshBatches.drain(this.consumers, finalBatch);
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
            return this;
        }

        @Override
        public VertexConsumer overlay(int uv) {
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
            this.collector.addVertex(this.vertices, this.x, this.y, this.z, this.argb, this.u, this.v, this.light, x, y, z);
            this.light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            return this;
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.collector.addVertex(this.vertices, x, y, z, color, u, v, light, normalX, normalY, normalZ);
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

        void add(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            this.ensureCapacity(this.position + QUANTIZED_VERTEX_BYTES);
            this.writeInt(Float.floatToIntBits(x));
            this.writeInt(Float.floatToIntBits(y));
            this.writeInt(Float.floatToIntBits(z));
            this.writeInt(argb);
            this.writeInt(Float.floatToIntBits(u));
            this.writeInt(Float.floatToIntBits(v));
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
            this.bytes = Arrays.copyOf(this.bytes,
                    QuickLitematicaPreviewVertexBudget.growCapacity(this.bytes.length, needed));
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
                    LOGGER.warn("动态实体实例化返回空：位置={},{},{}", this.x, this.y, this.z);
                    return null;
                }

                QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(entity, this.x, this.y, this.z);
                int light = MinecraftClient.getInstance().getEntityRenderDispatcher().getLight(entity, 0.0F);
                return new RenderedEntity(entity, this.x, this.y, this.z, light);
            } catch (Throwable ignored) {
                LOGGER.warn("动态实例化失败：类型=EntityData，位置={},{},{}", this.x, this.y, this.z, ignored);
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
                    LOGGER.warn("方块实体工厂返回空：位置={},{},{}，状态={}", this.x, this.y, this.z, state);
                    return null;
                }

                setPreviewBlockEntityState(blockEntity, state);
                if (!this.entityNbt.isEmpty()) {
                    blockEntity.read(this.entityNbt.copy(), world.getRegistryManager());
                }
                blockEntity.setWorld(world);
                return blockEntity;
            } catch (Throwable ignored) {
                LOGGER.warn("动态实例化失败：类型=BlockEntityData，位置={},{},{}", this.x, this.y, this.z, ignored);
                return null;
            }
        }
    }

    /**
     * 动态内容视口剔除器：把模型空间点变换到 framebuffer 像素，判定是否落在预览框（含安全余量）内。
     * 预览框外对象本就被 scissor 裁掉看不见，剔除纯属减负，不改可见效果。
     */
    static final class ViewportCuller {
        final Matrix4f modelView;
        final Matrix4f projection;
        final float framebufferWidth;
        final float framebufferHeight;
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;
        final Vector4f scratch = new Vector4f();

        ViewportCuller(Matrix4f modelView, Matrix4f projection, MinecraftClient client, int viewX, int viewY, int viewSize) {
            this.modelView = modelView;
            this.projection = projection;
            this.framebufferWidth = client.getWindow().getFramebufferWidth();
            this.framebufferHeight = client.getWindow().getFramebufferHeight();
            int screenHeight = client.currentScreen == null ? client.getWindow().getScaledHeight() : client.currentScreen.height;
            float guiScale = screenHeight > 0 ? this.framebufferHeight / screenHeight : 1.0F;
            // 安全余量：实体/方块实体模型可能延伸出位置点，给 48px 覆盖盔甲架/画等大模型。
            float margin = 48.0F;
            this.minX = (viewX - margin) * guiScale;
            this.maxX = (viewX + viewSize + margin) * guiScale;
            this.minY = (viewY - margin) * guiScale;
            this.maxY = (viewY + viewSize + margin) * guiScale;
        }

        boolean isOutside(float x, float y, float z) {
            // 模型空间 -> 视图空间 -> 裁剪空间 -> NDC -> framebuffer 像素
            this.scratch.set(x, y, z, 1.0F);
            this.modelView.transform(this.scratch);
            this.projection.transform(this.scratch);
            float w = this.scratch.w;
            if (w == 0.0F) {
                return false;
            }
            float ndcX = this.scratch.x / w;
            float ndcY = this.scratch.y / w;
            float pixelX = (ndcX + 1.0F) * 0.5F * this.framebufferWidth;
            float pixelY = (1.0F - ndcY) * 0.5F * this.framebufferHeight;
            return pixelX < this.minX || pixelX > this.maxX || pixelY < this.minY || pixelY > this.maxY;
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
            this.blockStates = copyPreviewPositions(blockStates);
        }

        void setBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
            this.blockEntities = copyPreviewPositions(blockEntities);
        }

        @Override
        public long getTime() {
            return QuickLitematicaPreviewBeacons.isInitializing() ? 0L : super.getTime();
        }

        @Override
        public int getTopY(net.minecraft.world.Heightmap.Type type, int x, int z) {
            return QuickLitematicaPreviewBeacons.isInitializing()
                    ? QuickLitematicaPreviewBeacons.initializationTop() : super.getTopY(type, x, z);
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

    // ---- 静态顶点解码与 octahedral 8-bit 法线编码 ----

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
            int light = CacheFile.readInt(quantized, offset + 24);
            CacheFile.decodeNormal(CacheFile.readShort(quantized, offset + 28), normal);
            builder.vertex(x, y, z, argb, u, v, OverlayTexture.DEFAULT_UV, light, normal[0], normal[1], normal[2]);
        }
    }
    static Path entryPath(DirectoryEntry entry) { return entry.getFullPath().toPath(); }

    static void drawOutlinedBox(DrawContext context, int x, int y, int w, int h, int fill, int border) { RenderUtils.drawOutlinedBox(x, y, w, h, fill, border); }

    static void registerSpecialRenderer() {}

    static void renderShaderDisabled(DrawContext context, int x, int y, int size) {
        MinecraftClient client = MinecraftClient.getInstance();
        RenderUtils.drawOutlinedBox(x, y, size, size, 0xB0101010, 0xFF707070);
        Text message = Text.translatable("quickcraft.message.litematica.preview_3d.shader_disabled");
        var lines = client.textRenderer.wrapLines(message, Math.max(1, size - 16));
        int lineStep = client.textRenderer.fontHeight + 2;
        int textY = y + (size - lines.size() * lineStep) / 2;
        for (var line : lines) {
            context.drawCenteredTextWithShadow(client.textRenderer, line, x + size / 2, textY, 0xFFFFCC55);
            textY += lineStep;
        }
    }

    static void translateToScreen(Matrix4fStack matrixStack, MinecraftClient client, float x, float y) {
        int screenWidth = client.currentScreen == null ? client.getWindow().getScaledWidth() : client.currentScreen.width;
        int screenHeight = client.currentScreen == null ? client.getWindow().getScaledHeight() : client.currentScreen.height;
        matrixStack.translate((2.0F * x - screenWidth) / screenHeight, -(2.0F * y - screenHeight) / screenHeight, 0.0F);
    }
    static boolean writeSchematic(LitematicaSchematic schematic, Path parent, String name) { return schematic.writeToFile(parent.toFile(), name, true); }

    static boolean containsCompound(NbtCompound tag, String name) { return tag.contains(name, NbtElement.COMPOUND_TYPE); }
    static boolean containsList(NbtCompound tag, String name) { return tag.contains(name, NbtElement.LIST_TYPE); }
    static NbtCompound readCompound(NbtCompound tag, String name) { return tag.getCompound(name); }
    static NbtList readCompoundList(NbtCompound tag, String name) { return tag.getList(name, NbtElement.COMPOUND_TYPE); }
    static NbtCompound readListCompound(NbtList list, int index) { return list.getCompound(index); }
    static String readString(NbtCompound tag, String name) { return tag.getString(name); }
    static Vec3d entityPos(LitematicaSchematic.EntityInfo info) { return info.posVec; }
    static NbtCompound entityNbt(LitematicaSchematic.EntityInfo info) { return info.nbt; }
    static NbtCompound previewBlockEntityNbt(LitematicaSchematic schematic, String region, BlockPos pos) {
        Map<BlockPos, NbtCompound> source = schematic.getBlockEntityMapForRegion(region);
        return source == null ? new NbtCompound() : source.getOrDefault(pos, new NbtCompound());
    }
}
