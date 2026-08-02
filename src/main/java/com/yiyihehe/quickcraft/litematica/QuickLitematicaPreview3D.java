package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.FakeLightingProvider;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.WorldMesherRenderContext;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
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
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Litematica 文件浏览器里的真实方块模型 3D 预览。
 * 构建阶段调用 Minecraft 自带方块渲染器，把材质、异形模型、透明层和流体都录成可缓存的 CPU 顶点。
 */
public final class QuickLitematicaPreview3D {
    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderManager 多线程竞争。
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    // v14：UV 恢复 float32，并保留完整 light 坐标，避免斜视面跨出方块图集 sprite 边界。
    // v13：回退箱子静态化（entity atlas 纹理与方块 VBO 不兼容，紫色方块）；保留 GZIP+量化+视口剔除+邻居登记修复。
    // v12：箱子顶点静态化到独立 VBO，缓存追加 chestVertices 字段。
    // v11：保留 v10 的 GZIP + 顶点量化；箱子方块实体改回动态渲染，避免 chest atlas 被写进方块 VBO。
    // 升版本会让旧缓存一次性失效；之后 mod 版本号变化不再清缓存（token 已不含 mod 版本）。
    private static final int CACHE_FORMAT_VERSION = 14;
    private static final int CACHE_MAGIC = 0x51435033; // QCP3
    private static final String CACHE_DIR_NAME = "litematica-preview-cache";
    private static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    private static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v14-full-uv-light-gzip-dynamic-chest-mc1.21";
    private static final int MAX_PREVIEW_SIZE = 512;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    private static final int MAX_UPLOAD_VERTICES = 12_000_000;
    private static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    private static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    private static final int MAX_DYNAMIC_ENTITIES = 8_192;
    private static final double MAX_BLOCK_WIDTH = Math.cos(Math.PI / 6.0) * 2.0;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int VERTEX_BYTES = 44;
    // 静态顶点磁盘编码：12B 位置 + 4B 颜色 + 8B UV(float32×2) + 4B light + 2B 法线(octahedral) = 30B。
    private static final int QUANTIZED_VERTEX_BYTES = 30;
    private static final int MAX_QUANTIZED_LAYER_BYTES = MAX_UPLOAD_VERTICES * QUANTIZED_VERTEX_BYTES;
    private static final int CACHE_IO_CHUNK_BYTES = 1024 * 1024;
    private static final float PROGRESS_START = 0.02F;
    private static final float PROGRESS_MESHING_START = 0.10F;
    private static final float PROGRESS_MESHING_END = 0.80F;
    private static final float PROGRESS_CACHE_WRITE = 0.82F;
    private static final float PROGRESS_STATIC_CACHE_END = 0.93F;
    private static final float PROGRESS_BLOCK_STATES_CACHE_END = 0.95F;
    private static final float PROGRESS_BLOCK_ENTITIES_CACHE_END = 0.99F;
    private static final AtomicBoolean CACHE_DIRECTORY_READY = new AtomicBoolean();
    @Nullable
    private static volatile Path currentCacheDirectory;

    private QuickLitematicaPreview3D() {
    }

    public static Manager init(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui) {
        Manager old = MANAGERS.remove(gui);
        if (old != null) {
            old.close();
        }

        Manager manager = new Manager();
        MANAGERS.put(gui, manager);
        return manager;
    }

    public static void close(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui) {
        Manager manager = MANAGERS.remove(gui);
        if (manager != null) {
            manager.close();
        }
    }

    public static void render(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui, @Nullable DirectoryEntry entry, DrawContext drawContext, int x, int y, int size) {
        if (!QuickCraftConfigs.isLitematica3DPreviewEnabled()) {
            for (Manager manager : MANAGERS.values()) {
                manager.releasePreview();
            }
            return;
        }

        Manager manager = MANAGERS.get(gui);
        if (manager == null) {
            return;
        }

        manager.render(entry, drawContext, x, y, size);
    }

    public static final class Manager implements AutoCloseable {
        @Nullable
        private Preview current;
        @Nullable
        private Path currentPath;
        private final DragState drag = new DragState();
        private int viewX;
        private int viewY;
        private int viewSize;

        private Manager() {
        }

        private void render(@Nullable DirectoryEntry entry, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, Math.min(size, MAX_PREVIEW_SIZE));
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            Path path = entry.getFullPath().toPath().toAbsolutePath().normalize();
            if (!path.equals(this.currentPath)) {
                this.switchTo(path, entry);
            }

            RenderUtils.drawOutlinedBox(this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
            if (this.current != null) {
                this.current.render(drawContext, this.viewX, this.viewY, this.viewSize, this.drag);
            }
        }

        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (!this.canHandleMouse(mouseX, mouseY)) {
                return false;
            }

            this.drag.scaleBy(verticalAmount);
            return true;
        }

        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (this.current == null || !QuickCraftConfigs.isLitematica3DPreviewEnabled()) {
                return false;
            }

