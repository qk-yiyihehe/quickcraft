package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.compat.iris.IrisCompat;
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
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
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
import net.minecraft.client.texture.NativeImage;
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
import net.minecraft.text.Text;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Litematica 文件和游戏内选区的真实方块模型 3D 预览。
 * 构建阶段调用 Minecraft 自带方块渲染器，把材质、异形模型、透明层和流体都录成可缓存的 CPU 顶点。
 */
public final class QuickLitematicaPreview3D {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreview3D.class);
    private static final AtomicBoolean SHADER_API_WARNING_LOGGED = new AtomicBoolean();
    // Minecraft 1.21.x 的预览方块实体没有非弃用的公开状态更新 API。
    @SuppressWarnings("deprecation")
    private static void setPreviewBlockEntityState(BlockEntity blockEntity, BlockState state) {
        blockEntity.setCachedState(state);
    }

    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderManager 多线程竞争。
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    // v15：缓存文件固定绑定投影路径，内容哈希和材质包签名只决定是否原地重建。
    // v14：UV 恢复 float32，并保留完整 light 坐标，避免斜视面跨出方块图集 sprite 边界。
    // v13：回退箱子静态化（entity atlas 纹理与方块 VBO 不兼容，紫色方块）；保留 GZIP+量化+视口剔除+邻居登记修复。
    // v12：箱子顶点静态化到独立 VBO，缓存追加 chestVertices 字段。
    // v11：保留 v10 的 GZIP + 顶点量化；箱子方块实体改回动态渲染，避免 chest atlas 被写进方块 VBO。
    // 升版本会让旧缓存一次性失效；之后 mod 版本号变化不再清缓存（token 已不含 mod 版本）。
    private static final int CACHE_FORMAT_VERSION = 15;
    private static final int CACHE_MAGIC = 0x51435033; // QCP3
    private static final String CACHE_DIR_NAME = "litematica-preview-cache";
    private static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    private static final String CACHE_INDEX_FILE_NAME = "cache-index.properties";
    private static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v15-stable-path-content-resource-signature-mc1.21";
    private static final int EXPAND_BUTTON_SIZE = 16;
    private static final int COMPAT_CLIPBOARD_MAX_DIMENSION = 4096;
    private static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    private static final int MAX_UPLOAD_VERTICES = 12_000_000;
    private static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    private static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    private static final int MAX_DYNAMIC_ENTITIES = 8_192;
    // 动态模型只驻留显存、不写入 qcp3d；限制一次录制的 CPU/GPU 顶点总量，失败时回退逐帧渲染。
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_DYNAMIC_RENDER_LAYERS = 1_024;
    private static final int DYNAMIC_LAYER_INITIAL_BYTES = 64 * 1024;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final float MAX_PITCH_RADIANS = (float) Math.toRadians(85.0);
    private static final float PREVIEW_FIT_PADDING = 0.95F;
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
    private static final Object CACHE_INDEX_LOCK = new Object();
    private static final Properties CACHE_INDEX = new Properties();
    // 当前页面只按此间隔检查文件大小/时间戳；完整 SHA-256 始终在后台且仅于重新核验时计算。
    private static final long SOURCE_CHECK_INTERVAL_MILLIS = 1_000L;
    @Nullable
    private static volatile Path currentCacheDirectory;

    private QuickLitematicaPreview3D() {
    }

    public static Manager init(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui, Runnable previewMetadataRefresh) {
        Manager old = MANAGERS.remove(gui);
        if (old != null) {
            old.close();
        }

        Manager manager = new Manager(gui, previewMetadataRefresh);
        MANAGERS.put(gui, manager);
        return manager;
    }

    public static void close(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui) {
        Manager manager = MANAGERS.remove(gui);
        if (manager != null) {
            manager.close();
        }
    }

    static void openGenerated(Screen parent, String displayName, Supplier<LitematicaSchematic> schematicSupplier) {
        Manager manager = new Manager(parent, () -> {});
        manager.current = Preview.createGenerated(displayName, schematicSupplier);
        MinecraftClient.getInstance().setScreen(new QuickLitematicaPreview3DScreen(
                parent,
                displayName,
                manager,
                true
        ));
    }

    public static void render(
            fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui,
            @Nullable DirectoryEntry entry,
            boolean hasEmbeddedPreview,
            DrawContext drawContext,
            int x,
            int y,
            int size
    ) {
        boolean previewEnabled = QuickCraftConfigs.isLitematica3DPreviewEnabled();
        boolean shaderPackActive = isShaderPackActive();
        if (!previewEnabled || shaderPackActive) {
            for (Manager manager : MANAGERS.values()) {
                manager.releasePreview();
            }
            if (previewEnabled
                    && shaderPackActive
                    && !hasEmbeddedPreview
                    && entry != null
                    && isSupportedLitematic(entry)) {
                renderShaderDisabled(drawContext, x, y, size);
            }
            return;
        }

        Manager manager = MANAGERS.get(gui);
        if (manager == null) {
            return;
        }

        if (QuickCraftConfigs.shouldReplaceLitematicaPreviewWith3D() || !hasEmbeddedPreview) {
            manager.render(entry, hasEmbeddedPreview, drawContext, x, y, size);
        } else {
            manager.renderLauncher(entry, hasEmbeddedPreview, drawContext, x, y, size);
        }
    }

    private static void renderShaderDisabled(DrawContext context, int x, int y, int size) {
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

    public static boolean is3DPreviewAvailable() {
        return QuickCraftConfigs.isLitematica3DPreviewEnabled() && !isShaderPackActive();
    }

    public static boolean isShaderPackActive() {
        try {
            return IrisCompat.isShaderActive();
        } catch (Throwable throwable) {
            if (SHADER_API_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Iris shader state could not be queried; disabling QuickCraft 3D previews for this session", throwable);
            }
            return IrisCompat.isIrisActive;
        }
    }

    public static final class Manager implements AutoCloseable {
        @Nullable
        private Preview current;
        @Nullable
        private Path currentPath;
        @Nullable
        private DirectoryEntry currentEntry;
        private final DragState drag = new DragState();
        private final Screen owner;
        private final Runnable previewMetadataRefresh;
        private final AtomicBoolean previewImageWriteInProgress = new AtomicBoolean();
        private boolean hasEmbeddedPreviewImage;
        private int viewX;
        private int viewY;
        private int viewSize;
        private boolean showExpandButton;
        private long nextSourceCheckMillis;

        private Manager(Screen owner, Runnable previewMetadataRefresh) {
            this.owner = owner;
            this.previewMetadataRefresh = previewMetadataRefresh;
        }

        private void render(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toPath().toAbsolutePath().normalize();
            if (!path.equals(this.currentPath)) {
                this.switchTo(path, entry);
            } else if (this.current != null
                    && System.currentTimeMillis() >= this.nextSourceCheckMillis
                    && this.current.sourceStampChanged()) {
                this.switchTo(path, entry);
            }
            this.nextSourceCheckMillis = System.currentTimeMillis() + SOURCE_CHECK_INTERVAL_MILLIS;
            this.currentEntry = entry;
            this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
            this.renderCurrent(drawContext, x, y, size, true);
        }

        private void renderLauncher(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toPath().toAbsolutePath().normalize();
            if (!path.equals(this.currentPath)) {
                this.clearCurrent();
            }
            this.currentPath = path;
            this.currentEntry = entry;
            this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = true;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);
            this.drawExpandButton(drawContext);
        }

        void renderFullscreen(DrawContext drawContext, int x, int y, int size) {
            this.renderCurrent(drawContext, x, y, size, false);
        }

        private void renderCurrent(DrawContext drawContext, int x, int y, int size, boolean showExpandButton) {
            if (!is3DPreviewAvailable()) {
                return;
            }

            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = showExpandButton;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            RenderUtils.drawOutlinedBox(this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
            if (this.current != null) {
                this.current.render(drawContext, this.viewX, this.viewY, this.viewSize, this.drag);
            }
            if (showExpandButton) {
                this.drawExpandButton(drawContext);
            }
        }

        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (this.current == null || !this.canHandleMouse(mouseX, mouseY)) {
                return false;
            }

            this.drag.scaleBy(verticalAmount);
            return true;
        }

        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (this.current == null || !is3DPreviewAvailable()) {
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

            if (mouseButton == 0 && this.showExpandButton && this.isExpandButtonHovered(mouseX, mouseY) && this.currentEntry != null) {
                DirectoryEntry entry = this.currentEntry;
                if (this.current == null && this.currentPath != null) {
                    boolean hasEmbeddedPreview = this.hasEmbeddedPreviewImage;
                    this.switchTo(this.currentPath, entry);
                    this.currentEntry = entry;
                    this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
                }
                MinecraftClient.getInstance().setScreen(new QuickLitematicaPreview3DScreen(this.owner, entry.getName(), this));
                return true;
            }

            if (this.current == null) {
                return false;
            }

            this.drag.click(mouseButton);
            return true;
        }

        void setPreset(double yawDegrees, double pitchDegrees) {
            this.drag.setPreset(yawDegrees, pitchDegrees);
        }

        Path outputDirectory() {
            return MinecraftClient.getInstance().runDirectory.toPath().resolve("渲染图");
        }

        int recommendedExportResolution() {
            Preview preview = this.current;
            return preview == null ? 0 : preview.recommendedExportResolution();
        }

        void exportPng(int resolution, int backgroundColor, Consumer<Text> callback) {
            Preview preview = this.current;
            if (preview == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_failed"));
                return;
            }
            preview.exportPng(resolution, backgroundColor, this.drag, this.outputDirectory(), callback);
        }

        void copyImage(int resolution, int backgroundColor, Consumer<Text> callback) {
            Preview preview = this.current;
            if (preview == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                return;
            }
            preview.copyImage(resolution, backgroundColor, this.drag, callback);
        }

        boolean canEditPreviewImage() {
            return QuickCraftConfigs.canAddLitematicaPreviewImages()
                    && this.current != null
                    && this.currentPath != null
                    && this.currentEntry != null
                    && !this.previewImageWriteInProgress.get();
        }

        boolean hasFilePreviewTarget() {
            return this.current != null && this.currentPath != null && this.currentEntry != null;
        }

        boolean canRemovePreviewImage() {
            return this.canEditPreviewImage() && this.hasEmbeddedPreviewImage;
        }

        void saveCurrentViewAsPreview(int backgroundColor, Consumer<Text> callback) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canEditPreviewImage() || preview == null || target == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            NativeImage image = preview.captureSnapshot(
                    EMBEDDED_PREVIEW_DIMENSION,
                    backgroundColor,
                    this.drag,
                    "quickcraft.litematica.preview_3d.preview_write_failed",
                    callback
            );
            if (image == null) {
                this.previewImageWriteInProgress.set(false);
                return;
            }

            int[] pixels;
            try {
                pixels = makeArgbPixels(image);
            } catch (Throwable throwable) {
                LOGGER.error("Failed to convert the 3D view into Litematica preview pixels", throwable);
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            } finally {
                image.close();
                preview.snapshotInProgress.set(false);
            }
            this.writePreviewAsync(preview, target, pixels, callback);
        }

        void selectPreviewImage(Consumer<Text> callback) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canEditPreviewImage() || preview == null || target == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            Path selected;
            try {
                selected = QuickLitematicaPreviewImageWriter.chooseImage(target);
            } catch (Throwable throwable) {
                LOGGER.error("Failed to open the preview image file picker", throwable);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            }
            if (selected == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.image_selection_cancelled"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            try {
                Util.getIoWorkerExecutor().execute(() -> {
                    try {
                        int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(selected);
                        QuickLitematicaPreviewImageWriter.writePreview(target, pixels);
                        preview.refreshCacheSourceHash();
                        this.finishPreviewWrite(preview, true, callback, Text.translatable(
                                "quickcraft.litematica.preview_3d.preview_write_success",
                                target.getFileName().toString()
                        ));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to set the Litematica preview image from {}", selected, throwable);
                        this.failPreviewWrite(callback, throwable);
                    }
                });
            } catch (Throwable throwable) {
                LOGGER.error("Failed to schedule the Litematica preview image write", throwable);
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
            }
        }

        void removePreviewImage(Consumer<Text> callback) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canRemovePreviewImage() || preview == null || target == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_remove_unavailable"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            try {
                Util.getIoWorkerExecutor().execute(() -> {
                    try {
                        QuickLitematicaPreviewImageWriter.removePreview(target);
                        preview.refreshCacheSourceHash();
                        this.finishPreviewWrite(preview, false, callback, Text.translatable(
                                "quickcraft.litematica.preview_3d.preview_remove_success",
                                target.getFileName().toString()
                        ));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to remove the Litematica preview image from {}", target, throwable);
                        this.failPreviewWrite(callback, throwable);
                    }
                });
            } catch (Throwable throwable) {
                LOGGER.error("Failed to schedule the Litematica preview image removal", throwable);
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
            }
        }

        @SuppressWarnings("deprecation")
        private static int[] makeArgbPixels(NativeImage image) {
            return image.makePixelArray();
        }

        private void writePreviewAsync(Preview preview, Path target, int[] pixels, Consumer<Text> callback) {
            try {
                Util.getIoWorkerExecutor().execute(() -> {
                    try {
                        QuickLitematicaPreviewImageWriter.writePreview(target, pixels);
                        preview.refreshCacheSourceHash();
                        this.finishPreviewWrite(preview, true, callback, Text.translatable(
                                "quickcraft.litematica.preview_3d.preview_write_success",
                                target.getFileName().toString()
                        ));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to set the Litematica preview image for {}", target, throwable);
                        this.failPreviewWrite(callback, throwable);
                    }
                });
            } catch (Throwable throwable) {
                LOGGER.error("Failed to schedule the Litematica preview image write", throwable);
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
            }
        }

        private void finishPreviewWrite(Preview preview, boolean hasEmbeddedPreview, Consumer<Text> callback, Text message) {
            MinecraftClient.getInstance().execute(() -> {
                this.previewImageWriteInProgress.set(false);
                if (this.current == preview) {
                    this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
                }
                try {
                    this.previewMetadataRefresh.run();
                } catch (Throwable throwable) {
                    LOGGER.warn("Failed to refresh the Litematica preview image cache", throwable);
                }
                callback.accept(message);
            });
        }

        private void failPreviewWrite(Consumer<Text> callback, Throwable throwable) {
            MinecraftClient.getInstance().execute(() -> {
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable(
                        QuickLitematicaPreviewImageWriter.failureTranslationKey(throwable)
                ));
            });
        }

        @Override
        public void close() {
            this.clearCurrent();
        }

        private boolean canHandleMouse(double mouseX, double mouseY) {
            return (this.current != null || this.currentEntry != null)
                    && is3DPreviewAvailable()
                    && this.drag.inViewport(mouseX, mouseY);
        }

        private void switchTo(Path path, DirectoryEntry entry) {
            this.clearCurrent();
            this.currentPath = path;
            this.current = Preview.create(entry);
            this.nextSourceCheckMillis = System.currentTimeMillis() + SOURCE_CHECK_INTERVAL_MILLIS;
        }

        private void clearCurrent() {
            this.currentPath = null;
            this.currentEntry = null;
            this.hasEmbeddedPreviewImage = false;
            if (this.current != null) {
                this.current.close();
                this.current = null;
            }
            this.drag.stop();
        }

        private void drawExpandButton(DrawContext context) {
            int x = this.viewX + this.viewSize - EXPAND_BUTTON_SIZE - 3;
            int y = this.viewY + 3;
            MinecraftClient client = MinecraftClient.getInstance();
            double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
            boolean hovered = this.isExpandButtonHovered(mouseX, mouseY);
            int backgroundColor = hovered ? 0xA0505050 : 0x40101010;
            context.fill(x + 2, y + 2, x + EXPAND_BUTTON_SIZE - 2, y + EXPAND_BUTTON_SIZE - 2, backgroundColor);
            this.drawExpandIcon(context, x, y, hovered ? 0xFFFFFFFF : 0xFFE0E0E0);
            if (hovered) {
                context.drawTooltip(
                        client.textRenderer,
                        Text.translatable("quickcraft.litematica.preview_3d.expand"),
                        (int) mouseX,
                        (int) mouseY
                );
            }
        }

        private void drawExpandIcon(DrawContext context, int x, int y, int color) {
            context.fill(x + 3, y + 3, x + 7, y + 4, color);
            context.fill(x + 3, y + 3, x + 4, y + 7, color);
            context.fill(x + 5, y + 5, x + 6, y + 6, color);
            context.fill(x + 6, y + 6, x + 7, y + 7, color);

            context.fill(x + 9, y + 3, x + 13, y + 4, color);
            context.fill(x + 12, y + 3, x + 13, y + 7, color);
            context.fill(x + 10, y + 5, x + 11, y + 6, color);
            context.fill(x + 9, y + 6, x + 10, y + 7, color);

            context.fill(x + 3, y + 12, x + 7, y + 13, color);
            context.fill(x + 3, y + 9, x + 4, y + 13, color);
            context.fill(x + 5, y + 10, x + 6, y + 11, color);
            context.fill(x + 6, y + 9, x + 7, y + 10, color);

            context.fill(x + 9, y + 12, x + 13, y + 13, color);
            context.fill(x + 12, y + 9, x + 13, y + 13, color);
            context.fill(x + 10, y + 10, x + 11, y + 11, color);
            context.fill(x + 9, y + 9, x + 10, y + 10, color);
        }

        private boolean isExpandButtonHovered(double mouseX, double mouseY) {
            int x = this.viewX + this.viewSize - EXPAND_BUTTON_SIZE - 3;
            int y = this.viewY + 3;
            return mouseX >= x && mouseX < x + EXPAND_BUTTON_SIZE && mouseY >= y && mouseY < y + EXPAND_BUTTON_SIZE;
        }

        private void releasePreview() {
            this.clearCurrent();
        }
    }

    private static boolean isSupportedLitematic(DirectoryEntry entry) {
        return entry.getFullPath().isFile() && FileType.fromFile(entry.getFullPath().toPath()) == FileType.LITEMATICA_SCHEMATIC;
    }

    private static final class Preview implements AutoCloseable {
        private final Path sourcePath;
        private final Path cachePath;
        private final Path tmpPath;
        private final String cacheSlot;
        private final String resourcePackSignature;
        private volatile long sourceSize;
        private volatile long sourceModifiedMillis;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile MeshData meshData;
        private volatile float progress;
        private volatile State state = State.LOADING;
        @Nullable
        private volatile Future<?> future;
        private final Map<LayerKey, VertexBuffer> vertexBuffers = new EnumMap<>(LayerKey.class);
        private List<DynamicLayerBuffer> dynamicBuffers = List.of();
        private boolean dynamicBuffersReady;
        private boolean dynamicBufferFallback;
        private boolean uploadScheduled;
        private final AtomicBoolean snapshotInProgress = new AtomicBoolean();

        private Preview(
                Path sourcePath,
                Path cachePath,
                Path tmpPath,
                String cacheSlot,
                String resourcePackSignature
        ) {
            this.sourcePath = sourcePath;
            this.cachePath = cachePath;
            this.tmpPath = tmpPath;
            this.cacheSlot = cacheSlot;
            this.resourcePackSignature = resourcePackSignature;
            this.captureSourceStamp();
        }

        private static Preview create(DirectoryEntry entry) {
            Path sourcePath = entry.getFullPath().toPath().toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new Preview(
                    sourcePath,
                    cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot,
                    currentResourcePackSignature()
            );
            preview.progress = PROGRESS_START;
            preview.future = PREVIEW_EXECUTOR.submit(() -> preview.loadOrBuild(entry));
            return preview;
        }

        private static Preview createGenerated(String displayName, Supplier<LitematicaSchematic> schematicSupplier) {
            String safeName = displayName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (safeName.isBlank()) {
                safeName = "selection";
            }
            Path transientPath = cacheDirectory().resolve("selection-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
            Preview preview = new Preview(
                    Path.of(safeName + ".litematic"),
                    transientPath,
                    transientPath,
                    "",
                    ""
            );
            preview.progress = PROGRESS_START;
            preview.future = PREVIEW_EXECUTOR.submit(() -> preview.loadGenerated(schematicSupplier));
            return preview;
        }

        private void loadGenerated(Supplier<LitematicaSchematic> schematicSupplier) {
            try {
                this.state = State.BUILDING;
                LitematicaSchematic schematic = schematicSupplier.get();
                this.throwIfCancelled();
                if (schematic == null) {
                    throw new IllegalStateException("Cannot capture Litematica selection");
                }

                MeshData built = MeshBuilder.build(schematic, this.cancelled, value -> this.progress = value);
                this.throwIfCancelled();
                if (!built.withinBudget()) {
                    built.closeDynamic();
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    return;
                }

                this.meshData = built;
                this.progress = 1.0F;
                this.state = State.READY;
            } catch (CancellationException ignored) {
                this.state = State.CANCELLED;
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.progress = 1.0F;
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                } else if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                } else {
                    LOGGER.error("Failed to build a 3D preview from the Litematica selection", e);
                    this.state = State.FAILED;
                }
            }
        }

        private void loadOrBuild(DirectoryEntry entry) {
            try {
                this.progress = PROGRESS_START;
                String sourceHash = hashFileCancellable(this.sourcePath, this.cancelled);
                Path cacheDirectory = this.cachePath.getParent();
                if (cacheDirectory == null) {
                    throw new IOException("3D preview cache path has no parent directory");
                }
                Files.createDirectories(cacheDirectory);
                Path readCachePath = this.cachePath;
                CacheIndexEntry indexEntry = readCacheIndexEntry(this.cacheSlot);
                MeshData cached = indexEntry != null
                        && sourceHash.equals(indexEntry.sourceHash())
                        && this.resourcePackSignature.equals(indexEntry.resourcePackSignature())
                        ? CacheFile.read(readCachePath, this.cancelled)
                        : null;
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

                Path writeCachePath = this.cachePath;
                Path writeTmpPath = this.tmpPath;
                CacheFile.writeAtomically(writeTmpPath, writeCachePath, built, this.cancelled, value -> this.progress = value);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
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

        private void captureSourceStamp() {
            try {
                this.sourceSize = Files.size(this.sourcePath);
                this.sourceModifiedMillis = Files.getLastModifiedTime(this.sourcePath).toMillis();
            } catch (IOException e) {
                this.sourceSize = -1L;
                this.sourceModifiedMillis = -1L;
            }
        }

        private boolean sourceStampChanged() {
            try {
                return Files.size(this.sourcePath) != this.sourceSize
                        || Files.getLastModifiedTime(this.sourcePath).toMillis() != this.sourceModifiedMillis
                        || !currentResourcePackSignature().equals(this.resourcePackSignature);
            } catch (IOException e) {
                return true;
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

        private void refreshCacheSourceHash() {
            try {
                String sourceHash = hashFile(this.sourcePath);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                this.captureSourceStamp();
            } catch (IOException e) {
                LOGGER.warn("Failed to refresh the 3D preview cache signature for {}", this.sourcePath, e);
            }
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
            modelView.rotate(RotationAxis.POSITIVE_X.rotation(drag.pitch));
            modelView.rotate(RotationAxis.POSITIVE_Y.rotation((float) drag.angle));
            float scale = data.scaleFactor(size, client.currentScreen.height) * drag.scale;
            modelView.scale(scale, scale, scale);
            modelView.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);
            RenderSystem.applyModelViewMatrix();

            this.applyLight(modelView);
            this.prepareDynamicBuffers(data);
            if (this.dynamicBuffersReady) {
                this.drawDynamicBuffers(modelView, null, false);
            } else {
                this.drawDynamic(data, modelView, x, y, size);
            }
            this.drawBuffers(modelView, null, false);

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.restoreProjectionMatrix();
            context.disableScissor();
        }

        private void drawBuffers(Matrix4f modelView, @Nullable Framebuffer target, boolean keepTargetOpaque) {
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                VertexBuffer buffer = this.vertexBuffers.get(layer);
                if (buffer == null || buffer.isClosed()) {
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
                buffer.bind();
                buffer.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                renderLayer.endDrawing();
            }
            VertexBuffer.unbind();
        }

        private void prepareDynamicBuffers(MeshData data) {
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
                                entity.entity().getYaw(0.0F),
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

        private void drawDynamicBuffers(Matrix4f modelView, @Nullable Framebuffer target, boolean keepTargetOpaque) {
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
                buffer.bind();
                buffer.draw(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                renderLayer.endDrawing();
            }
            VertexBuffer.unbind();
        }

        private int recommendedExportResolution() {
            MeshData data = this.meshData;
            if (data == null) {
                return 0;
            }

            long target = 4L * Math.max(data.sizeX(), Math.max(data.sizeY(), data.sizeZ()));
            if (target <= 512) {
                return 512;
            }
            if (target <= 1024) {
                return 1024;
            }
            if (target <= 2048) {
                return 2048;
            }
            if (target <= 4096) {
                return 4096;
            }
            return 8192;
        }

        private void exportPng(int resolution, int backgroundColor, DragState drag, Path outputDirectory, Consumer<Text> callback) {
            NativeImage image = this.captureSnapshot(
                    resolution,
                    backgroundColor,
                    drag,
                    "quickcraft.litematica.preview_3d.export_failed",
                    callback
            );
            if (image == null) {
                return;
            }

            Path outputPath;
            try {
                Files.createDirectories(outputDirectory);
                outputPath = this.nextOutputPath(outputDirectory, resolution);
            } catch (Throwable ignored) {
                image.close();
                this.snapshotInProgress.set(false);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_failed"));
                return;
            }

            Util.getIoWorkerExecutor().execute(() -> {
                try {
                    image.writeTo(outputPath);
                    MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                            "quickcraft.litematica.preview_3d.export_success",
                            outputPath.getFileName().toString()
                    )));
                } catch (Exception ignored) {
                    MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                            "quickcraft.litematica.preview_3d.export_failed"
                    )));
                } finally {
                    image.close();
                    this.snapshotInProgress.set(false);
                }
            });
        }

        private void copyImage(int resolution, int backgroundColor, DragState drag, Consumer<Text> callback) {
            if (!Platform.isWindows()) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                return;
            }

            NativeImage image = this.captureSnapshot(
                    resolution,
                    backgroundColor,
                    drag,
                    "quickcraft.litematica.preview_3d.copy_failed",
                    callback
            );
            if (image == null) {
                return;
            }

            Util.getIoWorkerExecutor().execute(() -> {
                try {
                    copyToWindowsClipboard(image);
                    MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                            "quickcraft.litematica.preview_3d.copy_success"
                    )));
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to copy the 3D preview image to the Windows clipboard", throwable);
                    MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                            "quickcraft.litematica.preview_3d.copy_failed"
                    )));
                } finally {
                    image.close();
                    this.snapshotInProgress.set(false);
                }
            });
        }

        @Nullable
        private NativeImage captureSnapshot(
                int resolution,
                int backgroundColor,
                DragState drag,
                String failureTranslationKey,
                Consumer<Text> callback
        ) {
            if (isShaderPackActive()) {
                callback.accept(Text.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                return null;
            }

            MeshData data = this.meshData;
            if (this.state != State.READY || data == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                return null;
            }

            this.uploadIfNeeded();
            this.prepareDynamicBuffers(data);
            if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_dynamic_failed"));
                return null;
            }
            if (data.vertexCount() > 0 && this.vertexBuffers.isEmpty()) {
                callback.accept(Text.translatable(failureTranslationKey));
                return null;
            }
            if (!this.snapshotInProgress.compareAndSet(false, true)) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.exporting"));
                return null;
            }

            Framebuffer framebuffer = null;
            try {
                framebuffer = new SimpleFramebuffer(resolution, resolution, true, MinecraftClient.IS_SYSTEM_MAC);
                RenderSystem.colorMask(true, true, true, true);
                framebuffer.setClearColor(
                        ((backgroundColor >> 16) & 0xFF) / 255.0F,
                        ((backgroundColor >> 8) & 0xFF) / 255.0F,
                        (backgroundColor & 0xFF) / 255.0F,
                        ((backgroundColor >>> 24) & 0xFF) / 255.0F
                );
                framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
                this.renderSnapshot(framebuffer, data, drag, ((backgroundColor >>> 24) & 0xFF) == 0xFF);
                return takeSnapshot(framebuffer);
            } catch (Throwable ignored) {
                this.snapshotInProgress.set(false);
                callback.accept(Text.translatable(failureTranslationKey));
                return null;
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

        // Minecraft 客户端会启用 java.awt.headless，图片剪贴板必须绕过 AWT 直接写 Win32。
        private static void copyToWindowsClipboard(NativeImage image) throws InterruptedException {
            int width = image.getWidth();
            int height = image.getHeight();
            double compatScale = Math.min(1.0, COMPAT_CLIPBOARD_MAX_DIMENSION / (double) Math.max(width, height));
            int compatWidth = Math.max(1, (int) Math.round(width * compatScale));
            int compatHeight = Math.max(1, (int) Math.round(height * compatScale));
            long pixelBytes = (long) width * height * 4L;
            long compatPixelBytes = (long) compatWidth * compatHeight * 4L;
            Pointer dibV5Handle = WindowsMemory.INSTANCE.GlobalAlloc(
                    WindowsMemory.GHND,
                    new BaseTSD.SIZE_T(WindowsMemory.BITMAP_V5_HEADER_SIZE + pixelBytes)
            );
            Pointer dibHandle = WindowsMemory.INSTANCE.GlobalAlloc(
                    WindowsMemory.GHND,
                    new BaseTSD.SIZE_T(WindowsMemory.BITMAP_INFO_HEADER_SIZE + compatPixelBytes)
            );
            if (dibV5Handle == null || dibHandle == null) {
                if (dibV5Handle != null) {
                    WindowsMemory.INSTANCE.GlobalFree(dibV5Handle);
                }
                if (dibHandle != null) {
                    WindowsMemory.INSTANCE.GlobalFree(dibHandle);
                }
                throw new IllegalStateException("GlobalAlloc failed");
            }

            boolean clipboardOwnsDibV5 = false;
            boolean clipboardOwnsDib = false;
            boolean clipboardOpen = false;
            try {
                Pointer dibV5Memory = WindowsMemory.INSTANCE.GlobalLock(dibV5Handle);
                Pointer dibMemory = WindowsMemory.INSTANCE.GlobalLock(dibHandle);
                if (dibV5Memory == null || dibMemory == null) {
                    if (dibV5Memory != null) {
                        WindowsMemory.INSTANCE.GlobalUnlock(dibV5Handle);
                    }
                    if (dibMemory != null) {
                        WindowsMemory.INSTANCE.GlobalUnlock(dibHandle);
                    }
                    throw new IllegalStateException("GlobalLock failed");
                }
                try {
                    dibV5Memory.write(0, createBitmapV5Header(width, height), 0, WindowsMemory.BITMAP_V5_HEADER_SIZE);
                    dibMemory.write(
                            0,
                            createBitmapInfoHeader(compatWidth, compatHeight),
                            0,
                            WindowsMemory.BITMAP_INFO_HEADER_SIZE
                    );
                    int[] row = new int[width];
                    boolean sameDimensions = width == compatWidth && height == compatHeight;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int abgr = image.getColor(x, y);
                            row[x] = (abgr & 0xFF00FF00)
                                    | ((abgr & 0x00FF0000) >>> 16)
                                    | ((abgr & 0x000000FF) << 16);
                        }
                        dibV5Memory.write(WindowsMemory.BITMAP_V5_HEADER_SIZE + (long) y * width * 4L, row, 0, width);
                        if (sameDimensions) {
                            dibMemory.write(
                                    WindowsMemory.BITMAP_INFO_HEADER_SIZE + (long) (height - 1 - y) * width * 4L,
                                    row,
                                    0,
                                    width
                            );
                        }
                    }
                    if (!sameDimensions) {
                        int[] compatRow = new int[compatWidth];
                        for (int y = 0; y < compatHeight; y++) {
                            int sourceY = Math.min(height - 1, (int) ((y + 0.5) * height / compatHeight));
                            for (int x = 0; x < compatWidth; x++) {
                                int sourceX = Math.min(width - 1, (int) ((x + 0.5) * width / compatWidth));
                                int abgr = image.getColor(sourceX, sourceY);
                                compatRow[x] = (abgr & 0xFF00FF00)
                                        | ((abgr & 0x00FF0000) >>> 16)
                                        | ((abgr & 0x000000FF) << 16);
                            }
                            dibMemory.write(
                                    WindowsMemory.BITMAP_INFO_HEADER_SIZE
                                            + (long) (compatHeight - 1 - y) * compatWidth * 4L,
                                    compatRow,
                                    0,
                                    compatWidth
                            );
                        }
                    }
                } finally {
                    WindowsMemory.INSTANCE.GlobalUnlock(dibV5Handle);
                    WindowsMemory.INSTANCE.GlobalUnlock(dibHandle);
                }

                clipboardOpen = openWindowsClipboard();
                if (!clipboardOpen || !WindowsClipboard.INSTANCE.EmptyClipboard()) {
                    throw new IllegalStateException("Windows clipboard is unavailable");
                }
                // QQ 等旧客户端按枚举顺序取第一个可识别格式，兼容 DIB 必须放在完整 DIBV5 前面。
                if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIB, dibHandle) == null) {
                    throw new IllegalStateException("SetClipboardData(CF_DIB) failed");
                }
                clipboardOwnsDib = true;
                if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIBV5, dibV5Handle) != null) {
                    clipboardOwnsDibV5 = true;
                } else {
                    LOGGER.warn("Could not add CF_DIBV5 to the clipboard; CF_DIB remains available");
                }
            } finally {
                if (clipboardOpen) {
                    WindowsClipboard.INSTANCE.CloseClipboard();
                }
                if (!clipboardOwnsDibV5) {
                    WindowsMemory.INSTANCE.GlobalFree(dibV5Handle);
                }
                if (!clipboardOwnsDib) {
                    WindowsMemory.INSTANCE.GlobalFree(dibHandle);
                }
            }
        }

        private static boolean openWindowsClipboard() throws InterruptedException {
            for (int attempt = 0; attempt < 5; attempt++) {
                if (WindowsClipboard.INSTANCE.OpenClipboard(null)) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return false;
        }

        private static byte[] createBitmapV5Header(int width, int height) {
            ByteBuffer header = ByteBuffer.allocate(WindowsMemory.BITMAP_V5_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(WindowsMemory.BITMAP_V5_HEADER_SIZE);
            header.putInt(width);
            header.putInt(-height);
            header.putShort((short) 1);
            header.putShort((short) 32);
            header.putInt(WindowsMemory.BI_BITFIELDS);
            header.putInt(width * height * 4);
            header.position(40);
            header.putInt(0x00FF0000);
            header.putInt(0x0000FF00);
            header.putInt(0x000000FF);
            header.putInt(0xFF000000);
            header.putInt(WindowsMemory.LCS_SRGB);
            header.position(108);
            header.putInt(WindowsMemory.LCS_GM_IMAGES);
            return header.array();
        }

        private static byte[] createBitmapInfoHeader(int width, int height) {
            ByteBuffer header = ByteBuffer.allocate(WindowsMemory.BITMAP_INFO_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(WindowsMemory.BITMAP_INFO_HEADER_SIZE);
            header.putInt(width);
            header.putInt(height);
            header.putShort((short) 1);
            header.putShort((short) 32);
            header.putInt(WindowsMemory.BI_RGB);
            header.putInt(width * height * 4);
            return header.array();
        }

        private void renderSnapshot(Framebuffer framebuffer, MeshData data, DragState drag, boolean keepBackgroundOpaque) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(-1.0F, 1.0F, -1.0F, 1.0F, -1000.0F, 3000.0F),
                    VertexSorter.BY_Z
            );
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

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
                RenderSystem.applyModelViewMatrix();

                this.applyLight(modelView);
                framebuffer.beginWrite(true);
                if (this.dynamicBuffersReady) {
                    this.drawDynamicBuffers(modelView, framebuffer, keepBackgroundOpaque);
                }
                this.drawBuffers(modelView, framebuffer, keepBackgroundOpaque);
            } finally {
                RenderSystem.colorMask(true, true, true, true);
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.disableDepthTest();
                RenderSystem.disableBlend();
                RenderSystem.restoreProjectionMatrix();
            }
        }

        private static NativeImage takeSnapshot(Framebuffer framebuffer) {
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

        private Path nextOutputPath(Path outputDirectory, int resolution) {
            String fileName = this.sourcePath.getFileName().toString();
            int extension = fileName.lastIndexOf('.');
            String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
            baseName = baseName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (baseName.isBlank()) {
                baseName = "render";
            }

            String stem = baseName + "_" + Util.getFormattedCurrentTime() + "_" + resolution + "x" + resolution;
            Path outputPath = outputDirectory.resolve(stem + ".png");
            int suffix = 2;
            while (Files.exists(outputPath)) {
                outputPath = outputDirectory.resolve(stem + "_" + suffix++ + ".png");
            }
            return outputPath;
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
            this.closeDynamicBuffers();
        }

        private void closeDynamicBuffers() {
            this.dynamicBuffers.forEach(layerBuffer -> {
                if (!layerBuffer.buffer().isClosed()) {
                    layerBuffer.buffer().close();
                }
            });
            this.dynamicBuffers = List.of();
            this.dynamicBuffersReady = false;
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

    private record DynamicLayerBuffer(RenderLayer layer, VertexBuffer buffer) {
    }

    private static final class DynamicMeshCollector implements VertexConsumerProvider, AutoCloseable {
        private final Map<RenderLayer, DynamicMeshBuilder> sharedBuilders = new LinkedHashMap<>();
        private final List<DynamicMeshBuilder> builders = new ArrayList<>();
        private long allocatedBytes;

        @Override
        public VertexConsumer getBuffer(RenderLayer layer) {
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

        private DynamicMeshBuilder createBuilder(RenderLayer layer) {
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

        private void reserve(int bytes) {
            this.allocatedBytes += bytes;
            if (this.allocatedBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                throw new DynamicBufferTooLargeException("动态顶点超过 128 MiB 上限");
            }
        }

        private List<DynamicLayerBuffer> upload() {
            List<DynamicLayerBuffer> uploaded = new ArrayList<>();
            try {
                for (DynamicMeshBuilder meshBuilder : this.builders) {
                    try {
                        try (BuiltBuffer built = meshBuilder.builder().endNullable()) {
                            if (built == null) {
                                continue;
                            }
                            if (meshBuilder.layer().isTranslucent()) {
                                built.sortQuads(meshBuilder.allocator(), RenderSystem.getVertexSorting());
                            }

                            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
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

    private record DynamicMeshBuilder(RenderLayer layer, BufferAllocator allocator, BufferBuilder builder) {
        private DynamicMeshBuilder(RenderLayer layer, BufferAllocator allocator) {
            this(layer, allocator, new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat()));
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

    private static final class DynamicBufferTooLargeException extends RuntimeException {
        private DynamicBufferTooLargeException(String message) {
            super(message);
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

    static String cacheKey(Path sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, sourcePath.toAbsolutePath().normalize().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String hashFile(Path path) throws IOException {
        return hashFileCancellable(path, new AtomicBoolean());
    }

    private static String hashFileCancellable(Path path, AtomicBoolean cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        byte[] buffer = new byte[CACHE_IO_CHUNK_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException();
                }
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String currentResourcePackSignature() {
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
            loadAndCleanCacheIndex(cacheDir);
        } catch (IOException ignored) {
        }
    }

    private static void loadAndCleanCacheIndex(Path cacheDir) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.clear();
            Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
            if (Files.isRegularFile(indexPath)) {
                try (InputStream input = new BufferedInputStream(Files.newInputStream(indexPath))) {
                    CACHE_INDEX.load(input);
                }
            }

            Set<String> retainedCacheFiles = new java.util.HashSet<>();
            List<String> staleSlots = new ArrayList<>();
            for (String key : CACHE_INDEX.stringPropertyNames()) {
                if (!key.endsWith(".path")) {
                    continue;
                }
                String slot = key.substring(0, key.length() - ".path".length());
                String source = CACHE_INDEX.getProperty(key, "");
                Path cachePath = cacheDir.resolve(slot + ".qcp3d");
                boolean sourceExists;
                try {
                    sourceExists = !source.isBlank() && Files.isRegularFile(Path.of(source));
                } catch (RuntimeException e) {
                    sourceExists = false;
                }
                if (!sourceExists || !Files.isRegularFile(cachePath)) {
                    staleSlots.add(slot);
                } else {
                    retainedCacheFiles.add(cachePath.getFileName().toString());
                }
            }

            staleSlots.forEach(slot -> removeCacheIndexEntry(cacheDir, slot));
            try (var files = Files.list(cacheDir)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".tmp")
                                    || name.endsWith(".qcp3d") && !retainedCacheFiles.contains(name);
                        })
                        .forEach(QuickLitematicaPreview3D::deleteQuietly);
            }
            writeCacheIndex(cacheDir);
        }
    }

    @Nullable
    private static CacheIndexEntry readCacheIndexEntry(String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            String sourceHash = CACHE_INDEX.getProperty(slot + ".sourceHash");
            String resourceSignature = CACHE_INDEX.getProperty(slot + ".resourceSignature");
            return sourceHash == null || resourceSignature == null
                    ? null
                    : new CacheIndexEntry(sourceHash, resourceSignature);
        }
    }

    private static void writeCacheIndexEntry(String slot, Path sourcePath, String sourceHash, String resourceSignature) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.setProperty(slot + ".path", sourcePath.toAbsolutePath().normalize().toString());
            CACHE_INDEX.setProperty(slot + ".sourceHash", sourceHash);
            CACHE_INDEX.setProperty(slot + ".resourceSignature", resourceSignature);
            writeCacheIndex(cacheDirectory());
        }
    }

    private static void removeCacheIndexEntry(Path cacheDir, String slot) {
        CACHE_INDEX.remove(slot + ".path");
        CACHE_INDEX.remove(slot + ".sourceHash");
        CACHE_INDEX.remove(slot + ".resourceSignature");
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d"));
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d.tmp"));
    }

    private static void writeCacheIndex(Path cacheDir) throws IOException {
        Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
        Path temporaryPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME + ".tmp");
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryPath))) {
            CACHE_INDEX.store(output, "QuickCraft Litematica 3D preview cache index");
        }
        moveCacheFile(temporaryPath, indexPath);
    }

    private record CacheIndexEntry(String sourceHash, String resourcePackSignature) {
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

    private static void moveCacheFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private interface WindowsClipboard extends StdCallLibrary {
        WindowsClipboard INSTANCE = Native.load("user32", WindowsClipboard.class, W32APIOptions.DEFAULT_OPTIONS);
        int CF_DIB = 8;
        int CF_DIBV5 = 17;

        boolean OpenClipboard(Pointer owner);

        boolean EmptyClipboard();

        Pointer SetClipboardData(int format, Pointer memoryHandle);

        boolean CloseClipboard();
    }

    private interface WindowsMemory extends StdCallLibrary {
        WindowsMemory INSTANCE = Native.load("kernel32", WindowsMemory.class, W32APIOptions.DEFAULT_OPTIONS);
        int GHND = 0x0042;
        int BITMAP_INFO_HEADER_SIZE = 40;
        int BITMAP_V5_HEADER_SIZE = 124;
        int BI_RGB = 0;
        int BI_BITFIELDS = 3;
        int LCS_SRGB = 0x73524742;
        int LCS_GM_IMAGES = 4;

        Pointer GlobalAlloc(int flags, BaseTSD.SIZE_T bytes);

        Pointer GlobalLock(Pointer memoryHandle);

        boolean GlobalUnlock(Pointer memoryHandle);

        Pointer GlobalFree(Pointer memoryHandle);
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
        private float pitch = DEFAULT_SLANT_RADIANS;
        private float scale = 1.0F;
        private float dx;
        private float dy;

        private void setViewport(int x, int y, int size) {
            if (this.size > 0 && this.size != size) {
                float ratio = size / (float) this.size;
                this.dx *= ratio;
                this.dy *= ratio;
            }
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
                this.pitch = Math.max(
                        -MAX_PITCH_RADIANS,
                        Math.min(MAX_PITCH_RADIANS, this.pitch + (float) deltaY * 0.015F)
                );
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
            this.scale = Math.max(0.05F, Math.min(20.0F, (float) (this.scale * Math.exp(amount * 0.12))));
        }

        private void setPreset(double yawDegrees, double pitchDegrees) {
            this.angle = Math.toRadians(yawDegrees);
            this.pitch = Math.max(-MAX_PITCH_RADIANS, Math.min(MAX_PITCH_RADIANS, (float) Math.toRadians(pitchDegrees)));
            this.scale = 1.0F;
            this.dx = 0.0F;
            this.dy = 0.0F;
        }

        private void stop() {
            this.activeButton = -1;
        }
    }

    private static final class MeshBuilder {
        private static MeshData build(DirectoryEntry entry, AtomicBoolean cancelled, ProgressSink progressSink) {
            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(entry.getDirectory(), entry.getName(), FileType.LITEMATICA_SCHEMATIC);
            throwIfCancelled(cancelled);
            if (schematic == null) {
                throw new IllegalStateException("Cannot read litematic file");
            }

            return build(schematic, cancelled, progressSink);
        }

        private static MeshData build(LitematicaSchematic schematic, AtomicBoolean cancelled, ProgressSink progressSink) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                throw new IllegalStateException("Litematica preview needs a loaded client world");
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
                    : schematicBlockEntities.getOrDefault(schematicPos.subtract(view.bounds.min()), new NbtCompound());
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

            setPreviewBlockEntityState(blockEntity, state);
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
            double rotationSafeSize = Math.sqrt(
                    (double) this.sizeX * this.sizeX
                            + (double) this.sizeY * this.sizeY
                            + (double) this.sizeZ * this.sizeZ
            );
            return (float) ((previewSize * 2.0 * PREVIEW_FIT_PADDING) / (Math.max(1.0, rotationSafeSize) * Math.max(1, screenHeight)));
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

                setPreviewBlockEntityState(blockEntity, state);
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
