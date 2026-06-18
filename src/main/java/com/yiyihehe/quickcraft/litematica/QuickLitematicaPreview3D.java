package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.VertexSorter;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUsage;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
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
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Litematica 文件浏览器里的真实方块模型 3D 预览。
 * 构建阶段调用 Minecraft 自带方块渲染器，把材质、异形模型、透明层和流体都录成可缓存的 CPU 顶点。
 */
public final class QuickLitematicaPreview3D {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreview3D.class);
    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    private static final int CACHE_FORMAT_VERSION = 6;
    private static final int CACHE_MAGIC = 0x51435033; // QCP3
    private static final String CACHE_DIR_NAME = "litematica-preview-cache";
    private static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v6-large-detailed-mc1.21.3-dynamic-v2";
    private static final int MAX_PREVIEW_SIZE = 512;
    // 真实模型预览保留一个很高的硬上限，避免极端文件把游戏直接打死。
    private static final int MAX_UPLOAD_VERTICES = 30_000_000;
    private static final int MAX_DYNAMIC_BLOCK_STATES = 100_000;
    private static final int MAX_DYNAMIC_BLOCK_ENTITIES = 4_096;
    private static final int MAX_DYNAMIC_ENTITIES = 4_096;
    private static final double MAX_BLOCK_WIDTH = Math.cos(Math.PI / 6.0) * 2.0;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int VERTEX_BYTES = 44;

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
        private volatile CompletableFuture<Void> future;
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
            preview.future = CompletableFuture.runAsync(() -> preview.loadOrBuild(entry), Util.getMainWorkerExecutor());
            return preview;
        }

        private void loadOrBuild(DirectoryEntry entry) {
            try {
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

                CacheFile.writeAtomically(this.tmpPath, this.cachePath, built, this.cancelled);
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
                this.state = State.FAILED;
                deleteTmpQuietly(this.tmpPath);
                deleteQuietly(this.cachePath);
                LOGGER.warn("QuickCraft Litematica 3D preview failed for {}", this.sourcePath, e);
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
                        if (layerMesh.vertices().isEmpty()) {
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
                    LOGGER.warn("QuickCraft Litematica 3D preview upload failed for {}", this.sourcePath, e);
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
            int allocatorSize = allocatorSize(layerMesh.vertices().size());
            BufferAllocator allocator = new BufferAllocator(allocatorSize);
            try {
                BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
                for (PreviewVertex vertex : layerMesh.vertices()) {
                    builder.vertex(
                            vertex.x(),
                            vertex.y(),
                            vertex.z(),
                            vertex.argb(),
                            vertex.u(),
                            vertex.v(),
                            vertex.overlay(),
                            vertex.light(),
                            vertex.nx(),
                            vertex.ny(),
                            vertex.nz()
                    );
                }

                var built = builder.endNullable();
                if (built == null) {
                    return null;
                }

                if (layerMesh.layer() == LayerKey.TRANSLUCENT) {
                    built.sortQuads(allocator, VertexSorter.byDistance(0.0F, 0.0F, 1000.0F));
                }

                VertexBuffer buffer = new VertexBuffer(GlUsage.STATIC_WRITE);
                buffer.bind();
                buffer.upload(built);
                VertexBuffer.unbind();
                return buffer;
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
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-aspectRatio, aspectRatio, -1.0F, 1.0F, -1000.0F, 3000.0F), ProjectionType.ORTHOGRAPHIC);
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
            this.applyLight(modelView);
            this.drawDynamic(data);
            this.drawBuffers(modelView);

            modelView.popMatrix();
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

        private void drawDynamic(MeshData data) {
            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            MatrixStack matrices = new MatrixStack();
            scene.blockEntities().forEach((pos, entity) -> {
                matrices.push();
                matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                // 1.21.3 的高层方块实体渲染会按真实相机做距离判断，预览里的离屏假世界不能走那条路径。
                client.getBlockEntityRenderDispatcher().renderEntity(
                        entity,
                        matrices,
                        client.getBufferBuilders().getEntityVertexConsumers(),
                        LightmapTextureManager.MAX_LIGHT_COORDINATE,
                        OverlayTexture.DEFAULT_UV
                );
                matrices.pop();
            });
            this.flushDynamic();

            scene.entities().forEach(entity -> {
                client.getEntityRenderDispatcher().render(
                        entity.entity(),
                        entity.x(),
                        entity.y(),
                        entity.z(),
                        entity.entity().getYaw(0.0F),
                        matrices,
                        client.getBufferBuilders().getEntityVertexConsumers(),
                        entity.light()
                );
                this.flushDynamic();
            });
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
            CompletableFuture<Void> task = this.future;
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
            if (this.cancelled.get()) {
                throw new CancellationException();
            }
        }
    }

    private static final class PreviewTooLargeException extends RuntimeException {
    }

    private static void translateToScreen(Matrix4fStack matrixStack, MinecraftClient client, float x, float y) {
        int screenWidth = client.currentScreen == null ? client.getWindow().getScaledWidth() : client.currentScreen.width;
        int screenHeight = client.currentScreen == null ? client.getWindow().getScaledHeight() : client.currentScreen.height;
        matrixStack.translate((2.0F * x - screenWidth) / screenHeight, -(2.0F * y - screenHeight) / screenHeight, 0.0F);
    }

    private static Path cachePath(Path sourcePath) {
        MinecraftClient client = MinecraftClient.getInstance();
        Path cacheDir = client.runDirectory.toPath().resolve(CACHE_DIR_NAME);
        return cacheDir.resolve(cacheKey(sourcePath) + ".qcp3d");
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
            if (schematic == null) {
                throw new IllegalStateException("Cannot read litematic file");
            }

            Bounds bounds = Bounds.from(schematic.getAreas().values());
            MeshCollector collector = new MeshCollector();
            Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
            List<BlockEntityData> blockEntities = new ArrayList<>();
            List<EntityData> entities = new ArrayList<>();
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
                recordEntities(entities, schematic, regionName, area, bounds);

                for (BlockPos pos : BlockPos.iterate(regionBounds.min(), regionBounds.max())) {
                    throwIfCancelled(cancelled);
                    BlockState state = view.getBlockState(pos);
                    if (!state.isAir()) {
                        BlockPos renderPos = pos.subtract(bounds.min());
                        recordBlockEntity(blockStates, blockEntities, view, state, schematicBlockEntities, pos, renderPos, bounds);
                        renderFluidIfPresent(collector, blockRenderManager, matrices, view, state, pos, renderPos);
                        renderBlockModel(collector, blockRenderManager, fabricContext, matrices, view, state, pos, renderPos, random);
                    }

                    visited++;
                    if ((visited & 0x3FF) == 0L) {
                        progressSink.set(Math.min(0.98F, visited / (float) total));
                    }
                }
            }

            progressSink.set(1.0F);
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
                count += layer.vertices().size();
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
            } catch (Throwable throwable) {
                LOGGER.warn("QuickCraft Litematica 3D preview will skip Fabric Renderer API models", throwable);
            }
            return null;
        }

        private static void recordBlockEntity(
                Map<BlockPos, BlockStateData> blockStates,
                List<BlockEntityData> blockEntities,
                RegionBlockView view,
                BlockState state,
                @Nullable Map<BlockPos, NbtCompound> schematicBlockEntities,
                BlockPos schematicPos,
                BlockPos renderPos,
                Bounds bounds
        ) {
            if (!(state.getBlock() instanceof BlockEntityProvider)) {
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
            NbtCompound entityNbt = nbt.copy();
            entityNbt.putInt("x", renderPos.getX());
            entityNbt.putInt("y", renderPos.getY());
            entityNbt.putInt("z", renderPos.getZ());
            blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state), entityNbt));
            if (blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES) {
                throw new PreviewTooLargeException();
            }
        }

        private static void recordDynamicBlockState(Map<BlockPos, BlockStateData> blockStates, BlockState state, BlockPos renderPos) {
            if (blockStates.size() >= MAX_DYNAMIC_BLOCK_STATES && !blockStates.containsKey(renderPos)) {
                throw new PreviewTooLargeException();
            }

            blockStates.put(renderPos.toImmutable(), new BlockStateData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state)));
        }

        private static void recordEntities(
                List<EntityData> entities,
                LitematicaSchematic schematic,
                String regionName,
                Box area,
                Bounds bounds
        ) {
            List<LitematicaSchematic.EntityInfo> regionEntities = schematic.getEntityListForRegion(regionName);
            if (regionEntities == null || regionEntities.isEmpty()) {
                return;
            }

            BlockPos regionOrigin = area.getPos1() == null ? BlockPos.ORIGIN : area.getPos1();
            for (LitematicaSchematic.EntityInfo info : regionEntities) {
                double x = info.posVec.x + regionOrigin.getX() - bounds.min().getX();
                double y = info.posVec.y + regionOrigin.getY() - bounds.min().getY();
                double z = info.posVec.z + regionOrigin.getZ() - bounds.min().getZ();
                entities.add(new EntityData(x, y, z, copyEntityNbtAt(info.nbt, x, y, z)));
                if (entities.size() > MAX_DYNAMIC_ENTITIES) {
                    throw new PreviewTooLargeException();
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
            if (cancelled.get()) {
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

        private VertexConsumer consumerFor(RenderLayer renderLayer) {
            LayerKey layer = LayerKey.from(renderLayer);
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer());
        }

        private List<LayerMesh> toMeshes() {
            List<LayerMesh> meshes = new ArrayList<>();
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                RecordingVertexConsumer consumer = this.consumers.get(layer);
                if (consumer != null && !consumer.vertices.isEmpty()) {
                    meshes.add(new LayerMesh(layer, List.copyOf(consumer.vertices)));
                }
            }
            return List.copyOf(meshes);
        }
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private final List<PreviewVertex> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private int argb = 0xFFFFFFFF;
        private float u;
        private float v;
        private int overlay = OverlayTexture.DEFAULT_UV;
        private int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

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
            this.vertices.add(new PreviewVertex(this.x, this.y, this.z, this.argb, this.u, this.v, this.overlay, this.light, x, y, z));
            this.overlay = OverlayTexture.DEFAULT_UV;
            this.light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            return this;
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.vertices.add(new PreviewVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ));
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

    private record PreviewVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
    }

    private record LayerMesh(LayerKey layer, List<PreviewVertex> vertices) {
    }

    private static RegistryEntryLookup<Block> blockLookup(DynamicRegistryManager registryManager) {
        return registryManager.getOrThrow(RegistryKeys.BLOCK);
    }

    private record BlockStateData(int x, int y, int z, NbtCompound stateNbt) {
        private BlockState state(DynamicRegistryManager registryManager) {
            return NbtHelper.toBlockState(blockLookup(registryManager), this.stateNbt);
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
                count += layer.vertices().size();
            }
            return count;
        }

        private boolean withinBudget() {
            long vertices = 0L;
            for (LayerMesh layer : this.layers) {
                vertices += layer.vertices().size();
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
            Entity entity = EntityUtils.createEntityAndPassengersFromNBT(this.entityNbt.copy(), world);
            if (entity == null) {
                return null;
            }

            entity.setPosition(this.x, this.y, this.z);
            int light = MinecraftClient.getInstance().getEntityRenderDispatcher().getLight(entity, 0.0F);
            return new RenderedEntity(entity, this.x, this.y, this.z, light);
        }
    }

    private record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    private record BlockEntityData(int x, int y, int z, NbtCompound stateNbt, NbtCompound entityNbt) {
        @Nullable
        private BlockEntity instantiate(DummyWorld world) {
            BlockState state = NbtHelper.toBlockState(blockLookup(world.getRegistryManager()), this.stateNbt);
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
                return null;
            }

            BlockPos pos = new BlockPos(this.x, this.y, this.z);
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
                blockStates.put(new BlockPos(data.x(), data.y(), data.z()), data.state(world.getRegistryManager()));
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

        private DummyWorld(MutableWorldProperties properties, DynamicRegistryManager registryManager, RegistryEntry<DimensionType> dimensionEntry, WorldRendererSchematic renderer) {
            super(properties, registryManager, dimensionEntry, renderer);
        }

        private static DummyWorld fromWorld(ClientWorld world) {
            return new DummyWorld(world.getLevelProperties(), world.getRegistryManager(), world.getDimensionEntry(), new WorldRendererSchematic(MinecraftClient.getInstance()));
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

            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
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
                long fileSize = Files.size(path);
                if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || layerCount < 0 || layerCount > LayerKey.values().length) {
                    deleteQuietly(path);
                    return null;
                }

                List<LayerMesh> layers = new ArrayList<>(layerCount);
                long totalVertices = 0L;
                for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
                    if (cancelled.get()) {
                        throw new CancellationException();
                    }

                    LayerKey layer = LayerKey.byId(input.readInt());
                    int vertexCount = input.readInt();
                    totalVertices += Math.max(vertexCount, 0);
                    if (layer == null
                            || vertexCount < 0
                            || totalVertices > MAX_UPLOAD_VERTICES
                            || totalVertices * VERTEX_BYTES > fileSize + 1024L) {
                        deleteQuietly(path);
                        return null;
                    }

                    List<PreviewVertex> vertices = new ArrayList<>(vertexCount);
                    for (int i = 0; i < vertexCount; i++) {
                        if ((i & 0x7FF) == 0 && cancelled.get()) {
                            throw new CancellationException();
                        }

                        vertices.add(new PreviewVertex(
                                input.readFloat(),
                                input.readFloat(),
                                input.readFloat(),
                                input.readInt(),
                                input.readFloat(),
                                input.readFloat(),
                                input.readInt(),
                                input.readInt(),
                                input.readFloat(),
                                input.readFloat(),
                                input.readFloat()
                        ));
                    }
                    layers.add(new LayerMesh(layer, List.copyOf(vertices)));
                }

                int blockStateCount = input.readInt();
                if (blockStateCount < 0 || blockStateCount > MAX_DYNAMIC_BLOCK_STATES) {
                    deleteQuietly(path);
                    return null;
                }

                List<BlockStateData> blockStates = new ArrayList<>(blockStateCount);
                for (int i = 0; i < blockStateCount; i++) {
                    if ((i & 0x7FF) == 0 && cancelled.get()) {
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
                    if ((i & 0xFF) == 0 && cancelled.get()) {
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
                    if ((i & 0xFF) == 0 && cancelled.get()) {
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

        private static void writeAtomically(Path tmpPath, Path finalPath, MeshData data, AtomicBoolean cancelled) throws IOException {
            deleteTmpQuietly(tmpPath);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath)))) {
                output.writeInt(CACHE_MAGIC);
                output.writeInt(CACHE_FORMAT_VERSION);
                output.writeUTF(CACHE_RENDER_MARKER);
                output.writeInt(data.sizeX());
                output.writeInt(data.sizeY());
                output.writeInt(data.sizeZ());
                output.writeInt(data.layers().size());

                int written = 0;
                for (LayerMesh layer : data.layers()) {
                    output.writeInt(layer.layer().id);
                    output.writeInt(layer.vertices().size());
                    for (PreviewVertex vertex : layer.vertices()) {
                        if ((written++ & 0x7FF) == 0 && cancelled.get()) {
                            throw new CancellationException();
                        }

                        output.writeFloat(vertex.x());
                        output.writeFloat(vertex.y());
                        output.writeFloat(vertex.z());
                        output.writeInt(vertex.argb());
                        output.writeFloat(vertex.u());
                        output.writeFloat(vertex.v());
                        output.writeInt(vertex.overlay());
                        output.writeInt(vertex.light());
                        output.writeFloat(vertex.nx());
                        output.writeFloat(vertex.ny());
                        output.writeFloat(vertex.nz());
                    }
                }

                output.writeInt(data.blockStates.size());
                for (BlockStateData blockState : data.blockStates) {
                    if (cancelled.get()) {
                        throw new CancellationException();
                    }

                    output.writeInt(blockState.x());
                    output.writeInt(blockState.y());
                    output.writeInt(blockState.z());
                    NbtIo.writeCompound(blockState.stateNbt(), output);
                }

                output.writeInt(data.blockEntities.size());
                for (BlockEntityData blockEntity : data.blockEntities) {
                    if (cancelled.get()) {
                        throw new CancellationException();
                    }

                    output.writeInt(blockEntity.x());
                    output.writeInt(blockEntity.y());
                    output.writeInt(blockEntity.z());
                    NbtIo.writeCompound(blockEntity.stateNbt(), output);
                    NbtIo.writeCompound(blockEntity.entityNbt(), output);
                }

                output.writeInt(data.entities.size());
                for (EntityData entity : data.entities) {
                    if (cancelled.get()) {
                        throw new CancellationException();
                    }

                    output.writeDouble(entity.x());
                    output.writeDouble(entity.y());
                    output.writeDouble(entity.z());
                    NbtIo.writeCompound(entity.entityNbt(), output);
                }
            }

            if (cancelled.get()) {
                throw new CancellationException();
            }

            try {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