            return this.drag.drag(button, deltaX, deltaY);
        }

        public boolean mouseReleased(double mouseX, double mouseY, int mouseButton) {
            if (this.current == null) {
                return false;
            }

            return this.drag.release(mouseButton);
        }

        public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
            if (!this.canHandleMouse(mouseX, mouseY)) {
                return false;
            }

            this.drag.click(mouseButton);
            return true;
        }

        @Override
        public void close() {
            this.clearCurrent();
        }

        private boolean canHandleMouse(double mouseX, double mouseY) {
            return this.current != null
                    && QuickCraftConfigs.isLitematica3DPreviewEnabled()
                    && this.drag.inViewport(mouseX, mouseY);
        }

        private void switchTo(Path path, DirectoryEntry entry) {
            this.clearCurrent();
            this.currentPath = path;
            this.current = Preview.create(entry);
        }

        private void clearCurrent() {
            this.currentPath = null;
            if (this.current != null) {
                this.current.close();
                this.current = null;
            }
            this.drag.stop();
        }

        private void releasePreview() {
            this.clearCurrent();
        }
    }

    private static boolean isSupportedLitematic(DirectoryEntry entry) {
        return entry.getFullPath().isFile() && FileType.fromFile(entry.getFullPath()) == FileType.LITEMATICA_SCHEMATIC;
    }

    private static final class Preview implements AutoCloseable {
        private final Path sourcePath;
        private final Path cachePath;
        private final Path tmpPath;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile MeshData meshData;
        private volatile float progress;
        private volatile State state = State.LOADING;
        @Nullable
        private volatile Future<?> future;
        private final Map<LayerKey, VertexBuffer> vertexBuffers = new EnumMap<>(LayerKey.class);
        private boolean uploadScheduled;

        private Preview(Path sourcePath, Path cachePath, Path tmpPath) {
            this.sourcePath = sourcePath;
            this.cachePath = cachePath;
            this.tmpPath = tmpPath;
        }

        private static Preview create(DirectoryEntry entry) {
            Path sourcePath = entry.getFullPath().toPath().toAbsolutePath().normalize();
            Path cachePath = cachePath(sourcePath);
            Preview preview = new Preview(sourcePath, cachePath, cachePath.resolveSibling(cachePath.getFileName() + ".tmp"));
            preview.progress = PROGRESS_START;
            preview.future = PREVIEW_EXECUTOR.submit(() -> preview.loadOrBuild(entry));
            return preview;
        }

        private void loadOrBuild(DirectoryEntry entry) {
            try {
                this.progress = PROGRESS_START;
                Files.createDirectories(this.cachePath.getParent());
                MeshData cached = CacheFile.read(this.cachePath, this.cancelled);
                if (cached != null) {
                    this.throwIfCancelled();
                    this.meshData = cached;
                    this.progress = 1.0F;
                    this.state = State.READY;
                    return;
                }

                this.state = State.BUILDING;
                MeshData built = MeshBuilder.build(entry, this.cancelled, value -> this.progress = value);
                this.throwIfCancelled();
                if (!built.withinBudget()) {
                    built.closeDynamic();
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    deleteTmpQuietly(this.tmpPath);
                    deleteQuietly(this.cachePath);
                    return;
                }

                CacheFile.writeAtomically(this.tmpPath, this.cachePath, built, this.cancelled, value -> this.progress = value);
                this.throwIfCancelled();
                this.meshData = built;
                this.progress = 1.0F;
                this.state = State.READY;
            } catch (CancellationException ignored) {
                this.state = State.CANCELLED;
                deleteTmpQuietly(this.tmpPath);
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.progress = 1.0F;
                deleteTmpQuietly(this.tmpPath);
                deleteQuietly(this.cachePath);
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                    deleteTmpQuietly(this.tmpPath);
                    return;
                }
                if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    deleteTmpQuietly(this.tmpPath);
                    deleteQuietly(this.cachePath);
                    return;
                }
                this.state = State.FAILED;
                deleteTmpQuietly(this.tmpPath);
                deleteQuietly(this.cachePath);
            }
        }

        private void render(DrawContext context, int x, int y, int size, DragState drag) {
            if (this.state == State.READY && this.meshData != null) {
                this.uploadIfNeeded();
                if (!this.vertexBuffers.isEmpty() || this.meshData.hasDynamicContent() || this.meshData.vertexCount() == 0) {
                    this.drawMesh(context, x, y, size, drag);
                    return;
                }
            }

            this.renderProgress(context, x, y, size);
        }

        private void uploadIfNeeded() {
            if (!this.vertexBuffers.isEmpty() || this.uploadScheduled || this.meshData == null) {
                return;
            }

            this.uploadScheduled = true;
            Runnable upload = () -> {
                MeshData data = this.meshData;
                if (this.cancelled.get() || data == null) {
                    return;
                }

                EnumMap<LayerKey, VertexBuffer> uploaded = new EnumMap<>(LayerKey.class);
                try {
                    if (!data.withinBudget()) {
                        this.markTooLarge(data);
                        return;
                    }

                    for (LayerMesh layerMesh : data.layers()) {
                        if (layerMesh.vertexCount() == 0) {
                            continue;
                        }

                        VertexBuffer buffer = uploadLayer(layerMesh);
                        if (buffer != null) {
                            uploaded.put(layerMesh.layer(), buffer);
                        }
                    }

                    if (this.cancelled.get()) {
                        uploaded.values().forEach(VertexBuffer::close);
                        return;
                    }

                    this.closeBuffers();
                    this.vertexBuffers.putAll(uploaded);
                    data.releaseStaticVertices();
                } catch (Throwable e) {
                    uploaded.values().forEach(buffer -> {
                        if (!buffer.isClosed()) {
                            buffer.close();
                        }
                    });
                    this.releaseMeshData();
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                }
            };

            if (RenderSystem.isOnRenderThread()) {
                upload.run();
            } else {
                RenderSystem.recordRenderCall(upload::run);
            }
        }

        private void markTooLarge(@Nullable MeshData data) {
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.state = State.TOO_LARGE;
            this.progress = 1.0F;
            this.closeBuffers();
            deleteTmpQuietly(this.tmpPath);
            deleteQuietly(this.cachePath);
        }

        private void releaseMeshData() {
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
        }

        @Nullable
        private static VertexBuffer uploadLayer(LayerMesh layerMesh) {
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

                if (layerMesh.layer() == LayerKey.TRANSLUCENT) {
                    built.sortQuads(allocator, VertexSorter.byDistance(0.0F, 0.0F, 1000.0F));
                }

                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                boolean uploaded = false;
                try {
                    buffer.bind();
                    buffer.upload(built);
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

        private static int allocatorSize(int vertexCount) {
            long bytes = Math.max(256L, (long) vertexCount * VERTEX_BYTES);
            return (int) Math.min(Integer.MAX_VALUE - 8L, bytes);
        }

        private void drawMesh(DrawContext context, int x, int y, int size, DragState drag) {
            MeshData data = this.meshData;
            MinecraftClient client = MinecraftClient.getInstance();
            if (data == null || client.currentScreen == null) {
                return;
            }

            context.enableScissor(x + 1, y + 1, x + size - 2, y + size - 2);
            RenderSystem.backupProjectionMatrix();

            float aspectRatio = client.getWindow().getFramebufferWidth() / (float) client.getWindow().getFramebufferHeight();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-aspectRatio, aspectRatio, -1.0F, 1.0F, -1000.0F, 3000.0F), VertexSorter.BY_Z);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            translateToScreen(modelView, client, x + size / 2.0F + drag.dx, y + size / 2.0F + drag.dy);
            modelView.rotate(RotationAxis.POSITIVE_X.rotation(DEFAULT_SLANT_RADIANS));
            modelView.rotate(RotationAxis.POSITIVE_Y.rotation((float) drag.angle));
            float scale = data.scaleFactor(size, client.currentScreen.height) * drag.scale;
            modelView.scale(scale, scale, scale);
            modelView.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);
            RenderSystem.applyModelViewMatrix();

            this.applyLight(modelView);
            this.drawDynamic(data, modelView, x, y, size);
            this.drawBuffers(modelView);

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.restoreProjectionMatrix();
            context.disableScissor();
        }

        private void drawBuffers(Matrix4f modelView) {
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                VertexBuffer buffer = this.vertexBuffers.get(layer);
                if (buffer == null || buffer.isClosed()) {
                    continue;
                }

                RenderLayer renderLayer = layer.renderLayer();
                renderLayer.startDrawing();
                buffer.bind();
                buffer.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                renderLayer.endDrawing();
            }
            VertexBuffer.unbind();
        }

        private void drawDynamic(MeshData data, Matrix4f modelView, int viewX, int viewY, int viewSize) {
            DynamicScene scene = data.dynamicScene();
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
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }
                matrices.push();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    // 高层方块实体渲染会按真实相机做距离判断，预览里的离屏假世界不能走那条路径。
                    renderBlockEntity(client, entity, matrices, client.getBufferBuilders().getEntityVertexConsumers());
                } catch (Throwable ignored) {
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
                            entity.entity().getYaw(0.0F),
                            0.0F,
                            matrices,
                            client.getBufferBuilders().getEntityVertexConsumers(),
                            entity.light()
                    );
                } catch (Throwable ignored) {
                }
            });
            // BE 和实体共用同一个 EntityVertexConsumers，一次 flush 提交所有动态顶点。
            this.flushDynamic();
        }

        private void flushDynamic() {
            MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers().draw();
        }

        private void applyLight(Matrix4f viewMatrix) {
            Matrix4f lightTransform = new Matrix4f(viewMatrix);
            Vector4f lightDirection = new Vector4f(0.0F, 0.35F, 0.25F, 0.0F);
            lightTransform.invert();
            lightDirection.mul(lightTransform);

            Vector3f transformed = new Vector3f(lightDirection.x, lightDirection.y, lightDirection.z);
            RenderSystem.setShaderLights(transformed, transformed);
        }

        private void renderProgress(DrawContext context, int x, int y, int size) {
            int barWidth = Math.max(24, size - 12);
            int barX = x + (size - barWidth) / 2;
            int barY = y + size / 2 - 5;
            int fill = Math.max(0, Math.min(barWidth - 2, (int) ((barWidth - 2) * this.progress)));
            int textColor = this.state == State.FAILED || this.state == State.TOO_LARGE ? 0xFFFF7777 : 0xFFDDDDDD;
            String text = switch (this.state) {
                case FAILED -> StringUtils.translate("quickcraft.litematica.preview_3d.failed");
                case TOO_LARGE -> StringUtils.translate("quickcraft.litematica.preview_3d.too_large");
                default -> StringUtils.translate("quickcraft.litematica.preview_3d.rendering");
            };

            context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x + size / 2, barY - 14, textColor);
            RenderUtils.drawOutlinedBox(barX, barY, barWidth, 10, 0xB0000000, 0xFF707070);
            RenderUtils.drawRect(barX + 1, barY + 1, fill, 8, this.state == State.FAILED || this.state == State.TOO_LARGE ? 0xFFAA3333 : 0xFF4DB36A);
        }

        @Override
        public void close() {
            this.cancelled.set(true);
            Future<?> task = this.future;
            if (task != null) {
                task.cancel(true);
            }

            deleteTmpQuietly(this.tmpPath);
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.closeBuffersOnRenderThread();
        }

        private void closeBuffersOnRenderThread() {
            Runnable close = this::closeBuffers;
            if (RenderSystem.isOnRenderThread()) {
                close.run();
            } else {
                RenderSystem.recordRenderCall(close::run);
            }
        }

        private void closeBuffers() {
            this.vertexBuffers.values().forEach(buffer -> {
                if (!buffer.isClosed()) {
                    buffer.close();
                }
            });
            this.vertexBuffers.clear();
        }

        private void throwIfCancelled() {
            if (this.isCancelled()) {
                throw new CancellationException();
            }
        }

        private boolean isCancelled() {
            return this.cancelled.get() || Thread.currentThread().isInterrupted();
        }
    }

    private static final class PreviewTooLargeException extends RuntimeException {
    }

    private static boolean isPreviewTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PreviewTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // 渲染方块实体到指定 VertexConsumerProvider。动态 BE 会走各自专用 atlas，不能录进普通方块 VBO。
    private static <T extends BlockEntity> void renderBlockEntity(MinecraftClient client, T entity, MatrixStack matrices, VertexConsumerProvider consumers) {
        BlockEntityRenderer<T> renderer = client.getBlockEntityRenderDispatcher().get(entity);
        if (renderer == null) {
            return;
        }

        int light = entity.getWorld() != null
                ? WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos())
                : LightmapTextureManager.MAX_LIGHT_COORDINATE;
        renderer.render(entity, 0.0F, matrices, consumers, light, OverlayTexture.DEFAULT_UV);
    }

    private static void translateToScreen(Matrix4fStack matrixStack, MinecraftClient client, float x, float y) {
        int screenWidth = client.currentScreen == null ? client.getWindow().getScaledWidth() : client.currentScreen.width;
        int screenHeight = client.currentScreen == null ? client.getWindow().getScaledHeight() : client.currentScreen.height;
        matrixStack.translate((2.0F * x - screenWidth) / screenHeight, -(2.0F * y - screenHeight) / screenHeight, 0.0F);
    }

    private static Path cachePath(Path sourcePath) {
        return cacheDirectory().resolve(cacheKey(sourcePath) + ".qcp3d");
    }

    private static String cacheKey(Path sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, sourcePath.toString());
            updateDigest(digest, Long.toString(Files.size(sourcePath)));
            updateDigest(digest, Long.toString(Files.getLastModifiedTime(sourcePath).toMillis()));
            updateDigest(digest, Integer.toString(CACHE_FORMAT_VERSION));
            updateDigest(digest, SharedConstants.getGameVersion().getName());
            updateDigest(digest, CACHE_RENDER_MARKER);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return Integer.toHexString(Objects.hash(sourcePath.toString(), System.currentTimeMillis()));
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static Path cacheDirectory() {
        Path cacheDir = currentCacheDirectory;
        if (cacheDir != null) {
            return cacheDir;
        }

        synchronized (QuickLitematicaPreview3D.class) {
            cacheDir = currentCacheDirectory;
            if (cacheDir != null) {
                return cacheDir;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            Path runDirectory = client.runDirectory.toPath();
            cacheDir = runDirectory.resolve(CACHE_DIR_NAME);
            currentCacheDirectory = cacheDir;
            if (CACHE_DIRECTORY_READY.compareAndSet(false, true)) {
                prepareCacheDirectory(cacheDir);
            }
            return cacheDir;
        }
    }

    private static void prepareCacheDirectory(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            Path versionFile = cacheDir.resolve(CACHE_VERSION_FILE_NAME);
            String currentVersion = currentCacheVersionToken();
            String storedVersion = readCacheVersion(versionFile);
            if (!currentVersion.equals(storedVersion)) {
                clearCacheDirectory(cacheDir);
                Files.writeString(versionFile, currentVersion, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private static String currentCacheVersionToken() {
        // 不含 mod 版本号：只有磁盘格式真正改变时才应清缓存，mod 版本升级不应触发清理。
        return CACHE_FORMAT_VERSION + "|" + CACHE_RENDER_MARKER;
    }

    @Nullable
    private static String readCacheVersion(Path versionFile) {
        if (!Files.isRegularFile(versionFile)) {
            return null;
        }

        try {
            return Files.readString(versionFile, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void clearCacheDirectory(Path cacheDir) {
        try (var paths = Files.list(cacheDir)) {
            paths.forEach(QuickLitematicaPreview3D::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursivelyQuietly(Path path) {
        if (Files.isDirectory(path)) {
            try (var paths = Files.walk(path)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(QuickLitematicaPreview3D::deleteQuietly);
            } catch (IOException ignored) {
            }
            return;
        }

        deleteQuietly(path);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void deleteTmpQuietly(Path path) {
        if (path.getFileName() != null && path.getFileName().toString().endsWith(".tmp")) {
            deleteQuietly(path);
        }
    }

    private enum State {
        LOADING,
        BUILDING,
        READY,
        FAILED,
        TOO_LARGE,
        CANCELLED
    }

    private static final class DragState {
        private int x;
        private int y;
        private int size;
        private int activeButton = -1;
        private double angle = Math.PI / 4.0;
        private float scale = 1.0F;
        private float dx;
        private float dy;

        private void setViewport(int x, int y, int size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }

        private boolean inViewport(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.size && mouseY < this.y + this.size;
        }

        private void click(int button) {
            this.activeButton = button;
        }

        private boolean drag(int button, double deltaX, double deltaY) {
            if (this.activeButton != button) {
                return false;
            }

            if (button == 0) {
                this.angle += deltaX * 0.015;
                return true;
            }

            if (button == 1) {
                this.dx += (float) deltaX;
                this.dy += (float) deltaY;
                return true;
            }

            return false;
        }

        private boolean release(int button) {
            boolean handled = this.activeButton == button;
            if (handled) {
                this.activeButton = -1;
            }
            return handled;
        }

        private void scaleBy(double amount) {
            this.scale = Math.max(0.25F, Math.min(6.0F, (float) (this.scale * Math.exp(amount * 0.12))));
        }

        private void stop() {
            this.activeButton = -1;
        }
    }

    private static final class MeshBuilder {
        private static MeshData build(DirectoryEntry entry, AtomicBoolean cancelled, ProgressSink progressSink) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                throw new IllegalStateException("Litematica preview needs a loaded client world");
            }

            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(entry.getDirectory(), entry.getName(), FileType.LITEMATICA_SCHEMATIC);
            throwIfCancelled(cancelled);
            if (schematic == null) {
                throw new IllegalStateException("Cannot read litematic file");
            }

            progressSink.set(PROGRESS_MESHING_START);

            Bounds bounds = Bounds.from(schematic.getAreas().values());
            MeshCollector collector = new MeshCollector();
            Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
            List<BlockEntityData> blockEntities = new ArrayList<>();
            List<EntityData> entities = new ArrayList<>();
            Map<BlockState, Boolean> blockEntityRendererCache = new HashMap<>();
            long total = Math.max(1L, totalVolume(schematic.getAreas().values()));
            long visited = 0L;

            BlockRenderManager blockRenderManager = client.getBlockRenderManager();
            MatrixStack matrices = new MatrixStack();
            Random random = Random.createLocal();
            WorldMesherRenderContext fabricContext = createFabricContext(collector, client.world);

            for (String regionName : schematic.getAreas().keySet()) {
                throwIfCancelled(cancelled);
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                Box area = schematic.getAreas().get(regionName);
                if (container == null || area == null) {
                    continue;
                }

                RegionBlockView view = new RegionBlockView(container, area);
                RegionBounds regionBounds = RegionBounds.from(area);
                Map<BlockPos, NbtCompound> schematicBlockEntities = schematic.getBlockEntityMapForRegion(regionName);
                recordEntities(blockStates, entities, view, schematic, regionName, area, bounds, cancelled);

                for (BlockPos pos : BlockPos.iterate(regionBounds.min(), regionBounds.max())) {
                    throwIfCancelled(cancelled);
                    BlockState state = view.getBlockState(pos);
                    if (!state.isAir()) {
                        BlockPos renderPos = pos.subtract(bounds.min());
                        recordBlockEntity(blockStates, blockEntities, blockEntityRendererCache, view, state, schematicBlockEntities, pos, renderPos, bounds);
                        renderFluidIfPresent(collector, blockRenderManager, matrices, view, state, pos, renderPos);
                        renderBlockModel(collector, blockRenderManager, fabricContext, matrices, view, state, pos, renderPos, random);
                    }

                    visited++;
                    if ((visited & 0x3FF) == 0L) {
                        progressSink.set(PROGRESS_MESHING_START + (PROGRESS_MESHING_END - PROGRESS_MESHING_START)
                                * Math.min(1.0F, visited / (float) total));
                    }
                }
            }

            progressSink.set(PROGRESS_MESHING_END);
            List<LayerMesh> layers = collector.toMeshes();
            int vertices = vertexCount(layers);
            if (vertices > MAX_UPLOAD_VERTICES
                    || blockStates.size() > MAX_DYNAMIC_BLOCK_STATES
                    || blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES
                    || entities.size() > MAX_DYNAMIC_ENTITIES) {
                throw new PreviewTooLargeException();
            }
            return new MeshData(layers, new ArrayList<>(blockStates.values()), blockEntities, entities, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        }

        private static int vertexCount(List<LayerMesh> layers) {
            int count = 0;
            for (LayerMesh layer : layers) {
                count += layer.vertexCount();
            }
            return count;
        }

        private static long totalVolume(Collection<Box> boxes) {
            long total = 0L;
            for (Box box : boxes) {
                RegionBounds bounds = RegionBounds.from(box);
                total += bounds.volume();
            }
            return total;
        }

        @Nullable
        private static WorldMesherRenderContext createFabricContext(MeshCollector collector, ClientWorld world) {
            try {
                if (RendererAccess.INSTANCE.getRenderer() instanceof IndigoRenderer) {
                    DummyWorld dummyWorld = DummyWorld.fromWorld(world);
                    return new WorldMesherRenderContext(dummyWorld, layer -> collector.consumerFor(layer));
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static void recordBlockEntity(
                Map<BlockPos, BlockStateData> blockStates,
                List<BlockEntityData> blockEntities,
                Map<BlockState, Boolean> blockEntityRendererCache,
                RegionBlockView view,
                BlockState state,
                @Nullable Map<BlockPos, NbtCompound> schematicBlockEntities,
                BlockPos schematicPos,
                BlockPos renderPos,
                Bounds bounds
        ) {
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
                return;
            }

            if (!blockEntityRendererCache.computeIfAbsent(state, key -> hasPreviewBlockEntityRenderer(provider, key, renderPos))) {
                return;
            }

            recordDynamicBlockState(blockStates, state, renderPos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborSchematicPos = schematicPos.offset(direction);
                BlockState neighborState = view.getBlockState(neighborSchematicPos);
                if (!neighborState.isAir()) {
                    recordDynamicBlockState(blockStates, neighborState, neighborSchematicPos.subtract(bounds.min()));
                }
            }

            NbtCompound nbt = schematicBlockEntities == null
                    ? new NbtCompound()
                    : schematicBlockEntities.getOrDefault(schematicPos, new NbtCompound());
            NbtCompound entityNbt = sanitizeBlockEntityNbt(nbt);
            entityNbt.putInt("x", renderPos.getX());
            entityNbt.putInt("y", renderPos.getY());
            entityNbt.putInt("z", renderPos.getZ());
            blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state), entityNbt));
            if (blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES) {
                throw new PreviewTooLargeException();
            }
        }

        private static boolean hasPreviewBlockEntityRenderer(BlockEntityProvider provider, BlockState state, BlockPos renderPos) {
            BlockEntity blockEntity = provider.createBlockEntity(renderPos, state);
            if (blockEntity == null) {
                return false;
            }

            blockEntity.setCachedState(state);
            return MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(blockEntity) != null;
        }

        private static NbtCompound sanitizeBlockEntityNbt(NbtCompound nbt) {
            NbtCompound sanitized = nbt.copy();
            // 3D 预览只需要容器外观，不需要把箱子/潜影盒内部物品也带进缓存和动态渲染。
            sanitized.remove("Items");
            return sanitized;
        }

        private static void recordDynamicBlockState(Map<BlockPos, BlockStateData> blockStates, BlockState state, BlockPos renderPos) {
            if (blockStates.size() >= MAX_DYNAMIC_BLOCK_STATES && !blockStates.containsKey(renderPos)) {
                throw new PreviewTooLargeException();
            }

            blockStates.put(renderPos.toImmutable(), new BlockStateData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state)));
        }

        private static void recordEntities(
                Map<BlockPos, BlockStateData> blockStates,
                List<EntityData> entities,
                RegionBlockView view,
                LitematicaSchematic schematic,
                String regionName,
                Box area,
                Bounds bounds,
                AtomicBoolean cancelled
        ) {
            List<LitematicaSchematic.EntityInfo> regionEntities = schematic.getEntityListForRegion(regionName);
            if (regionEntities == null || regionEntities.isEmpty()) {
                return;
            }

            BlockPos regionOrigin = area.getPos1() == null ? BlockPos.ORIGIN : area.getPos1();
            for (LitematicaSchematic.EntityInfo info : regionEntities) {
                throwIfCancelled(cancelled);
                double x = info.posVec.x + regionOrigin.getX() - bounds.min().getX();
                double y = info.posVec.y + regionOrigin.getY() - bounds.min().getY();
                double z = info.posVec.z + regionOrigin.getZ() - bounds.min().getZ();
                entities.add(new EntityData(x, y, z, copyEntityNbtAt(info.nbt, x, y, z)));
                recordEntityNearbyBlockStates(blockStates, view, bounds, x, y, z);
                if (entities.size() > MAX_DYNAMIC_ENTITIES) {
                    throw new PreviewTooLargeException();
                }
            }
        }

        private static void recordEntityNearbyBlockStates(Map<BlockPos, BlockStateData> blockStates, RegionBlockView view, Bounds bounds, double x, double y, double z) {
            BlockPos center = BlockPos.ofFloored(x, y, z);
            // 展示框/画等挂载实体会查询附着方块（facing 反方向）；假世界缺邻居会被原版判成 invalid position。
            // 只登记 6 方向邻居（覆盖任意 facing），不登记 center 本身（实体位置通常是 air）。
            // 原 3x3x3=27 个过多，导致 blockStates 暴涨、缓存膨胀、首次渲染变慢。
            for (Direction direction : Direction.values()) {
                BlockPos renderPos = center.offset(direction);
                BlockPos schematicPos = renderPos.add(bounds.min());
                BlockState state = view.getBlockState(schematicPos);
                if (!state.isAir()) {
                    recordDynamicBlockState(blockStates, state, renderPos);
                }
            }
        }

        private static NbtCompound copyEntityNbtAt(NbtCompound source, double x, double y, double z) {
            NbtCompound copy = source.copy();
            NbtList pos = new NbtList();
            pos.add(NbtDouble.of(x));
            pos.add(NbtDouble.of(y));
            pos.add(NbtDouble.of(z));
            copy.put("Pos", pos);
            return copy;
        }

        private static void renderFluidIfPresent(
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

            RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);
            matrices.push();
            matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());
            blockRenderManager.renderFluid(pos, view, new FluidVertexConsumer(collector.consumerFor(fluidLayer), matrices.peek().getPositionMatrix()), state, fluidState);
            matrices.pop();
        }

        private static void renderBlockModel(
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
            if (fabricContext != null && !model.isVanillaAdapter()) {
                fabricContext.tessellateBlock(view, state, pos, model, matrices);
            } else {
                RenderLayer blockLayer = RenderLayers.getBlockLayer(state);
                blockRenderManager.getModelRenderer().render(
                        view,
                        model,
                        state,
                        pos,
                        matrices,
                        collector.consumerFor(blockLayer),
                        true,
                        random,
                        state.getRenderingSeed(pos),
                        OverlayTexture.DEFAULT_UV
                );
            }

            matrices.pop();
        }

        private static void throwIfCancelled(AtomicBoolean cancelled) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }
    }

    private enum LayerKey {
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
        };

        private static final LayerKey[] DRAW_ORDER = {SOLID, CUTOUT_MIPPED, CUTOUT, TRIPWIRE, TRANSLUCENT};
        private final int id;

        LayerKey(int id) {
            this.id = id;
        }

        abstract RenderLayer renderLayer();

        private static LayerKey from(RenderLayer layer) {
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

        @Nullable
        private static LayerKey byId(int id) {
            for (LayerKey value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return null;
        }
    }

    private static final class MeshCollector {
        private final EnumMap<LayerKey, RecordingVertexConsumer> consumers = new EnumMap<>(LayerKey.class);
        private int vertexCount;

        private VertexConsumer consumerFor(RenderLayer renderLayer) {
            LayerKey layer = LayerKey.from(renderLayer);
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        private void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            if (this.vertexCount >= MAX_UPLOAD_VERTICES) {
                throw new PreviewTooLargeException();
            }

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, light, nx, ny, nz);
        }

        private List<LayerMesh> toMeshes() {
            List<LayerMesh> meshes = new ArrayList<>();
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                RecordingVertexConsumer consumer = this.consumers.get(layer);
                if (consumer != null && !consumer.vertices.isEmpty()) {
                    meshes.add(new LayerMesh(layer, consumer.vertices.takeBytes()));
                }
            }
            return List.copyOf(meshes);
        }
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private final MeshCollector collector;
        private final QuantizedVertexBuffer vertices = new QuantizedVertexBuffer();
        private float x;
        private float y;
        private float z;
        private int argb = 0xFFFFFFFF;
        private float u;
        private float v;
        private int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        private RecordingVertexConsumer(MeshCollector collector) {
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

    private static final class FluidVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Matrix4f transform;

        private FluidVertexConsumer(VertexConsumer delegate, Matrix4f transform) {
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

    private static final class QuantizedVertexBuffer {
        private byte[] bytes = new byte[QUANTIZED_VERTEX_BYTES * 256];
        private int position;

        private boolean isEmpty() {
            return this.position == 0;
        }

        private void add(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
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

        private byte[] takeBytes() {
            byte[] result = this.bytes.length == this.position ? this.bytes : Arrays.copyOf(this.bytes, this.position);
            this.bytes = new byte[0];
            this.position = 0;
            return result;
        }

        private void ensureCapacity(int needed) {
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

    private record LayerMesh(LayerKey layer, byte[] quantizedVertices) {
        private int vertexCount() {
            return this.quantizedVertices.length / QUANTIZED_VERTEX_BYTES;
        }
    }

    private record BlockStateData(int x, int y, int z, NbtCompound stateNbt) {
        private BlockState state() {
            return NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), this.stateNbt);
        }
    }

    private static final class MeshData {
        private List<LayerMesh> layers;
        private final List<BlockStateData> blockStates;
        private final List<BlockEntityData> blockEntities;
        private final List<EntityData> entities;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        @Nullable
        private DynamicScene dynamicScene;

        private MeshData(List<LayerMesh> layers, List<BlockStateData> blockStates, List<BlockEntityData> blockEntities, List<EntityData> entities, int sizeX, int sizeY, int sizeZ) {
            this.layers = layers;
            this.blockStates = List.copyOf(blockStates);
            this.blockEntities = List.copyOf(blockEntities);
            this.entities = List.copyOf(entities);
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        private List<LayerMesh> layers() {
            return this.layers;
        }

        private int sizeX() {
            return this.sizeX;
        }

        private int sizeY() {
            return this.sizeY;
        }

        private int sizeZ() {
            return this.sizeZ;
        }

        private int vertexCount() {
            int count = 0;
            for (LayerMesh layer : this.layers) {
                count += layer.vertexCount();
            }
            return count;
        }

        private boolean withinBudget() {
            long vertices = 0L;
            for (LayerMesh layer : this.layers) {
                vertices += layer.vertexCount();
                if (vertices > MAX_UPLOAD_VERTICES) {
                    return false;
                }
            }
            return this.blockStates.size() <= MAX_DYNAMIC_BLOCK_STATES
                    && this.blockEntities.size() <= MAX_DYNAMIC_BLOCK_ENTITIES
                    && this.entities.size() <= MAX_DYNAMIC_ENTITIES;
        }

        private void releaseStaticVertices() {
            this.layers = List.of();
        }

        private boolean hasDynamicContent() {
            return !this.blockEntities.isEmpty() || !this.entities.isEmpty();
        }

        private float scaleFactor(int previewSize, int screenHeight) {
            int shortestSide = Math.min(this.sizeX, this.sizeZ);
            int longestSide = Math.max(this.sizeX, this.sizeZ);
            double horizontalSize = shortestSide * MAX_BLOCK_WIDTH + longestSide - shortestSide;
            double verticalSize = longestSide * Math.tan(DEFAULT_SLANT_RADIANS) + this.sizeY;
            return (float) ((previewSize * 2.0) / (Math.max(horizontalSize, verticalSize) * Math.max(1, screenHeight)));
        }

        private DynamicScene dynamicScene() {
            DynamicScene scene = this.dynamicScene;
            if (scene == null) {
                scene = DynamicScene.create(this.blockStates, this.blockEntities, this.entities);
                this.dynamicScene = scene;
            }
            return scene;
        }

        private void closeDynamic() {
            this.dynamicScene = null;
        }
    }

    private record EntityData(double x, double y, double z, NbtCompound entityNbt) {
        @Nullable
        private RenderedEntity instantiate(DummyWorld world) {
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

    private record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    private record BlockEntityData(int x, int y, int z, NbtCompound stateNbt, NbtCompound entityNbt) {
        @Nullable
        private BlockEntity instantiate(DummyWorld world) {
            BlockState state = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), this.stateNbt);
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
                return null;
            }

            BlockPos pos = new BlockPos(this.x, this.y, this.z);
            try {
                BlockEntity blockEntity = provider.createBlockEntity(pos, state);
                if (blockEntity == null) {
                    return null;
                }

                blockEntity.setCachedState(state);
                if (!this.entityNbt.isEmpty()) {
                    blockEntity.read(this.entityNbt.copy(), world.getRegistryManager());
                }
                blockEntity.setWorld(world);
                return blockEntity;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private record DynamicScene(DummyWorld world, Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
        private static DynamicScene create(List<BlockStateData> blockStateData, List<BlockEntityData> blockEntityData, List<EntityData> entityData) {
            if (blockStateData.isEmpty() && blockEntityData.isEmpty() && entityData.isEmpty()) {
                return new DynamicScene(Map.of(), List.of());
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                return new DynamicScene(Map.of(), List.of());
            }

            DummyWorld world = DummyWorld.fromWorld(client.world);
            Map<BlockPos, BlockState> blockStates = new HashMap<>();
            for (BlockStateData data : blockStateData) {
                blockStates.put(new BlockPos(data.x(), data.y(), data.z()), data.state());
            }
            world.setBlockStates(blockStates);

            Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
            for (BlockEntityData data : blockEntityData) {
                BlockEntity blockEntity = data.instantiate(world);
                if (blockEntity != null) {
                    blockEntities.put(blockEntity.getPos(), blockEntity);
                }
            }
            world.setBlockEntities(blockEntities);

            List<RenderedEntity> entities = new ArrayList<>();
            for (EntityData data : entityData) {
                RenderedEntity entity = data.instantiate(world);
                if (entity != null) {
                    entities.add(entity);
                }
            }

            return new DynamicScene(world, Map.copyOf(blockEntities), List.copyOf(entities));
        }

        private boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }

        private DynamicScene(Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
            this(null, blockEntities, entities);
        }
    }

    /**
     * 动态内容视口剔除器：把模型空间点变换到 framebuffer 像素，判定是否落在预览框（含安全余量）内。
     * 预览框外对象本就被 scissor 裁掉看不见，剔除纯属减负，不改可见效果。
     */
    private static final class ViewportCuller {
        private final Matrix4f modelView;
        private final Matrix4f projection;
        private final float framebufferWidth;
        private final float framebufferHeight;
        private final float minX;
        private final float maxX;
        private final float minY;
        private final float maxY;
        private final Vector4f scratch = new Vector4f();

        private ViewportCuller(Matrix4f modelView, Matrix4f projection, MinecraftClient client, int viewX, int viewY, int viewSize) {
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

        private boolean isOutside(float x, float y, float z) {
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

    private record Bounds(BlockPos min, BlockPos max) {
        private static Bounds from(Collection<Box> boxes) {
            BlockPos min = BlockPos.ORIGIN;
            BlockPos max = BlockPos.ORIGIN;
            boolean seen = false;

            for (Box box : boxes) {
                RegionBounds bounds = RegionBounds.from(box);
                if (!seen) {
                    min = bounds.min();
                    max = bounds.max();
                    seen = true;
                } else {
                    min = BlockPos.min(min, bounds.min());
                    max = BlockPos.max(max, bounds.max());
                }
            }

            return new Bounds(min, max);
        }

        private int sizeX() {
            return this.max.getX() - this.min.getX() + 1;
        }

        private int sizeY() {
            return this.max.getY() - this.min.getY() + 1;
        }

        private int sizeZ() {
            return this.max.getZ() - this.min.getZ() + 1;
        }
    }

    private record RegionBounds(BlockPos min, BlockPos max) {
        private static RegionBounds from(Box box) {
            BlockPos pos1 = box.getPos1() == null ? BlockPos.ORIGIN : box.getPos1();
            BlockPos pos2 = box.getPos2() == null ? pos1 : box.getPos2();
            return new RegionBounds(BlockPos.min(pos1, pos2), BlockPos.max(pos1, pos2));
        }

        private long volume() {
            return (long) (this.max.getX() - this.min.getX() + 1)
                    * (this.max.getY() - this.min.getY() + 1)
                    * (this.max.getZ() - this.min.getZ() + 1);
        }
    }

    private static final class RegionBlockView implements BlockRenderView {
        private final RegionBounds bounds;
        private final LitematicaBlockStateContainer blockStateContainer;
        private final MinecraftClient client = MinecraftClient.getInstance();
        private final LightingProvider lightingProvider;

        private RegionBlockView(LitematicaBlockStateContainer container, Box area) {
            this.blockStateContainer = container;
            this.bounds = RegionBounds.from(area);
            ClientWorld world = Objects.requireNonNull(this.client.world, "No loaded world for Litematica preview");
            this.lightingProvider = new FakeLightingProvider(new ChunkCacheSchematic(world, world, BlockPos.ORIGIN, 0));
        }

        @Override
        public float getBrightness(Direction direction, boolean shaded) {
            return Objects.requireNonNull(this.client.world).getBrightness(direction, shaded);
        }

        @Override
        public LightingProvider getLightingProvider() {
            return this.lightingProvider;
        }

        @Override
        public int getColor(BlockPos pos, ColorResolver colorResolver) {
            return Objects.requireNonNull(this.client.world).getColor(pos, colorResolver);
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!PositionUtils.isPositionInsideArea(pos, this.bounds.min(), this.bounds.max())) {
                return LitematicaBlockStateContainer.AIR_BLOCK_STATE;
            }

            BlockPos local = pos.subtract(this.bounds.min());
            return this.blockStateContainer.get(local.getX(), local.getY(), local.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.bounds.max().getY() - this.bounds.min().getY() + 1;
        }

        @Override
        public int getBottomY() {
            return 0;
        }
    }

    private static final class DummyWorld extends WorldSchematic {
        private Map<BlockPos, BlockState> blockStates = Map.of();
        private Map<BlockPos, BlockEntity> blockEntities = Map.of();

        private DummyWorld(MutableWorldProperties properties, DynamicRegistryManager registryManager, RegistryEntry<DimensionType> dimensionEntry, Supplier<Profiler> profiler) {
            super(properties, registryManager, dimensionEntry, profiler, null);
        }

        private static DummyWorld fromWorld(ClientWorld world) {
            return new DummyWorld(world.getLevelProperties(), world.getRegistryManager(), world.getDimensionEntry(), world.getProfilerSupplier());
        }

        private void setBlockStates(Map<BlockPos, BlockState> blockStates) {
            this.blockStates = Map.copyOf(blockStates);
        }

        private void setBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
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

    private interface ProgressSink {
        void set(float value);
    }

    private static final class CacheFile {
        @Nullable
        private static MeshData read(Path path, AtomicBoolean cancelled) {
            if (!Files.isRegularFile(path)) {
                return null;
            }

            try (DataInputStream input = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
                int magic = input.readInt();
                int version = input.readInt();
                String marker = input.readUTF();
                if (magic != CACHE_MAGIC || version != CACHE_FORMAT_VERSION || !CACHE_RENDER_MARKER.equals(marker)) {
                    deleteQuietly(path);
                    return null;
                }

                int sizeX = input.readInt();
                int sizeY = input.readInt();
                int sizeZ = input.readInt();
                int layerCount = input.readInt();
                if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || layerCount < 0 || layerCount > LayerKey.values().length) {
                    deleteQuietly(path);
                    return null;
                }

                List<LayerMesh> layers = new ArrayList<>(layerCount);
                long totalVertices = 0L;
                for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
                    if (isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    LayerKey layer = LayerKey.byId(input.readInt());
                    int vertexCount = input.readInt();
                    totalVertices += Math.max(vertexCount, 0);
                    // GZIP 压缩后无法用文件大小校验顶点数，仅用 MAX_UPLOAD_VERTICES 上界；
                    // 损坏文件会在 readFully 抛 EOFException 被外层 catch 删除。
                    if (layer == null || vertexCount < 0 || totalVertices > MAX_UPLOAD_VERTICES) {
                        deleteQuietly(path);
                        return null;
                    }

                    // 批量读取量化顶点字节，直接存进 LayerMesh，渲染线程再解码进 BufferBuilder。
                    // 直接读取 packed 顶点字节，大文件读取避免逐顶点对象分配。
                    long quantizedBytes = (long) vertexCount * QUANTIZED_VERTEX_BYTES;
                    if (quantizedBytes > MAX_QUANTIZED_LAYER_BYTES || quantizedBytes > Integer.MAX_VALUE - 8L) {
                        deleteQuietly(path);
                        return null;
                    }

                    byte[] quantizedVertices = new byte[(int) quantizedBytes];
                    readFullyCancellable(input, quantizedVertices, cancelled);
                    layers.add(new LayerMesh(layer, quantizedVertices));
                }

                int blockStateCount = input.readInt();
                if (blockStateCount < 0 || blockStateCount > MAX_DYNAMIC_BLOCK_STATES) {
                    deleteQuietly(path);
                    return null;
                }

                List<BlockStateData> blockStates = new ArrayList<>(blockStateCount);
                for (int i = 0; i < blockStateCount; i++) {
                    if ((i & 0x7FF) == 0 && isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    blockStates.add(new BlockStateData(
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                int blockEntityCount = input.readInt();
                if (blockEntityCount < 0 || blockEntityCount > MAX_DYNAMIC_BLOCK_ENTITIES) {
                    deleteQuietly(path);
                    return null;
                }

                List<BlockEntityData> blockEntities = new ArrayList<>(blockEntityCount);
                for (int i = 0; i < blockEntityCount; i++) {
                    if ((i & 0xFF) == 0 && isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    blockEntities.add(new BlockEntityData(
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES)),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                int entityCount = input.readInt();
                if (entityCount < 0 || entityCount > MAX_DYNAMIC_ENTITIES) {
                    deleteQuietly(path);
                    return null;
                }

                List<EntityData> entities = new ArrayList<>(entityCount);
                for (int i = 0; i < entityCount; i++) {
                    if ((i & 0xFF) == 0 && isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    entities.add(new EntityData(
                            input.readDouble(),
                            input.readDouble(),
                            input.readDouble(),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                return new MeshData(List.copyOf(layers), blockStates, blockEntities, entities, sizeX, sizeY, sizeZ);
            } catch (CancellationException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                deleteQuietly(path);
                return null;
            }
        }

        private static void readFullyCancellable(DataInputStream input, byte[] bytes, AtomicBoolean cancelled) throws IOException {
            int offset = 0;
            while (offset < bytes.length) {
                if (isCancelled(cancelled)) {
                    throw new CancellationException();
                }

                int length = Math.min(CACHE_IO_CHUNK_BYTES, bytes.length - offset);
                input.readFully(bytes, offset, length);
                offset += length;
            }
        }

        private static void writeAtomically(Path tmpPath, Path finalPath, MeshData data, AtomicBoolean cancelled, ProgressSink progressSink) throws IOException {
            deleteTmpQuietly(tmpPath);
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath))))) {
                progressSink.set(PROGRESS_CACHE_WRITE);
                output.writeInt(CACHE_MAGIC);
                output.writeInt(CACHE_FORMAT_VERSION);
                output.writeUTF(CACHE_RENDER_MARKER);
                output.writeInt(data.sizeX());
                output.writeInt(data.sizeY());
                output.writeInt(data.sizeZ());
                output.writeInt(data.layers().size());

                long totalStaticBytes = 0L;
                for (LayerMesh layer : data.layers()) {
                    totalStaticBytes += layer.quantizedVertices().length;
                }

                long staticBytesWritten = 0L;
                for (LayerMesh layer : data.layers()) {
                    output.writeInt(layer.layer().id);
                    output.writeInt(layer.vertexCount());
                    byte[] quantized = layer.quantizedVertices();
                    for (int offset = 0; offset < quantized.length; offset += CACHE_IO_CHUNK_BYTES) {
                        if (isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        int length = Math.min(CACHE_IO_CHUNK_BYTES, quantized.length - offset);
                        output.write(quantized, offset, length);
                        staticBytesWritten += length;
                        progressSink.set(progress(PROGRESS_CACHE_WRITE, PROGRESS_STATIC_CACHE_END, staticBytesWritten, totalStaticBytes));
                    }
                }
                progressSink.set(PROGRESS_STATIC_CACHE_END);

                output.writeInt(data.blockStates.size());
                for (int index = 0; index < data.blockStates.size(); index++) {
                    if (isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    BlockStateData blockState = data.blockStates.get(index);
                    output.writeInt(blockState.x());
                    output.writeInt(blockState.y());
                    output.writeInt(blockState.z());
                    NbtIo.writeCompound(blockState.stateNbt(), output);
                    if ((index & 0x7F) == 0 || index + 1 == data.blockStates.size()) {
                        progressSink.set(progress(PROGRESS_STATIC_CACHE_END, PROGRESS_BLOCK_STATES_CACHE_END, index + 1L, data.blockStates.size()));
                    }
                }
                progressSink.set(PROGRESS_BLOCK_STATES_CACHE_END);

                output.writeInt(data.blockEntities.size());
                for (int index = 0; index < data.blockEntities.size(); index++) {
                    if (isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    BlockEntityData blockEntity = data.blockEntities.get(index);
                    output.writeInt(blockEntity.x());
                    output.writeInt(blockEntity.y());
                    output.writeInt(blockEntity.z());
                    NbtIo.writeCompound(blockEntity.stateNbt(), output);
                    NbtIo.writeCompound(blockEntity.entityNbt(), output);
                    if ((index & 0x3F) == 0 || index + 1 == data.blockEntities.size()) {
                        progressSink.set(progress(PROGRESS_BLOCK_STATES_CACHE_END, PROGRESS_BLOCK_ENTITIES_CACHE_END, index + 1L, data.blockEntities.size()));
                    }
                }
                progressSink.set(PROGRESS_BLOCK_ENTITIES_CACHE_END);

                output.writeInt(data.entities.size());
                for (int index = 0; index < data.entities.size(); index++) {
                    if (isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    EntityData entity = data.entities.get(index);
                    output.writeDouble(entity.x());
                    output.writeDouble(entity.y());
                    output.writeDouble(entity.z());
                    NbtIo.writeCompound(entity.entityNbt(), output);
                }
            }

            if (isCancelled(cancelled)) {
                throw new CancellationException();
            }

            try {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static boolean isCancelled(AtomicBoolean cancelled) {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }

        private static float progress(float start, float end, long completed, long total) {
            if (total <= 0L) {
                return end;
            }
            return start + (end - start) * Math.min(1.0F, completed / (float) total);
        }

        // ---- 静态顶点解码与 octahedral 8-bit 法线编码 ----

        // 渲染线程调用：把量化字节数组直接解码进 BufferBuilder，跳过 PreviewVertex 对象。
        private static void decodeQuantizedToBuilder(byte[] quantized, BufferBuilder builder) {
            float[] normal = new float[3];
            for (int offset = 0; offset < quantized.length; offset += QUANTIZED_VERTEX_BYTES) {
                float x = Float.intBitsToFloat(readInt(quantized, offset));
                float y = Float.intBitsToFloat(readInt(quantized, offset + 4));
                float z = Float.intBitsToFloat(readInt(quantized, offset + 8));
                int argb = readInt(quantized, offset + 12);
                float u = Float.intBitsToFloat(readInt(quantized, offset + 16));
                float v = Float.intBitsToFloat(readInt(quantized, offset + 20));
                int light = readInt(quantized, offset + 24);
                decodeNormal(readShort(quantized, offset + 28), normal);
                builder.vertex(x, y, z, argb, u, v, OverlayTexture.DEFAULT_UV, light, normal[0], normal[1], normal[2]);
            }
        }

        private static int readInt(byte[] bytes, int offset) {
            return (bytes[offset] & 0xFF) << 24
                    | (bytes[offset + 1] & 0xFF) << 16
                    | (bytes[offset + 2] & 0xFF) << 8
                    | (bytes[offset + 3] & 0xFF);
        }

        private static short readShort(byte[] bytes, int offset) {
            return (short) ((bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF));
        }

        // 法线 (float x3) -> 2 字节，八面体编码 8-bit/分量。方向光照肉眼不可察觉差异。
        private static short encodeNormal(float nx, float ny, float nz) {
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6F) {
                return 0;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            float denom = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
            float pu = nx / denom;
            float pv = ny / denom;
            if (nz < 0.0F) {
                float newU = (1.0F - Math.abs(pv)) * (pu >= 0.0F ? 1.0F : -1.0F);
                float newV = (1.0F - Math.abs(pu)) * (pv >= 0.0F ? 1.0F : -1.0F);
                pu = newU;
                pv = newV;
            }
            int iu = Math.round(pu * 127.0F);
            int iv = Math.round(pv * 127.0F);
            return (short) ((iu & 0xFF) << 8 | (iv & 0xFF));
        }

        // 2 字节八面体编码 -> 法线，填入复用数组避免分配。
        private static void decodeNormal(short packed, float[] out) {
            int iu = (packed >> 8) & 0xFF;
            int iv = packed & 0xFF;
            int su = iu > 127 ? iu - 256 : iu;
            int sv = iv > 127 ? iv - 256 : iv;
            float pu = su / 127.0F;
            float pv = sv / 127.0F;
            float pz = 1.0F - Math.abs(pu) - Math.abs(pv);
            float nx;
            float ny;
            float nz;
            if (pz < 0.0F) {
                nx = (1.0F - Math.abs(pv)) * (pu >= 0.0F ? 1.0F : -1.0F);
                ny = (1.0F - Math.abs(pu)) * (pv >= 0.0F ? 1.0F : -1.0F);
                nz = pz;
            } else {
                nx = pu;
                ny = pv;
                nz = pz;
            }
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6F) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            out[0] = nx;
            out[1] = ny;
            out[2] = nz;
        }
    }
}
