package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.Collection;
import net.minecraft.util.math.random.Random;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.block.BlockEntityProvider;
import fi.dy.masa.litematica.util.FileType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.FakeLightingProvider;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;

/** 预览任务、缓存、交互和导出编排；原生渲染由版本后端提供。 */
public final class QuickLitematicaPreview3D {

    static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaPreview3D.class);
    static final AtomicBoolean SHADER_API_ERROR_LOGGED = new AtomicBoolean();
    static final AtomicBoolean SHADER_DISABLE_ERROR_LOGGED = new AtomicBoolean();
    // Minecraft 1.21.x 的预览方块实体没有非弃用的公开状态更新 API。
    @SuppressWarnings("deprecation")
    static void setPreviewBlockEntityState(BlockEntity blockEntity, BlockState state) {
        blockEntity.setCachedState(state);
    }

    static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderManager 多线程竞争。
    static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    static final int CACHE_MAGIC = 0x51435033; // QCP3
    static final String CACHE_DIR_NAME = "litematica-preview-cache";
    static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    static final String CACHE_INDEX_FILE_NAME = "cache-index.properties";
    static final int EXPAND_BUTTON_SIZE = 16;
    static final int COMPAT_CLIPBOARD_MAX_DIMENSION = 4096;
    static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    // 1600 万顶点约对应 704 MiB 静态 GPU 顶点数据；只放宽静态网格，动态内容上限仍保持原值。
    static final int MAX_UPLOAD_VERTICES = 16_000_000;
    static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    static final int MAX_DYNAMIC_ENTITIES = 8_192;
    // 动态模型只驻留显存、不写入 qcp3d；限制一次录制的 CPU/GPU 顶点总量，失败时回退逐帧渲染。
    static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;
    static final int MAX_DYNAMIC_RENDER_LAYERS = 1_024;
    static final int DYNAMIC_LAYER_INITIAL_BYTES = 64 * 1024;
    static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    static final float MAX_PITCH_RADIANS = (float) Math.toRadians(85.0);
    static final float PREVIEW_FIT_PADDING = 0.95F;
    static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    static final int VERTEX_BYTES = 44;
    static final int MAX_QUANTIZED_LAYER_BYTES = MAX_UPLOAD_VERTICES * QUANTIZED_VERTEX_BYTES;
    static final int CACHE_IO_CHUNK_BYTES = 1024 * 1024;
    // 不透明层按约 7.5 MiB 的量化顶点切批，限制单帧解码和 GPU 上传耗时；透明层必须保持全图整体排序。
    static final int STATIC_BATCH_TARGET_VERTICES = 250_000;
    static final float PROGRESS_START = 0.02F;
    static final float PROGRESS_MESHING_START = 0.10F;
    static final float PROGRESS_MESHING_END = 0.80F;
    static final float PROGRESS_CACHE_WRITE = 0.82F;
    static final float PROGRESS_STATIC_CACHE_END = 0.93F;
    static final float PROGRESS_BLOCK_STATES_CACHE_END = 0.95F;
    static final float PROGRESS_BLOCK_ENTITIES_CACHE_END = 0.99F;
    static final AtomicBoolean CACHE_DIRECTORY_READY = new AtomicBoolean();
    static final Object CACHE_INDEX_LOCK = new Object();
    static final Properties CACHE_INDEX = new Properties();
    // 当前页面只按此间隔检查文件大小/时间戳；完整 SHA-256 始终在后台且仅于重新核验时计算。
    static final long SOURCE_CHECK_INTERVAL_MILLIS = 1_000L;
    @Nullable
    static volatile Path currentCacheDirectory;

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
        if (previewEnabled
                && shaderPackActive
                && entry != null
                && isSupportedLitematic(entry)) {
            shaderPackActive = !prepare3DPreview();
        }
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

    public static boolean is3DPreviewAvailable() {
        return QuickCraftConfigs.isLitematica3DPreviewEnabled() && !isShaderPackActive();
    }

    public static boolean prepare3DPreview() {
        if (!isShaderPackActive()) return true;
        if (!QuickCraftConfigs.shouldAutoDisableShadersFor3DPreview()
                || !QuickLitematicaPreviewAccess.tryDisableShaders()) return false;
        boolean disabled = !isShaderPackActive();
        if (disabled) InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.shader_auto_disabled");
        return disabled;
    }

    public static final class Manager implements AutoCloseable {
        @Nullable
        Preview current;
        @Nullable
        Path currentPath;
        @Nullable
        DirectoryEntry currentEntry;
        final DragState drag = new DragState();
        final Screen owner;
        final Runnable previewMetadataRefresh;
        final AtomicBoolean previewImageWriteInProgress = new AtomicBoolean();
        @Nullable
        Consumer<Text> pendingPreviewImageCallback;
        boolean pendingPreviewImageRestoreFullscreen;
        boolean pendingPreviewImageKeepMaximized;
        int pendingPreviewImageWaitTicks;
        boolean hasEmbeddedPreviewImage;
        int viewX;
        int viewY;
        int viewSize;
        boolean showExpandButton;
        long nextSourceCheckMillis;

        Manager(Screen owner, Runnable previewMetadataRefresh) {
            this.owner = owner;
            this.previewMetadataRefresh = previewMetadataRefresh;
        }

        void render(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entryPath(entry).toAbsolutePath().normalize();
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

        void renderLauncher(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entryPath(entry).toAbsolutePath().normalize();
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

        void renderCurrent(DrawContext drawContext, int x, int y, int size, boolean showExpandButton) {
            if (!is3DPreviewAvailable()) {
                return;
            }

            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = showExpandButton;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            drawOutlinedBox(drawContext, this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
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

            preview.captureSnapshot(
                    EMBEDDED_PREVIEW_DIMENSION,
                    backgroundColor,
                    this.drag,
                    "quickcraft.litematica.preview_3d.preview_write_failed",
                    message -> {
                        this.previewImageWriteInProgress.set(false);
                        callback.accept(message);
                    },
                    image -> {
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
            );
        }

        void selectPreviewImage(Consumer<Text> callback) {
            if (!this.canEditPreviewImage() || this.current == null || this.currentPath == null) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getWindow().isFullscreen()) {
                // toggleFullscreen() 只改标记，真正退出独占全屏要等下一帧 swapBuffers。
                client.getWindow().toggleFullscreen();
                this.pendingPreviewImageCallback = callback;
                this.pendingPreviewImageRestoreFullscreen = true;
                this.pendingPreviewImageKeepMaximized = true;
                this.pendingPreviewImageWaitTicks = 5;
                return;
            }
            this.openPreviewImagePicker(callback, false, isMaximizedWindow());
        }

        void pollPendingPreviewImagePicker() {
            Consumer<Text> callback = this.pendingPreviewImageCallback;
            if (callback == null) {
                return;
            }
            if (this.pendingPreviewImageRestoreFullscreen
                    && this.pendingPreviewImageWaitTicks > 0
                    && isExclusiveFullscreen()) {
                this.pendingPreviewImageWaitTicks--;
                return;
            }
            this.pendingPreviewImageCallback = null;
            boolean restore = this.pendingPreviewImageRestoreFullscreen;
            boolean keepMaximized = this.pendingPreviewImageKeepMaximized;
            this.pendingPreviewImageRestoreFullscreen = false;
            this.pendingPreviewImageKeepMaximized = false;
            this.pendingPreviewImageWaitTicks = 0;
            this.openPreviewImagePicker(callback, restore, keepMaximized);
        }

        void cancelPendingPreviewImagePicker() {
            if (this.pendingPreviewImageCallback == null && !this.pendingPreviewImageRestoreFullscreen) {
                return;
            }
            this.pendingPreviewImageCallback = null;
            boolean restore = this.pendingPreviewImageRestoreFullscreen;
            boolean keepMaximized = this.pendingPreviewImageKeepMaximized;
            this.pendingPreviewImageRestoreFullscreen = false;
            this.pendingPreviewImageKeepMaximized = false;
            this.pendingPreviewImageWaitTicks = 0;
            restoreGameWindow(restore, keepMaximized);
        }

        void openPreviewImagePicker(Consumer<Text> callback, boolean restoreFullscreen, boolean keepMaximized) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (preview == null || target == null) {
                restoreGameWindow(restoreFullscreen, keepMaximized);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            Path selected;
            if (keepMaximized) {
                maximizeGameWindow();
            }
            long handle = MinecraftClient.getInstance().getWindow().getHandle();
            setAutoIconify(handle, false);
            try {
                selected = QuickLitematicaPreviewImageWriter.chooseImage(target);
            } catch (Throwable throwable) {
                LOGGER.error("Failed to open the preview image file picker", throwable);
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            } finally {
                setAutoIconify(handle, true);
                restoreGameWindow(restoreFullscreen, keepMaximized);
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

        static long gameWindowHandle() {
            return MinecraftClient.getInstance().getWindow().getHandle();
        }

        static boolean isExclusiveFullscreen() {
            long handle = gameWindowHandle();
            return handle != 0L && GLFW.glfwGetWindowMonitor(handle) != 0L;
        }

        static boolean isMaximizedWindow() {
            long handle = gameWindowHandle();
            return handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) != GLFW.GLFW_FALSE;
        }

        static void maximizeGameWindow() {
            long handle = gameWindowHandle();
            if (handle == 0L) {
                return;
            }
            if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE) {
                GLFW.glfwRestoreWindow(handle);
            }
            if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_FALSE) {
                GLFW.glfwMaximizeWindow(handle);
            }
        }

        static void setAutoIconify(long handle, boolean enabled) {
            if (handle == 0L) {
                return;
            }
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, enabled ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }

        static void restoreGameWindow(boolean restoreFullscreen, boolean keepMaximized) {
            MinecraftClient client = MinecraftClient.getInstance();
            long handle = client.getWindow().getHandle();
            if (handle != 0L) {
                boolean iconified = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE;
                if (keepMaximized) {
                    maximizeGameWindow();
                } else if (iconified) {
                    GLFW.glfwRestoreWindow(handle);
                }
                GLFW.glfwShowWindow(handle);
                GLFW.glfwFocusWindow(handle);
                restoreWindowsGameWindow(handle, keepMaximized, iconified);
            }
            if (restoreFullscreen && !client.getWindow().isFullscreen()) {
                client.getWindow().toggleFullscreen();
            }
        }

        static void restoreWindowsGameWindow(long glfwHandle, boolean keepMaximized, boolean iconified) {
            if (!Platform.isWindows()) {
                return;
            }
            try {
                long hwndValue = GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
                if (hwndValue == 0L) {
                    return;
                }
                HWND hwnd = new HWND();
                hwnd.setPointer(new Pointer(hwndValue));
                if (keepMaximized) {
                    User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWMAXIMIZED);
                } else if (iconified) {
                    User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
                }
                User32.INSTANCE.SetForegroundWindow(hwnd);
            } catch (Throwable ignored) {
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
        static int[] makeArgbPixels(NativeImage image) {
            return image.makePixelArray();
        }

        void writePreviewAsync(Preview preview, Path target, int[] pixels, Consumer<Text> callback) {
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

        void finishPreviewWrite(Preview preview, boolean hasEmbeddedPreview, Consumer<Text> callback, Text message) {
            MinecraftClient.getInstance().execute(() -> {
                this.previewImageWriteInProgress.set(false);
                if (this.current == preview) {
                    this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
                }
                try {
                    this.previewMetadataRefresh.run();
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to refresh the Litematica preview image cache", throwable);
                }
                callback.accept(message);
            });
        }

        void failPreviewWrite(Consumer<Text> callback, Throwable throwable) {
            MinecraftClient.getInstance().execute(() -> {
                this.previewImageWriteInProgress.set(false);
                callback.accept(Text.translatable(
                        QuickLitematicaPreviewImageWriter.failureTranslationKey(throwable)
                ));
            });
        }

        @Override
        public void close() {
            this.cancelPendingPreviewImagePicker();
            this.clearCurrent();
        }

        boolean canHandleMouse(double mouseX, double mouseY) {
            return (this.current != null || this.currentEntry != null)
                    && is3DPreviewAvailable()
                    && this.drag.inViewport(mouseX, mouseY);
        }

        void switchTo(Path path, DirectoryEntry entry) {
            this.clearCurrent();
            this.currentPath = path;
            this.current = Preview.create(entry);
            this.nextSourceCheckMillis = System.currentTimeMillis() + SOURCE_CHECK_INTERVAL_MILLIS;
        }

        void clearCurrent() {
            this.currentPath = null;
            this.currentEntry = null;
            this.hasEmbeddedPreviewImage = false;
            if (this.current != null) {
                this.current.close();
                this.current = null;
            }
            this.drag.stop();
        }

        void drawExpandButton(DrawContext context) {
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

        void drawExpandIcon(DrawContext context, int x, int y, int color) {
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

        boolean isExpandButtonHovered(double mouseX, double mouseY) {
            int x = this.viewX + this.viewSize - EXPAND_BUTTON_SIZE - 3;
            int y = this.viewY + 3;
            return mouseX >= x && mouseX < x + EXPAND_BUTTON_SIZE && mouseY >= y && mouseY < y + EXPAND_BUTTON_SIZE;
        }

        void releasePreview() {
            this.clearCurrent();
        }
    }
    abstract static class Preview implements AutoCloseable {
        final Path sourcePath;

        final Path cachePath;

        final Path tmpPath;

        final String cacheSlot;

        final String resourcePackSignature;

        volatile long sourceSize;

        volatile long sourceModifiedMillis;

        final long startedAtNanos = System.nanoTime();

        final AtomicBoolean cancelled = new AtomicBoolean();

        volatile MeshData meshData;

        volatile float progress;

        volatile State state = State.LOADING;

        @Nullable
        volatile Future<?> future;

        final ConcurrentLinkedQueue<LayerMesh> pendingStaticLayers = new ConcurrentLinkedQueue<>();

        @Nullable
        volatile PreviewDimensions dimensions;

        boolean dynamicBuffersReady;

        boolean dynamicBufferFallback;

        boolean uploadScheduled;

        boolean staticUploadComplete;

        boolean dynamicPreparationArmed;

        final AtomicBoolean snapshotInProgress = new AtomicBoolean();

        Preview(
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

        static Preview create(DirectoryEntry entry) {
            Path sourcePath = entryPath(entry).toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new NativePreview(
                    sourcePath,
                    cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot,
                    currentResourcePackSignature()
            );
            preview.progress = PROGRESS_START;
            preview.future = PREVIEW_EXECUTOR.submit(preview::loadOrBuild);
            return preview;
        }

        static Preview createGenerated(String displayName, Supplier<LitematicaSchematic> schematicSupplier) {
            String safeName = displayName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (safeName.isBlank()) {
                safeName = "selection";
            }
            Path transientPath = cacheDirectory().resolve("selection-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
            Preview preview = new NativePreview(
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

        void loadGenerated(Supplier<LitematicaSchematic> schematicSupplier) {
            try {
                this.state = State.BUILDING;
                LitematicaSchematic schematic = schematicSupplier.get();
                this.throwIfCancelled();
                if (schematic == null) {
                    throw new IllegalStateException("Cannot capture Litematica selection");
                }
                MeshData built = MeshBuilder.build(
                        schematic,
                        this.cancelled,
                        value -> this.progress = value,
                        this::initializeDimensions,
                        this::publishStaticLayers
                );
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
                this.discardPartialStatic();
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.progress = 1.0F;
                this.discardPartialStatic();
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                    this.discardPartialStatic();
                } else if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    this.discardPartialStatic();
                } else {
                    LOGGER.error("Failed to build a 3D preview from the Litematica selection", e);
                    this.state = State.FAILED;
                    this.discardPartialStatic();
                }
            }
        }

        void loadOrBuild() {
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
                boolean sourceHashMatches = indexEntry != null && sourceHash.equals(indexEntry.sourceHash());
                boolean cacheSignatureMatches = sourceHashMatches
                        && this.resourcePackSignature.equals(indexEntry.resourcePackSignature());
                MeshData cached = cacheSignatureMatches ? CacheFile.read(readCachePath, this.cancelled) : null;
                if (cached != null) {
                    this.throwIfCancelled();
                    this.initializeDimensions(cached.sizeX(), cached.sizeY(), cached.sizeZ());
                    this.publishStaticLayers(cached.layers());
                    this.meshData = cached;
                    this.progress = 1.0F;
                    this.state = State.READY;
                    return;
                }

                this.state = State.BUILDING;
                Path convertedPath = this.convertedSchematicPath();
                LitematicaSchematic schematic = sourceHashMatches
                        ? MeshBuilder.readSchematic(convertedPath, this.cancelled, false)
                        : null;
                boolean convertedCacheHit = schematic != null;
                if (!convertedCacheHit) {
                    deleteQuietly(convertedPath);
                    schematic = MeshBuilder.readSchematic(this.sourcePath, this.cancelled, true);
                }
                MeshData built = MeshBuilder.build(
                        schematic,
                        this.cancelled,
                        value -> this.progress = value,
                        this::initializeDimensions,
                        this::publishStaticLayers
                );
                this.throwIfCancelled();
                if (!built.withinBudget()) {
                    built.closeDynamic();
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    deleteTmpQuietly(this.tmpPath);
                    deleteQuietly(this.cachePath);
                    return;
                }

                List<LayerMesh> cacheLayers = List.copyOf(built.layers());
                this.meshData = built;
                this.progress = 1.0F;
                this.state = State.READY;
                Path writeCachePath = this.cachePath;
                Path writeTmpPath = this.tmpPath;
                try {
                    CacheFile.writeAtomically(writeTmpPath, writeCachePath, built, cacheLayers, this.cancelled, ignored -> {});
                    writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                    this.throwIfCancelled();
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception ignored) {
                    deleteTmpQuietly(writeTmpPath);
                    deleteQuietly(writeCachePath);
                }

                if (!convertedCacheHit
                        && schematic.getMetadata().getMinecraftDataVersion() < LitematicaSchematic.MINECRAFT_DATA_VERSION_1_20_4) {
                    this.writeConvertedSchematic(schematic, convertedPath);
                }
            } catch (CancellationException ignored) {
                this.state = State.CANCELLED;
                this.discardPartialStatic();
                deleteTmpQuietly(this.tmpPath);
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.progress = 1.0F;
                this.discardPartialStatic();
                deleteTmpQuietly(this.tmpPath);
                deleteQuietly(this.cachePath);
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                    this.discardPartialStatic();
                    deleteTmpQuietly(this.tmpPath);
                    return;
                }
                if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.progress = 1.0F;
                    this.discardPartialStatic();
                    deleteTmpQuietly(this.tmpPath);
                    deleteQuietly(this.cachePath);
                    return;
                }
                LOGGER.error("Failed to build 3D preview for {}", this.sourceName(), e);
                this.state = State.FAILED;
                this.discardPartialStatic();
                deleteTmpQuietly(this.tmpPath);
                deleteQuietly(this.cachePath);
            }
        }

        void captureSourceStamp() {
            try {
                this.sourceSize = Files.size(this.sourcePath);
                this.sourceModifiedMillis = Files.getLastModifiedTime(this.sourcePath).toMillis();
            } catch (IOException e) {
                this.sourceSize = -1L;
                this.sourceModifiedMillis = -1L;
            }
        }

        boolean sourceStampChanged() {
            try {
                return Files.size(this.sourcePath) != this.sourceSize
                        || Files.getLastModifiedTime(this.sourcePath).toMillis() != this.sourceModifiedMillis
                        || !currentResourcePackSignature().equals(this.resourcePackSignature);
            } catch (IOException ignored) {
                return true;
            }
        }

        String sourceName() {
            Path fileName = this.sourcePath.getFileName();
            return fileName == null ? this.sourcePath.toString() : fileName.toString();
        }

        Path convertedSchematicPath() {
            return this.cachePath.resolveSibling(this.cacheSlot + ".converted.litematic");
        }

        void writeConvertedSchematic(LitematicaSchematic schematic, Path convertedPath) {
            Path parent = convertedPath.getParent();
            Path fileName = convertedPath.getFileName();
            if (parent == null || fileName == null) {
                return;
            }

            Path temporary = convertedPath.resolveSibling(this.cacheSlot + ".converted.tmp.litematic");
            Path temporaryName = temporary.getFileName();
            if (temporaryName == null) {
                return;
            }

            try {
                deleteQuietly(temporary);
                if (!writeSchematic(schematic, parent, temporaryName.toString())) {
                    throw new IOException("Litematica rejected the converted cache write");
                }
                moveCacheFile(temporary, convertedPath);
            } catch (Exception ignored) {
                deleteQuietly(temporary);
                deleteQuietly(convertedPath);
            }
        }

        void initializeDimensions(int sizeX, int sizeY, int sizeZ) {
            this.dimensions = new PreviewDimensions(sizeX, sizeY, sizeZ);
        }

        void publishStaticLayers(List<LayerMesh> layers) {
            if (!this.cancelled.get()) {
                this.pendingStaticLayers.addAll(layers);
            }
        }

        void discardPartialStatic() {
            this.pendingStaticLayers.clear();
            this.dimensions = null;
            this.closeRendererOnRenderThread();
        }

        void completeStaticUploadIfReady() {
            if (this.staticUploadComplete || this.uploadScheduled || this.state != State.READY || !this.pendingStaticLayers.isEmpty()) {
                return;
            }

            this.staticUploadComplete = true;
            MeshData data = this.meshData;
            if (data != null) {
                data.releaseStaticVertices();
            }
        }

        void markTooLarge(@Nullable MeshData data) {
            this.closeBuffers();
            if (data != null) {
                data.closeDynamic();
            }
            this.pendingStaticLayers.clear();
            this.meshData = null;
            this.dimensions = null;
            this.state = State.TOO_LARGE;
            this.progress = 1.0F;
            deleteTmpQuietly(this.tmpPath);
            deleteQuietly(this.cachePath);
        }

        void releaseMeshData() {
            this.closeBuffers();
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.dimensions = null;
            this.pendingStaticLayers.clear();
        }

        void refreshCacheSourceHash() {
            try {
                String sourceHash = hashFile(this.sourcePath);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                this.captureSourceStamp();
            } catch (IOException ignored) {
            }
        }

        int recommendedExportResolution() {
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

        // Minecraft 客户端会启用 java.awt.headless，图片剪贴板必须绕过 AWT 直接写 Win32。
        static void copyToWindowsClipboard(NativeImage image, boolean preserveTransparency) throws InterruptedException {
            int width = image.getWidth();
            int height = image.getHeight();
            double compatScale = Math.min(1.0, COMPAT_CLIPBOARD_MAX_DIMENSION / (double) Math.max(width, height));
            int compatWidth = Math.max(1, (int) Math.round(width * compatScale));
            int compatHeight = Math.max(1, (int) Math.round(height * compatScale));
            byte[] pngBytes;
            try {
                pngBytes = preserveTransparency ? encodeClipboardPng(image, compatWidth, compatHeight) : null;
            } catch (IOException e) {
                throw new IllegalStateException("Could not encode transparent clipboard PNG", e);
            }
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
            boolean clipboardOwnsPng = false;
            boolean clipboardOpen = false;
            Pointer pngHandle = null;
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
                            row[x] = readImageArgb(image, x, y);
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
                                compatRow[x] = readImageArgb(image, sourceX, sourceY);
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

                if (pngBytes != null) {
                    pngHandle = WindowsMemory.INSTANCE.GlobalAlloc(
                            WindowsMemory.GHND,
                            new BaseTSD.SIZE_T(pngBytes.length)
                    );
                    if (pngHandle == null) {
                        throw new IllegalStateException("GlobalAlloc(PNG) failed");
                    }
                    Pointer pngMemory = WindowsMemory.INSTANCE.GlobalLock(pngHandle);
                    if (pngMemory == null) {
                        throw new IllegalStateException("GlobalLock(PNG) failed");
                    }
                    try {
                        pngMemory.write(0, pngBytes, 0, pngBytes.length);
                    } finally {
                        WindowsMemory.INSTANCE.GlobalUnlock(pngHandle);
                    }
                }

                clipboardOpen = openWindowsClipboard();
                if (!clipboardOpen || !WindowsClipboard.INSTANCE.EmptyClipboard()) {
                    throw new IllegalStateException("Windows clipboard is unavailable");
                }
                if (pngHandle != null) {
                    int pngFormat = WindowsClipboard.INSTANCE.RegisterClipboardFormat("PNG");
                    if (pngFormat == 0 || WindowsClipboard.INSTANCE.SetClipboardData(pngFormat, pngHandle) == null) {
                        throw new IllegalStateException("SetClipboardData(PNG) failed");
                    }
                    clipboardOwnsPng = true;
                }

                // 不认识 PNG 的程序按枚举顺序使用 DIB；支持 PNG 的程序可保留完整 Alpha。
                if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIB, dibHandle) == null) {
                    throw new IllegalStateException("SetClipboardData(CF_DIB) failed");
                }
                clipboardOwnsDib = true;
                clipboardOwnsDibV5 = WindowsClipboard.INSTANCE.SetClipboardData(
                        WindowsClipboard.CF_DIBV5,
                        dibV5Handle
                ) != null;
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
                if (!clipboardOwnsPng && pngHandle != null) {
                    WindowsMemory.INSTANCE.GlobalFree(pngHandle);
                }
            }
        }

        static byte[] encodeClipboardPng(NativeImage image, int width, int height) throws IOException {
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                int sourceY = Math.min(image.getHeight() - 1, (int) ((y + 0.5) * image.getHeight() / height));
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.min(image.getWidth() - 1, (int) ((x + 0.5) * image.getWidth() / width));
                    row[x] = readImageArgb(image, sourceX, sourceY);
                }
                bufferedImage.setRGB(0, y, width, 1, row, 0, width);
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(bufferedImage, "png", output)) {
                    throw new IOException("No PNG writer is available");
                }
                return output.toByteArray();
            }
        }

        static boolean openWindowsClipboard() throws InterruptedException {
            for (int attempt = 0; attempt < 5; attempt++) {
                if (WindowsClipboard.INSTANCE.OpenClipboard(null)) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return false;
        }

        static byte[] createBitmapV5Header(int width, int height) {
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

        static byte[] createBitmapInfoHeader(int width, int height) {
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

        Path nextOutputPath(Path outputDirectory, int resolution) {
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

        float displayProgress() {
            if (this.progress >= PROGRESS_MESHING_START || this.state == State.FAILED || this.state == State.TOO_LARGE) {
                return this.progress;
            }

            // Litematica/DataFixer 读取和 GZIP 缓存解压没有可观测的完成量；只在这段等待里平滑补到扫描开始前。
            float elapsedSeconds = (System.nanoTime() - this.startedAtNanos) / 1_000_000_000.0F;
            float readingProgress = Math.min(PROGRESS_MESHING_START - 0.01F, PROGRESS_START + elapsedSeconds * 0.02F);
            return Math.max(this.progress, readingProgress);
        }

        void throwIfCancelled() {
            if (this.isCancelled()) {
                throw new CancellationException();
            }
        }

        boolean isCancelled() {
            return this.cancelled.get() || Thread.currentThread().isInterrupted();
        }

        void uploadIfNeeded() {
            if (this.uploadScheduled || this.cancelled.get()) return;
            LayerMesh layer = this.pendingStaticLayers.poll();
            if (layer == null) {
                this.completeStaticUploadIfReady();
                return;
            }
            this.uploadScheduled = true;
            Runnable upload = () -> {
                try {
                    if (!this.cancelled.get()) this.uploadMesh(layer);
                } catch (Throwable failure) {
                    LOGGER.error("Failed to upload a 3D preview batch for {}", this.sourceName(), failure);
                    this.markTooLarge(this.meshData);
                } finally {
                    this.uploadScheduled = false;
                    this.completeStaticUploadIfReady();
                }
            };
            if (RenderSystem.isOnRenderThread()) upload.run();
            else MinecraftClient.getInstance().execute(upload);
        }

        void captureSnapshot(
                int resolution,
                int backgroundColor,
                DragState drag,
                String failureTranslationKey,
                Consumer<Text> messageCallback,
                Consumer<NativeImage> imageCallback
        ) {
            if (isShaderPackActive()) {
                messageCallback.accept(Text.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                return;
            }

            MeshData data = this.meshData;
            if (this.state != State.READY || data == null) {
                messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                return;
            }

            this.uploadIfNeeded();
            if (!this.staticUploadComplete) {
                messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                return;
            }
            this.prepareDynamicBuffers(data);
            if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_dynamic_failed"));
                return;
            }
            if (data.vertexCount() > 0 && !this.hasUploadedBuffers()) {
                messageCallback.accept(Text.translatable(failureTranslationKey));
                return;
            }
            if (!this.snapshotInProgress.compareAndSet(false, true)) {
                messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.exporting"));
                return;
            }

            this.takeNativeSnapshot(resolution, backgroundColor, drag, data, imageCallback, failure -> {
                this.snapshotInProgress.set(false);
                messageCallback.accept(Text.translatable(failureTranslationKey));
            });
        }

        void exportPng(int resolution, int backgroundColor, DragState drag, Path outputDirectory, Consumer<Text> callback) {
            this.captureSnapshot(
                    resolution,
                    backgroundColor,
                    drag,
                    "quickcraft.litematica.preview_3d.export_failed",
                    callback, image -> {

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
            });
        }

        void copyImage(int resolution, int backgroundColor, DragState drag, Consumer<Text> callback) {
            if (!Platform.isWindows()) {
                callback.accept(Text.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                return;
            }

            this.captureSnapshot(
                    resolution,
                    backgroundColor,
                    drag,
                    "quickcraft.litematica.preview_3d.copy_failed",
                    callback, image -> {

            Util.getIoWorkerExecutor().execute(() -> {
                try {
                    copyToWindowsClipboard(image, ((backgroundColor >>> 24) & 0xFF) != 0xFF);
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
            });
        }

        @Override
        public void close() {
            this.cancelled.set(true);
            Future<?> task = this.future;
            if (task != null) {
                task.cancel(true);
            }

            deleteTmpQuietly(this.tmpPath);
            this.pendingStaticLayers.clear();
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.dimensions = null;
            this.closeRendererOnRenderThread();
        }

        void closeRendererOnRenderThread() {
            if (RenderSystem.isOnRenderThread()) this.closeRenderer();
            else MinecraftClient.getInstance().execute(this::closeRenderer);
        }

        static int readImageArgb(NativeImage image, int x, int y) {
            return NativePreview.readImageArgb(image, x, y);
        }

        final void render(DrawContext context, int x, int y, int size, DragState drag) {
            State currentState = this.state;
            if ((currentState == State.BUILDING || currentState == State.READY) && this.dimensions != null) {
                this.uploadIfNeeded();
                MeshData data = this.meshData;
                if (this.hasUploadedBuffers()
                        || currentState == State.READY && data != null && (data.hasDynamicContent() || this.staticUploadComplete)) {
                    this.drawPreview(context, x, y, size, drag);
                    return;
                }
            }
            this.renderProgress(context, x, y, size);
        }

        void renderProgress(DrawContext context, int x, int y, int size) {
            int barWidth = Math.max(24, size - 12);
            int barX = x + (size - barWidth) / 2;
            int barY = y + size / 2 - 5;
            int fill = Math.max(0, Math.min(barWidth - 2, (int) ((barWidth - 2) * this.displayProgress())));
            boolean failed = this.state == State.FAILED || this.state == State.TOO_LARGE;
            String text = switch (this.state) {
                case FAILED -> StringUtils.translate("quickcraft.litematica.preview_3d.failed");
                case TOO_LARGE -> StringUtils.translate("quickcraft.litematica.preview_3d.too_large");
                default -> StringUtils.translate("quickcraft.litematica.preview_3d.rendering");
            };
            context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x + size / 2, barY - 14,
                    failed ? 0xFFFF7777 : 0xFFDDDDDD);
            drawOutlinedBox(context, barX, barY, barWidth, 10, 0xB0000000, 0xFF707070);
            if (fill > 0) context.fill(barX + 1, barY + 1, barX + 1 + fill, barY + 9, failed ? 0xFFAA3333 : 0xFF4DB36A);
        }

        abstract void drawPreview(DrawContext context, int x, int y, int size, DragState drag);

        abstract void uploadMesh(LayerMesh mesh);
        abstract boolean hasUploadedBuffers();
        abstract void prepareDynamicBuffers(MeshData data);
        abstract void closeBuffers();
        abstract void closeRenderer();
        abstract void takeNativeSnapshot(int resolution, int backgroundColor, DragState drag, MeshData data,
                                         Consumer<NativeImage> imageCallback, Consumer<Throwable> failureCallback);
    }

    static final class DynamicBufferTooLargeException extends RuntimeException {
        DynamicBufferTooLargeException(String message) {
            super(message);
        }
    }

    static final class PreviewTooLargeException extends RuntimeException {
    }

    static boolean isPreviewTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PreviewTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    static String hashFileCancellable(Path path, AtomicBoolean cancelled) throws IOException {
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

    static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static Path cacheDirectory() {
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

    static void prepareCacheDirectory(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            Path versionFile = cacheDir.resolve(CACHE_VERSION_FILE_NAME);
            String currentVersion = currentCacheVersionToken();
            String storedVersion = readCacheVersion(versionFile);
            if (!currentVersion.equals(storedVersion)) {
                clearRenderCacheFiles(cacheDir);
                Files.writeString(versionFile, currentVersion, java.nio.charset.StandardCharsets.UTF_8);
            }
            loadAndCleanCacheIndex(cacheDir);
        } catch (IOException ignored) {
        }
    }

    static void loadAndCleanCacheIndex(Path cacheDir) throws IOException {
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
                if (!sourceExists) {
                    staleSlots.add(slot);
                } else {
                    if (Files.isRegularFile(cachePath)) {
                        retainedCacheFiles.add(cachePath.getFileName().toString());
                    }
                    retainedCacheFiles.add(slot + ".converted.litematic");
                }
            }

            staleSlots.forEach(slot -> removeCacheIndexEntry(cacheDir, slot));
            try (var files = Files.list(cacheDir)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".tmp")
                                    || name.endsWith(".converted.tmp.litematic")
                                    || (name.endsWith(".qcp3d") || name.endsWith(".converted.litematic"))
                                    && !retainedCacheFiles.contains(name);
                        })
                        .forEach(QuickLitematicaPreview3D::deleteQuietly);
            }
            writeCacheIndex(cacheDir);
        }
    }

    @Nullable
    static CacheIndexEntry readCacheIndexEntry(String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            String sourceHash = CACHE_INDEX.getProperty(slot + ".sourceHash");
            String resourceSignature = CACHE_INDEX.getProperty(slot + ".resourceSignature");
            return sourceHash == null || resourceSignature == null
                    ? null
                    : new CacheIndexEntry(sourceHash, resourceSignature);
        }
    }

    static void writeCacheIndexEntry(String slot, Path sourcePath, String sourceHash, String resourceSignature) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.setProperty(slot + ".path", sourcePath.toAbsolutePath().normalize().toString());
            CACHE_INDEX.setProperty(slot + ".sourceHash", sourceHash);
            CACHE_INDEX.setProperty(slot + ".resourceSignature", resourceSignature);
            writeCacheIndex(cacheDirectory());
        }
    }

    static void removeCacheIndexEntry(Path cacheDir, String slot) {
        CACHE_INDEX.remove(slot + ".path");
        CACHE_INDEX.remove(slot + ".sourceHash");
        CACHE_INDEX.remove(slot + ".resourceSignature");
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d"));
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d.tmp"));
        deleteQuietly(cacheDir.resolve(slot + ".converted.litematic"));
        deleteQuietly(cacheDir.resolve(slot + ".converted.tmp.litematic"));
    }

    static void writeCacheIndex(Path cacheDir) throws IOException {
        Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
        Path temporaryPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME + ".tmp");
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryPath))) {
            CACHE_INDEX.store(output, "QuickCraft Litematica 3D preview cache index");
        }
        moveCacheFile(temporaryPath, indexPath);
    }

    record CacheIndexEntry(String sourceHash, String resourcePackSignature) {
    }

    static String currentCacheVersionToken() {
        // 格式和渲染语义共同决定失效；发行版本号不影响缓存复用。
        return CACHE_FORMAT_VERSION + "|" + CACHE_RENDER_MARKER
                + "|ctm:" + ctmRuntimeToken();
    }

    @Nullable
    static String readCacheVersion(Path versionFile) {
        if (!Files.isRegularFile(versionFile)) {
            return null;
        }

        try {
            return Files.readString(versionFile, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void clearRenderCacheFiles(Path cacheDir) {
        try (var paths = Files.list(cacheDir)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return !CACHE_INDEX_FILE_NAME.equals(name);
                    })
                    .forEach(QuickLitematicaPreview3D::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
        }
    }

    static void deleteRecursivelyQuietly(Path path) {
        if (Files.isDirectory(path)) {
            try (var paths = Files.walk(path)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(QuickLitematicaPreview3D::deleteQuietly);
            } catch (IOException ignored) {
            }
            return;
        }

        deleteQuietly(path);
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    static void deleteTmpQuietly(Path path) {
        if (path.getFileName() != null && path.getFileName().toString().endsWith(".tmp")) {
            deleteQuietly(path);
        }
    }

    static void moveCacheFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    interface WindowsClipboard extends StdCallLibrary {
        WindowsClipboard INSTANCE = Native.load("user32", WindowsClipboard.class, W32APIOptions.DEFAULT_OPTIONS);
        int CF_DIB = 8;
        int CF_DIBV5 = 17;

        boolean OpenClipboard(Pointer owner);

        boolean EmptyClipboard();

        int RegisterClipboardFormat(String formatName);

        Pointer SetClipboardData(int format, Pointer memoryHandle);

        boolean CloseClipboard();
    }

    interface WindowsMemory extends StdCallLibrary {
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

    enum State {
        LOADING,
        BUILDING,
        READY,
        FAILED,
        TOO_LARGE,
        CANCELLED
    }

    static final class DragState {
        int x;
        int y;
        int size;
        int activeButton = -1;
        double angle = Math.PI / 4.0;
        float pitch = DEFAULT_SLANT_RADIANS;
        float scale = 1.0F;
        float dx;
        float dy;

        void setViewport(int x, int y, int size) {
            if (this.size > 0 && this.size != size) {
                float ratio = size / (float) this.size;
                this.dx *= ratio;
                this.dy *= ratio;
            }
            this.x = x;
            this.y = y;
            this.size = size;
        }

        boolean inViewport(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.size && mouseY < this.y + this.size;
        }

        void click(int button) {
            this.activeButton = button;
        }

        boolean drag(int button, double deltaX, double deltaY) {
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

        boolean release(int button) {
            boolean handled = this.activeButton == button;
            if (handled) {
                this.activeButton = -1;
            }
            return handled;
        }

        void scaleBy(double amount) {
            this.scale = Math.max(0.05F, Math.min(20.0F, (float) (this.scale * Math.exp(amount * 0.12))));
        }

        void setPreset(double yawDegrees, double pitchDegrees) {
            this.angle = Math.toRadians(yawDegrees);
            this.pitch = Math.max(-MAX_PITCH_RADIANS, Math.min(MAX_PITCH_RADIANS, (float) Math.toRadians(pitchDegrees)));
            this.scale = 1.0F;
            this.dx = 0.0F;
            this.dy = 0.0F;
        }

        void stop() {
            this.activeButton = -1;
        }
    }

    record LayerMesh(LayerKey layer, byte[] quantizedVertices) {
        int vertexCount() {
            return this.quantizedVertices.length / QUANTIZED_VERTEX_BYTES;
        }
    }

    record PreviewDimensions(int sizeX, int sizeY, int sizeZ) {
        float scaleFactor(int previewSize, int screenHeight) {
            double rotationSafeSize = Math.sqrt(
                    (double) this.sizeX * this.sizeX
                            + (double) this.sizeY * this.sizeY
                            + (double) this.sizeZ * this.sizeZ
            );
            return (float) ((previewSize * 2.0 * PREVIEW_FIT_PADDING) / (Math.max(1.0, rotationSafeSize) * Math.max(1, screenHeight)));
        }
    }

    static final class MeshData {
        List<LayerMesh> layers;
        final List<BlockStateData> blockStates;
        final List<BlockEntityData> blockEntities;
        final List<EntityData> entities;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        @Nullable
        DynamicScene dynamicScene;

        MeshData(List<LayerMesh> layers, List<BlockStateData> blockStates, List<BlockEntityData> blockEntities, List<EntityData> entities, int sizeX, int sizeY, int sizeZ) {
            this.layers = layers;
            this.blockStates = List.copyOf(blockStates);
            this.blockEntities = List.copyOf(blockEntities);
            this.entities = List.copyOf(entities);
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        List<LayerMesh> layers() {
            return this.layers;
        }

        int sizeX() {
            return this.sizeX;
        }

        int sizeY() {
            return this.sizeY;
        }

        int sizeZ() {
            return this.sizeZ;
        }

        int vertexCount() {
            int count = 0;
            for (LayerMesh layer : this.layers) {
                count += layer.vertexCount();
            }
            return count;
        }

        boolean withinBudget() {
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

        void releaseStaticVertices() {
            this.layers = List.of();
        }

        boolean hasDynamicContent() {
            return !this.blockEntities.isEmpty() || !this.entities.isEmpty();
        }

        DynamicScene dynamicScene() {
            DynamicScene scene = this.dynamicScene;
            if (scene == null) {
                scene = DynamicScene.create(this.blockStates, this.blockEntities, this.entities);
                this.dynamicScene = scene;
            }
            return scene;
        }

        void closeDynamic() {
            this.dynamicScene = null;
        }
    }

    record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    record DynamicScene(DummyWorld world, Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
        static DynamicScene create(List<BlockStateData> blockStateData, List<BlockEntityData> blockEntityData, List<EntityData> entityData) {
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

        boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }

        DynamicScene(Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
            this(null, blockEntities, entities);
        }
    }

    record RegionBounds(BlockPos min, BlockPos max) {
        static RegionBounds from(Box box) {
            BlockPos first = box.getPos1();
            BlockPos pos1 = first != null ? first : BlockPos.ORIGIN;
            BlockPos second = box.getPos2();
            BlockPos pos2 = second != null ? second : pos1;
            return new RegionBounds(BlockPos.min(pos1, pos2), BlockPos.max(pos1, pos2));
        }

        long volume() {
            return (long) (this.max.getX() - this.min.getX() + 1)
                    * (this.max.getY() - this.min.getY() + 1)
                    * (this.max.getZ() - this.min.getZ() + 1);
        }

        int sizeX() {
            return this.max.getX() - this.min.getX() + 1;
        }

        int sizeY() {
            return this.max.getY() - this.min.getY() + 1;
        }

        int sizeZ() {
            return this.max.getZ() - this.min.getZ() + 1;
        }
    }

    static final class RegionBlockView implements BlockRenderView {
        final RegionBounds bounds;
        final LitematicaBlockStateContainer blockStateContainer;
        final MinecraftClient client = MinecraftClient.getInstance();
        final LightingProvider lightingProvider;

        RegionBlockView(LitematicaBlockStateContainer container, Box area) {
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

    interface ProgressSink {
        void set(float value);
    }

    interface NonAirBlockConsumer {
        void accept(BlockPos position, BlockState state);
    }

    interface DimensionsSink {
        void set(int sizeX, int sizeY, int sizeZ);
    }
    static class CacheFile {
        @Nullable
        static MeshData read(Path path, AtomicBoolean cancelled) {
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

                    int remainingVertices = vertexCount;
                    while (remainingVertices > 0) {
                        int batchVertices = layer.isTranslucent()
                                ? remainingVertices
                                : Math.min(remainingVertices, STATIC_BATCH_TARGET_VERTICES);
                        byte[] quantizedVertices = new byte[batchVertices * QUANTIZED_VERTEX_BYTES];
                        readFullyCancellable(input, quantizedVertices, cancelled);
                        layers.add(new LayerMesh(layer, quantizedVertices));
                        remainingVertices -= batchVertices;
                    }
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

        static void readFullyCancellable(DataInputStream input, byte[] bytes, AtomicBoolean cancelled) throws IOException {
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

        static void writeAtomically(
                Path tmpPath,
                Path finalPath,
                MeshData data,
                List<LayerMesh> layers,
                AtomicBoolean cancelled,
                ProgressSink progressSink
        ) throws IOException {
            deleteTmpQuietly(tmpPath);
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath))))) {
                progressSink.set(PROGRESS_CACHE_WRITE);
                output.writeInt(CACHE_MAGIC);
                output.writeInt(CACHE_FORMAT_VERSION);
                output.writeUTF(CACHE_RENDER_MARKER);
                output.writeInt(data.sizeX());
                output.writeInt(data.sizeY());
                output.writeInt(data.sizeZ());
                List<LayerKey> storedLayers = new ArrayList<>();
                for (LayerKey layer : LayerKey.DRAW_ORDER) {
                    if (layers.stream().anyMatch(mesh -> mesh.layer() == layer && mesh.vertexCount() > 0)) {
                        storedLayers.add(layer);
                    }
                }
                output.writeInt(storedLayers.size());

                long totalStaticBytes = 0L;
                for (LayerMesh layer : layers) {
                    totalStaticBytes += layer.quantizedVertices().length;
                }

                long staticBytesWritten = 0L;
                for (LayerKey storedLayer : storedLayers) {
                    int layerVertexCount = 0;
                    for (LayerMesh mesh : layers) {
                        if (mesh.layer() == storedLayer) {
                            layerVertexCount += mesh.vertexCount();
                        }
                    }
                    output.writeInt(storedLayer.id);
                    output.writeInt(layerVertexCount);
                    for (LayerMesh mesh : layers) {
                        if (mesh.layer() != storedLayer) {
                            continue;
                        }
                        byte[] quantized = mesh.quantizedVertices();
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

        static boolean isCancelled(AtomicBoolean cancelled) {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }

        static float progress(float start, float end, long completed, long total) {
            if (total <= 0L) {
                return end;
            }
            return start + (end - start) * Math.min(1.0F, completed / (float) total);
        }

        // ---- 静态顶点解码与 octahedral 8-bit 法线编码 ----

        static short encodeOverlay(int overlay) {
            return (short) ((overlay & 0xFF) | (((overlay >>> 16) & 0xFF) << 8));
        }

        static int decodeOverlay(short packed) {
            return (packed & 0xFF) | (((packed >>> 8) & 0xFF) << 16);
        }

        static int readInt(byte[] bytes, int offset) {
            return (bytes[offset] & 0xFF) << 24
                    | (bytes[offset + 1] & 0xFF) << 16
                    | (bytes[offset + 2] & 0xFF) << 8
                    | (bytes[offset + 3] & 0xFF);
        }

        static short readShort(byte[] bytes, int offset) {
            return (short) ((bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF));
        }

        // 法线 (float x3) -> 2 字节，八面体编码 8-bit/分量。方向光照肉眼不可察觉差异。
        static short encodeNormal(float nx, float ny, float nz) {
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
        static void decodeNormal(short packed, float[] out) {
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

        static void decodeQuantizedToBuilder(byte[] bytes, BufferBuilder builder) {
            QuickLitematicaPreviewAccess.decodeQuantizedToBuilder(bytes, builder);
        }
    }
    public static void registerSpecialRenderer() { QuickLitematicaPreviewAccess.registerSpecialRenderer(); }

    static final class MeshBuilder {
        static final Set<String> NON_VISUAL_INVENTORY_IDS = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
                "minecraft:shulker_box", "minecraft:hopper", "minecraft:dispenser",
                "minecraft:dropper", "minecraft:crafter", "minecraft:furnace",
                "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand");

        @Nullable
        static LitematicaSchematic readSchematic(
                Path path,
                AtomicBoolean cancelled,
                boolean required
        ) {
            if (!Files.isRegularFile(path)) {
                return null;
            }

            Path directory = path.getParent();
            Path fileName = path.getFileName();
            if (directory == null || fileName == null) {
                return null;
            }

            LitematicaSchematic schematic = null;
            Path fastPath = null;
            try {
                fastPath = prepareFastSchematic(path, cancelled);
                if (fastPath != null) {
                    Path fastDir = fastPath.getParent();
                    Path fastFile = fastPath.getFileName();
                    if (fastDir != null && fastFile != null) {
                        throwIfCancelled(cancelled);
                        schematic = LitematicaSchematic.createFromFile(
                                fastDir,
                                fastFile.toString(),
                                FileType.LITEMATICA_SCHEMATIC
                        );
                    }
                }
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (Throwable t) {
                LOGGER.debug("QuickCraft fast schematic preparation skipped: {}", t.getMessage());
            } finally {
                if (fastPath != null) {
                    deleteQuietly(fastPath);
                }
            }

            if (schematic == null) {
                throwIfCancelled(cancelled);
                schematic = LitematicaSchematic.createFromFile(
                        directory,
                        fileName.toString(),
                        FileType.LITEMATICA_SCHEMATIC
                );
            }

            throwIfCancelled(cancelled);
            if (schematic == null && required) {
                throw new IllegalStateException("Cannot read litematic file");
            }
            return schematic;
        }

        @Nullable
        static Path prepareFastSchematic(Path sourcePath, AtomicBoolean cancelled) {
            throwIfCancelled(cancelled);
            String name = sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : "";
            if (!name.endsWith(".litematic")) {
                return null;
            }
            Path cacheDir = cacheDirectory();
            if (cacheDir == null) {
                return null;
            }

            Path fastPath = null;
            try (InputStream is = Files.newInputStream(sourcePath);
                 BufferedInputStream bis = new BufferedInputStream(is);
                 GZIPInputStream gis = new GZIPInputStream(bis);
                 DataInputStream dis = new DataInputStream(gis)) {
                NbtCompound root = NbtIo.readCompound(dis, NbtSizeTracker.of(256L * 1024L * 1024L));
                throwIfCancelled(cancelled);
                if (root == null || !containsCompound(root, "Regions")) {
                    return null;
                }

                boolean modified = false;
                NbtCompound regions = readCompound(root, "Regions");
                for (String key : regions.getKeys()) {
                    if (!containsCompound(regions, key)) {
                        continue;
                    }
                    NbtCompound reg = readCompound(regions, key);
                    if (containsList(reg, "TileEntities")) {
                        NbtList teList = readCompoundList(reg, "TileEntities");
                        for (int i = 0; i < teList.size(); i++) {
                            NbtCompound te = readListCompound(teList, i);
                            // 火堆物品和实体装备参与渲染，不能作为普通库存删减。
                            if (!NON_VISUAL_INVENTORY_IDS.contains(readString(te, "id"))) {
                                continue;
                            }
                            if (te.contains("Items")) {
                                te.remove("Items");
                                modified = true;
                            }
                            if (te.contains("Inventory")) {
                                te.remove("Inventory");
                                modified = true;
                            }
                        }
                    }

                }

                if (!modified) {
                    return null;
                }

                throwIfCancelled(cancelled);
                fastPath = cacheDir.resolve("fast-" + Long.toUnsignedString(System.nanoTime()) + ".fast.tmp.litematic");
                try (OutputStream os = Files.newOutputStream(fastPath);
                     BufferedOutputStream bos = new BufferedOutputStream(os);
                     GZIPOutputStream gos = new GZIPOutputStream(bos);
                     DataOutputStream dos = new DataOutputStream(gos)) {
                    NbtIo.writeCompound(root, dos);
                }
                return fastPath;
            } catch (CancellationException cancellation) {
                if (fastPath != null) {
                    deleteQuietly(fastPath);
                }
                throw cancellation;
            } catch (Throwable t) {
                if (fastPath != null) {
                    deleteQuietly(fastPath);
                }
                return null;
            }
        }

        static MeshData build(
                LitematicaSchematic schematic,
                AtomicBoolean cancelled,
                ProgressSink progressSink,
                DimensionsSink dimensionsSink,
                Consumer<List<LayerMesh>> batchSink
        ) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                throw new IllegalStateException("Litematica preview needs a loaded client world");
            }
            progressSink.set(PROGRESS_MESHING_START);

            Bounds bounds = Bounds.from(schematic.getAreas().values());
            dimensionsSink.set(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            MeshCollector collector = new MeshCollector();
            final List<LayerMesh> layers = new ArrayList<>();
            Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
            List<BlockEntityData> blockEntities = new ArrayList<>();
            List<EntityData> entities = new ArrayList<>();
            Map<BlockState, Boolean> blockEntityRendererCache = new HashMap<>();
            long total = Math.max(1L, totalVolume(schematic.getAreas().values()));

            BlockRenderManager blockRenderManager = client.getBlockRenderManager();
            MatrixStack matrices = new MatrixStack();
            Random random = Random.createLocal();
            MeshRenderer renderer = new MeshRenderer(collector, blockRenderManager, matrices, random, client.world);
            long scannedVolume = 0L;

            for (String regionName : schematic.getAreas().keySet()) {
                throwIfCancelled(cancelled);
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                Box area = schematic.getAreas().get(regionName);
                if (container == null || area == null) {
                    continue;
                }

                RegionBlockView view = new RegionBlockView(container, area);
                RegionBounds regionBounds = RegionBounds.from(area);
                recordEntities(blockStates, entities, view, schematic, regionName, area, bounds, cancelled);

                long regionVolume = regionBounds.volume();
                long regionStart = scannedVolume;
                visitNonAirBlocks(container, regionBounds, cancelled, wordProgress -> progressSink.set(
                        PROGRESS_MESHING_START + (PROGRESS_MESHING_END - PROGRESS_MESHING_START)
                                * ((regionStart + wordProgress * regionVolume) / (float) Math.max(1L, total))
                ), (pos, state) -> {
                    BlockPos renderPos = pos.subtract(bounds.min());
                    recordBlockEntity(blockStates, blockEntities, blockEntityRendererCache, view, state, schematic, regionName, pos, renderPos, bounds);
                    renderer.renderFluid(view, state, pos, renderPos);
                    renderer.renderBlock(view, state, pos, renderPos);
                    if (collector.shouldPublishOpaqueBatch()) {
                        List<LayerMesh> batch = collector.drainOpaqueMeshes();
                        layers.addAll(batch);
                        batchSink.accept(batch);
                    }
                });
                scannedVolume += regionVolume;
            }

            progressSink.set(PROGRESS_MESHING_END);
            List<LayerMesh> finalBatch = collector.drainAllMeshes();
            layers.addAll(finalBatch);
            batchSink.accept(finalBatch);
            List<LayerMesh> completeLayers = List.copyOf(layers);
            int vertices = vertexCount(completeLayers);
            if (vertices > MAX_UPLOAD_VERTICES
                    || blockStates.size() > MAX_DYNAMIC_BLOCK_STATES
                    || blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES
                    || entities.size() > MAX_DYNAMIC_ENTITIES) {
                throw new PreviewTooLargeException();
            }
            return new MeshData(completeLayers, new ArrayList<>(blockStates.values()), blockEntities, entities, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        }

        static int vertexCount(List<LayerMesh> layers) {
            int count = 0;
            for (LayerMesh layer : layers) {
                count += layer.vertexCount();
            }
            return count;
        }

        static void visitNonAirBlocks(
                LitematicaBlockStateContainer container,
                RegionBounds bounds,
                AtomicBoolean cancelled,
                ProgressSink progressSink,
                NonAirBlockConsumer consumer
        ) {
            var size = container.getSize();
            int sizeX = size.getX();
            int sizeY = size.getY();
            int sizeZ = size.getZ();
            if (sizeX != bounds.sizeX() || sizeY != bounds.sizeY() || sizeZ != bounds.sizeZ()) {
                for (BlockPos pos : BlockPos.iterate(bounds.min(), bounds.max())) {
                    throwIfCancelled(cancelled);
                    int x = pos.getX() - bounds.min().getX();
                    int y = pos.getY() - bounds.min().getY();
                    int z = pos.getZ() - bounds.min().getZ();
                    if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                        continue;
                    }
                    BlockState state = container.get(x, y, z);
                    if (!state.isAir()) {
                        consumer.accept(pos, state);
                    }
                }
                progressSink.set(1.0F);
                return;
            }

            long[] packed = container.getArray().getBackingLongArray();
            int paletteSize = Math.max(1, container.getPalette().getPaletteSize());
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
            BlockState zeroState = container.getPalette().getBlockState(0);
            boolean zeroMeansAir = zeroState == null || zeroState.isAir();
            long rows = (long) sizeY * sizeZ;
            long rowNumber = 0L;
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    if ((rowNumber & 0x7F) == 0L) {
                        throwIfCancelled(cancelled);
                        progressSink.set(rowNumber / (float) Math.max(1L, rows));
                    }

                    long rowStart = ((long) y * sizeZ + z) * sizeX;
                    long rowEnd = rowStart + sizeX;
                    int firstWord = (int) ((rowStart * bits) >>> 6);
                    int lastWord = (int) (((rowEnd - 1L) * bits) >>> 6);
                    long lastProcessedIndex = rowStart - 1L;
                    for (int wordIndex = firstWord; wordIndex <= lastWord; wordIndex++) {
                        if (zeroMeansAir && packed[wordIndex] == 0L) {
                            continue;
                        }

                        long firstIndex = Math.max(rowStart, (wordIndex * 64L) / bits);
                        long finalIndex = Math.min(rowEnd - 1L, (((wordIndex + 1L) * 64L) - 1L) / bits);
                        for (long index = firstIndex; index <= finalIndex; index++) {
                            if (index <= lastProcessedIndex) {
                                continue;
                            }
                            lastProcessedIndex = index;

                            int stateId = container.getArray().getAt(index);
                            BlockState state = container.getPalette().getBlockState(stateId);
                            if (state == null || state.isAir()) {
                                continue;
                            }

                            int x = (int) (index - rowStart);
                            consumer.accept(new BlockPos(
                                    bounds.min().getX() + x,
                                    bounds.min().getY() + y,
                                    bounds.min().getZ() + z
                            ), state);
                        }
                    }
                    rowNumber++;
                }
            }
            progressSink.set(1.0F);
        }

        static long totalVolume(Collection<Box> boxes) {
            long total = 0L;
            for (Box box : boxes) {
                RegionBounds bounds = RegionBounds.from(box);
                total += bounds.volume();
            }
            return total;
        }

        static void recordBlockEntity(
                Map<BlockPos, BlockStateData> blockStates,
                List<BlockEntityData> blockEntities,
                Map<BlockState, Boolean> blockEntityRendererCache,
                RegionBlockView view,
                BlockState state,
                LitematicaSchematic schematic,
                String regionName,
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

            NbtCompound nbt = previewBlockEntityNbt(schematic, regionName, schematicPos.subtract(view.bounds.min()));
            NbtCompound entityNbt = sanitizeBlockEntityNbt(nbt);
            entityNbt.putInt("x", renderPos.getX());
            entityNbt.putInt("y", renderPos.getY());
            entityNbt.putInt("z", renderPos.getZ());
            blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state), entityNbt));
            if (blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES) {
                throw new PreviewTooLargeException();
            }
        }

        static boolean hasPreviewBlockEntityRenderer(BlockEntityProvider provider, BlockState state, BlockPos renderPos) {
            BlockEntity blockEntity = provider.createBlockEntity(renderPos, state);
            if (blockEntity == null) {
                return false;
            }

            setPreviewBlockEntityState(blockEntity, state);
            return MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(blockEntity) != null;
        }

        static NbtCompound sanitizeBlockEntityNbt(NbtCompound nbt) {
            NbtCompound sanitized = nbt.copy();
            // 火堆食物等可见物品必须保留，只有已知普通容器可以删减库存。
            if (NON_VISUAL_INVENTORY_IDS.contains(readString(sanitized, "id"))) {
                sanitized.remove("Items");
            }
            return sanitized;
        }

        static void recordDynamicBlockState(Map<BlockPos, BlockStateData> blockStates, BlockState state, BlockPos renderPos) {
            if (blockStates.size() >= MAX_DYNAMIC_BLOCK_STATES && !blockStates.containsKey(renderPos)) {
                throw new PreviewTooLargeException();
            }

            blockStates.put(renderPos.toImmutable(), new BlockStateData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtHelper.fromBlockState(state)));
        }

        static void recordEntities(
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

            BlockPos areaOrigin = area.getPos1();
            BlockPos regionOrigin = areaOrigin != null ? areaOrigin : BlockPos.ORIGIN;
            for (LitematicaSchematic.EntityInfo info : regionEntities) {
                throwIfCancelled(cancelled);
                double x = entityPos(info).x + regionOrigin.getX() - bounds.min().getX();
                double y = entityPos(info).y + regionOrigin.getY() - bounds.min().getY();
                double z = entityPos(info).z + regionOrigin.getZ() - bounds.min().getZ();
                NbtCompound nbt = entityNbt(info);
                entities.add(new EntityData(x, y, z, copyEntityNbtAt(nbt, x, y, z)));
                recordEntityNearbyBlockStates(blockStates, view, bounds, x, y, z);
                if (entities.size() > MAX_DYNAMIC_ENTITIES) {
                    throw new PreviewTooLargeException();
                }
            }
        }

        static void recordEntityNearbyBlockStates(Map<BlockPos, BlockStateData> blockStates, RegionBlockView view, Bounds bounds, double x, double y, double z) {
            BlockPos center = BlockPos.ofFloored(x, y, z);
            // 矿车渲染会读取所在位置的铁轨，假世界需要保留中心方块。
            BlockState centerState = view.getBlockState(center.add(bounds.min()));
            if (!centerState.isAir()) {
                recordDynamicBlockState(blockStates, centerState, center);
            }
            // 展示框/画等挂载实体会查询附着方块（facing 反方向）；假世界缺邻居会被原版判成 invalid position。
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

        static NbtCompound copyEntityNbtAt(NbtCompound source, double x, double y, double z) {
            NbtCompound copy = source.copy();
            NbtList pos = new NbtList();
            pos.add(NbtDouble.of(x));
            pos.add(NbtDouble.of(y));
            pos.add(NbtDouble.of(z));
            copy.put("Pos", pos);
            return copy;
        }

        static void throwIfCancelled(AtomicBoolean cancelled) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }
    }
}
