package com.yiyihehe.quickcraft.litematica;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;

import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.Collection;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.block.BlockEntityProvider;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.FakeLightingProvider;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewShaderAccess.isShaderPackActive;

/** 预览任务、缓存、交互和导出编排；原生渲染由版本后端提供。 */
public final class QuickLitematicaPreview3D {

    static final QuickLitematicaPreviewLog LOGGER = QuickLitematicaPreviewLog.LOGGER;
    static final AtomicBoolean SHADER_API_ERROR_LOGGED = new AtomicBoolean();
    static final AtomicBoolean SHADER_DISABLE_ERROR_LOGGED = new AtomicBoolean();
    // Minecraft 1.21.x 的预览方块实体没有非弃用的公开状态更新 API。
    @SuppressWarnings("deprecation")
    static void setPreviewBlockEntityState(BlockEntity blockEntity, BlockState state) {
        blockEntity.setCachedState(state);
    }

    static String inferPreviewBlockEntityId(String blockId, int sourceDataVersion) {
        // 704 之前使用旧式 TileEntity 名称；无法可靠恢复时保留原数据给上游处理。
        if (sourceDataVersion < 704 || !blockId.startsWith("minecraft:")) return "";
        try {
            var block = Registries.BLOCK.get(Identifier.of(blockId));
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return "";
            BlockState state = block.getDefaultState();
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) return "";
            BlockEntity blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, state);
            if (blockEntity == null || !blockEntity.getClass().getName().startsWith("net.minecraft.")) return "";
            var typeId = Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType());
            if (typeId == null || !typeId.toString().startsWith("minecraft:")) return "";
            String id = typeId.toString();
            // trapped_chest 在 schema 1451 才独立，此前必须按 chest 进入 DFU。
            return sourceDataVersion < 1451 && id.equals("minecraft:trapped_chest") ? "minecraft:chest" : id;
        } catch (Throwable failure) {
            LOGGER.warn("缺失方块实体 id 恢复失败：方块={}，源数据版本={}，保留原数据", blockId, sourceDataVersion, failure);
            return "";
        }
    }

    static boolean isPreviewInventoryHidden(String blockId) {
        if (!QuickLitematicaPreviewSchematicFiles.isNonVisualInventory(blockId) && !blockId.equals("minecraft:ender_chest")) return false;
        try {
            var block = Registries.BLOCK.get(Identifier.of(blockId));
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return false;
            BlockState state = block.getDefaultState();
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) return false;
            BlockEntity blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, state);
            if (blockEntity == null) return false;
            var renderer = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(blockEntity);
            return renderer == null || renderer.getClass().getName().startsWith("net.minecraft.");
        } catch (Throwable failure) {
            LOGGER.warn("预览库存可见性核验失败：方块={}，保留库存及 DFU", blockId, failure);
            return false;
        }
    }

    static boolean hasPreviewRendererForBlockEntity(String id) {
        try {
            var blockKey = Identifier.of(id);
            if (!Registries.BLOCK.containsId(blockKey)) return true;
            var block = Registries.BLOCK.get(blockKey);
            // 模组替换原版方块类时，不假定其所有状态都使用同一种方块实体。
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return true;
            BlockState state = block.getDefaultState();
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) return false;
            // 与网格构建使用相同的类型/渲染器检查，兼容为原版方块注册渲染器的模组。
            return QuickLitematicaPreviewMeshBuilder.hasPreviewBlockEntityRenderer(provider, state, BlockPos.ORIGIN);
        } catch (Throwable failure) {
            LOGGER.warn("方块实体预览渲染器核验失败：类型={}，保留原数据及 DFU", id, failure);
            return true;
        }
    }

    static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 客户端线程访问；也覆盖不进浏览器映射的选区预览，不延长屏幕的生命周期。
    private static final java.util.Set<Manager> LIVE_MANAGERS = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderManager 多线程竞争。
    private static final AtomicReference<Preview> PENDING_PREVIEW_BUILD = new AtomicReference<>();
    static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    static final int EXPAND_BUTTON_SIZE = 16;
    static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    // 1600 万顶点约对应 671 MiB 静态 GPU 顶点数据；只放宽静态网格，动态内容上限仍保持原值。
    static final int MAX_UPLOAD_VERTICES = QuickLitematicaPreviewVertexBudget.DEFAULT_VERTEX_LIMIT;
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
    // 静态层按完整方块切批，限制构建暂存和单帧上传；透明层在首次显示前统一排序。
    static final int STATIC_BATCH_TARGET_VERTICES = 250_000;
    static final float PROGRESS_START = 0.02F;
    static final float PROGRESS_MESHING_START = 0.10F;
    static final float PROGRESS_MESHING_END = 0.80F;
    static final float PROGRESS_CACHE_WRITE = 0.82F;
    static final float PROGRESS_STATIC_CACHE_END = 0.93F;
    static final float PROGRESS_BLOCK_STATES_CACHE_END = 0.95F;
    static final float PROGRESS_BLOCK_ENTITIES_CACHE_END = 0.99F;
    // 当前页面只按此间隔检查文件大小/时间戳；完整 SHA-256 始终在后台且仅于重新核验时计算。
    static final long SOURCE_CHECK_INTERVAL_MILLIS = 1_000L;

    public static Manager init(fi.dy.masa.litematica.gui.GuiSchematicBrowserBase gui, Runnable previewMetadataRefresh) {
        Manager existing = MANAGERS.get(gui);
        if (existing != null) {
            // 全屏返回和窗口尺寸变化会重新 initGui，同一页面仍拥有原来的预览任务。
            if (existing.current != null) existing.current.trace.event("页面重新初始化：复用原管理器及预览任务，当前状态={}", existing.current.state);
            return existing;
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
        manager.generatedName = displayName;
        manager.generatedSupplier = schematicSupplier;
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

    static void resourcesReloaded() {
        for (Manager manager : LIVE_MANAGERS) {
            if (manager.current != null) manager.current.close();
        }
    }

    public static boolean prepare3DPreview() {
        if (!isShaderPackActive()) return true;
        if (!QuickCraftConfigs.shouldAutoDisableShadersFor3DPreview()
                || !QuickLitematicaPreviewCompat.tryDisableShaders()) return false;
        boolean disabled = !isShaderPackActive();
        if (disabled) InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.shader_auto_disabled");
        return disabled;
    }

    public static final class Manager implements AutoCloseable {
        @Nullable
        Preview current;
        private String generatedName;
        private Supplier<LitematicaSchematic> generatedSupplier;
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
            LIVE_MANAGERS.add(this);
        }

        void render(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, DrawContext drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entryPath(entry).toAbsolutePath().normalize();
            if (!path.equals(this.currentPath)) {
                this.switchTo(path, entry);
            }
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
            if (!path.equals(this.currentPath) || (this.current != null && this.current.isCancelled())) {
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

        void fullscreenClosed() {
            if (this.current != null) this.current.trace.event("退出全屏：任务保留，当前状态={}", this.current.state);
        }

        void renderFullscreen(DrawContext drawContext, int x, int y, int size) {
            this.renderCurrent(drawContext, x, y, size, false);
        }

        void renderCurrent(DrawContext drawContext, int x, int y, int size, boolean showExpandButton) {
            if (!is3DPreviewAvailable()) {
                return;
            }

            this.checkCurrentSource();
            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = showExpandButton;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            drawOutlinedBox(drawContext, this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
            if (this.current != null) {
                this.current.render(drawContext, this.viewX, this.viewY, this.viewSize, this.drag);
                if (this.canForceRender()) this.drawForceRenderButton(drawContext);
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
                if (this.current != null) this.current.trace.event("进入全屏：复用任务，当前状态={}", this.current.state);
                MinecraftClient.getInstance().setScreen(new QuickLitematicaPreview3DScreen(this.owner, entry.getName(), this));
                return true;
            }

            if (mouseButton == 0 && this.canForceRender() && this.isForceRenderButtonHovered(mouseX, mouseY)) {
                this.current.trace.event("用户强制渲染：解除当前投影静态顶点保护，可能耗尽内存/显存");
                this.current.close();
                this.current = Preview.create(this.currentEntry, true);
                this.drag.stop();
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
                Util.getIoWorkerExecutor().execute(preview.trace.wrap("原理图预览图更新", () -> {
                    try {
                        int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(selected);
                        QuickLitematicaPreviewImageWriter.writePreview(target, pixels);
                        QuickLitematicaPreviewLog.file("预览图更新后的源文件", target);
                        preview.refreshCacheSourceHash();
                        this.finishPreviewWrite(preview, true, callback, Text.translatable(
                                "quickcraft.litematica.preview_3d.preview_write_success",
                                target.getFileName().toString()
                        ));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to set the Litematica preview image from {}", selected, throwable);
                        this.failPreviewWrite(callback, throwable);
                    }
                }));
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
                LOGGER.warn("预览局部失败：入口=restoreWindowsGameWindow，继续原有回退", ignored);
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
                Util.getIoWorkerExecutor().execute(preview.trace.wrap("原理图预览图更新", () -> {
                    try {
                        QuickLitematicaPreviewImageWriter.removePreview(target);
                        QuickLitematicaPreviewLog.file("预览图更新后的源文件", target);
                        preview.refreshCacheSourceHash();
                        this.finishPreviewWrite(preview, false, callback, Text.translatable(
                                "quickcraft.litematica.preview_3d.preview_remove_success",
                                target.getFileName().toString()
                        ));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to remove the Litematica preview image from {}", target, throwable);
                        this.failPreviewWrite(callback, throwable);
                    }
                }));
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
            LIVE_MANAGERS.remove(this);
            this.generatedSupplier = null;
            this.generatedName = null;
            this.cancelPendingPreviewImagePicker();
            this.clearCurrent();
        }

        boolean canHandleMouse(double mouseX, double mouseY) {
            return (this.current != null || this.currentEntry != null)
                    && is3DPreviewAvailable()
                    && this.drag.inViewport(mouseX, mouseY);
        }

        private void checkCurrentSource() {
            if (QuickLitematicaPreviewResources.reloading()) return;
            if (this.generatedSupplier != null) {
                if (this.current != null && this.current.isCancelled()) {
                    this.current = Preview.createGenerated(this.generatedName, this.generatedSupplier);
                }
                return;
            }
            if (this.current == null || this.currentPath == null || this.currentEntry == null) return;
            long now = System.currentTimeMillis();
            boolean checkDue = now >= this.nextSourceCheckMillis;
            // 只有执行核验才推进截止时间，避免连续渲染把检查永久推迟。
            if (checkDue) this.nextSourceCheckMillis = now + SOURCE_CHECK_INTERVAL_MILLIS;
            if (this.current.isCancelled() || (checkDue && this.current.sourceStampChanged())) {
                boolean hasEmbeddedPreview = this.hasEmbeddedPreviewImage;
                this.switchTo(this.currentPath, this.currentEntry);
                this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
            }
        }

        void switchTo(Path path, DirectoryEntry entry) {
            this.clearCurrent();
            this.currentPath = path;
            this.currentEntry = entry;
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

        private boolean canForceRender() {
            return this.currentEntry != null && this.current != null && !this.current.forceRender
                    && this.current.state == State.TOO_LARGE;
        }

        private int forceRenderButtonWidth() {
            return Math.min(this.viewSize - 12, MinecraftClient.getInstance().textRenderer.getWidth(
                    StringUtils.translate("quickcraft.litematica.preview_3d.force_render")) + 16);
        }

        private boolean isForceRenderButtonHovered(double mouseX, double mouseY) {
            int width = this.forceRenderButtonWidth();
            int x = this.viewX + (this.viewSize - width) / 2;
            int y = this.viewY + this.viewSize / 2 + 14;
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 18;
        }

        private void drawForceRenderButton(DrawContext context) {
            MinecraftClient client = MinecraftClient.getInstance();
            double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
            int width = this.forceRenderButtonWidth();
            int x = this.viewX + (this.viewSize - width) / 2;
            int y = this.viewY + this.viewSize / 2 + 14;
            boolean hovered = this.isForceRenderButtonHovered(mouseX, mouseY);
            drawOutlinedBox(context, x, y, width, 18, hovered ? 0xE0603020 : 0xD0302010, 0xFFCC8855);
            String label = StringUtils.translate("quickcraft.litematica.preview_3d.force_render");
            context.drawCenteredTextWithShadow(client.textRenderer, label, this.viewX + this.viewSize / 2, y + 5, 0xFFFFCC77);
            if (hovered) {
                context.drawTooltip(client.textRenderer, Text.translatable("quickcraft.litematica.preview_3d.force_render_warning"), (int) mouseX, (int) mouseY);
            }
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
    static void runBuildTask(Runnable work, Consumer<Throwable> failureSink) {
        // submit 会把 Error 收进无人读取的 Future，必须在任务内交接失败与资源清理。
        try {
            work.run();
        } catch (RuntimeException | Error failure) {
            failureSink.accept(failure);
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
        final QuickLitematicaPreviewLog.Trace trace;

        boolean forceRender;
        volatile boolean vertexLimitExceeded;
        final AtomicBoolean cancelled = new AtomicBoolean();

        volatile MeshData meshData;

        volatile float progress;

        volatile State state = State.LOADING;

        @Nullable
        volatile Future<?> future;

        private final QuickLitematicaPreviewUploadQueue staticUploads = new QuickLitematicaPreviewUploadQueue();
        private final QuickLitematicaPreviewMeshStore staticMeshStore = new QuickLitematicaPreviewMeshStore();
        @Nullable private volatile QuickLitematicaPreviewCache.MeshLease borrowedMesh;

        @Nullable
        volatile PreviewDimensions dimensions;

        boolean dynamicBuffersReady;

        float dynamicProgress;

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
            this.trace = new QuickLitematicaPreviewLog.Trace(sourcePath, this.startedAtNanos);
            this.trace.event("CPU 顶点策略：cpu-spill-v2，小网格驻留阈值={} B，待上传队列预算=64 MiB，大网格缓存副本暂存磁盘，进程退出自动删除；加载预览重画上限=4 Hz", QuickLitematicaPreviewMeshStore.MEMORY_BYTES);
            this.trace.event("预览正确性策略：resource-content-v1 / translucent-view-v3 / beacon-v1，透明材质合并，批内索引与跨批绘制顺序按视角重排");
            if (QuickLitematicaPreviewLog.enabled()) this.trace.event("渲染环境：后端={}，资源包={}，源类型={}",
                    this.getClass().getName(), MinecraftClient.getInstance().getResourcePackManager().getEnabledProfiles().stream().map(profile -> profile.getId()).toList(), cacheSlot.isBlank() ? "游戏内选区" : "文件");
            this.captureSourceStamp();
            this.trace.event("任务创建：源={}，缓存={}，临时文件={}，缓存槽={}，资源签名={}，源大小={} B，修改时间={}，默认顶点/动态状态/方块实体/实体上限={}/{}/{}/{}",
                    sourcePath, cachePath, tmpPath, cacheSlot, resourcePackSignature, this.sourceSize, this.sourceModifiedMillis,
                    MAX_UPLOAD_VERTICES, MAX_DYNAMIC_BLOCK_STATES, MAX_DYNAMIC_BLOCK_ENTITIES, MAX_DYNAMIC_ENTITIES);
        }

        static Preview create(DirectoryEntry entry) {
            return create(entry, false);
        }

        static Preview create(DirectoryEntry entry, boolean forceRender) {
            Path sourcePath = entryPath(entry).toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new NativePreview(
                    sourcePath,
                    cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot,
                    currentCacheSignature()
            );
            preview.forceRender = forceRender;
            preview.trace.event("预览预算：强制渲染={}，静态顶点上限={}，默认上限={}，强制时忽略动态数量/缓冲/NBT分配预算，仅当前任务生效",
                    forceRender, forceRender ? Integer.MAX_VALUE : MAX_UPLOAD_VERTICES, MAX_UPLOAD_VERTICES);
            preview.progress = PROGRESS_START;
            preview.startBuild("预览后台读取/构建",
                    () -> QuickLitematicaPreviewVertexBudget.run(forceRender, preview::loadOrBuild));
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
            preview.startBuild("选区捕获/构建", () -> preview.loadGenerated(schematicSupplier));
            return preview;
        }

        private void startBuild(String phase, Runnable work) {
            Preview previous = PENDING_PREVIEW_BUILD.getAndSet(this);
            if (previous != null && previous != this) previous.cancelPendingBuild();
            this.future = PREVIEW_EXECUTOR.submit(this.trace.wrap(phase, () -> {
                try (var scope = QuickLitematicaPreviewCancellation.bind(this.cancelled)) {
                    runBuildTask(work, this::failBuild);
                } finally {
                    PENDING_PREVIEW_BUILD.compareAndSet(this, null);
                    this.trace.summary("后台读取/构建任务结束：" + this.state);
                }
            }));
        }

        synchronized void failBuild(Throwable failure) {
            if (this.state == State.FAILED) return;
            this.state = this.cancelled.get() ? State.CANCELLED : State.FAILED;
            this.progress = 1.0F;
            MeshData data = this.meshData;
            this.meshData = null;
            if (data != null) {
                data.releaseStaticVertices();
                data.closeDynamic();
            }
            // 先归还半成品，再记录异常；内存不足时仍保留已有的有效磁盘缓存。
            this.discardPartialStatic();
            Future<?> task = this.future;
            if (task != null) task.cancel(true);
            Runtime runtime = Runtime.getRuntime();
            LOGGER.error("3D 预览任务失败：源={}，状态={}，强制渲染={}，已用堆={} B，最大堆={} B，半成品已撤销，磁盘缓存保留",
                    this.sourcePath, this.state, this.forceRender, runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), failure);
            this.trace.event("预览状态改变：{}，失败类型={}", this.state, failure.getClass().getName());
        }

        private synchronized void rejectBuild(boolean vertexLimit) {
            if (this.state == State.FAILED) return;
            this.vertexLimitExceeded = vertexLimit;
            this.state = State.TOO_LARGE;
            this.progress = 1.0F;
            this.discardPartialStatic();
            this.trace.event("预览状态改变：TOO_LARGE，顶点保护触发={}，强制渲染={}", this.vertexLimitExceeded, this.forceRender);
        }

        private synchronized void cancelBuild() {
            if (this.state != State.FAILED) {
                this.state = State.CANCELLED;
                this.trace.event("预览状态改变：CANCELLED");
            }
            this.discardPartialStatic();
        }

        private synchronized void cancelPendingBuild() {
            // 与 READY 发布共用锁；只取消尚未完成的构建，缓存写入仍由独立任务拥有。
            if (this.state == State.LOADING || this.state == State.BUILDING) {
                this.trace.event("新预览请求接管构建队列：取消未完成旧任务，等待当前 NBT/DFU 边界退出");
                this.close();
            }
        }

        void loadGenerated(Supplier<LitematicaSchematic> schematicSupplier) {
            try {
                this.state = State.BUILDING;
                this.trace.event("预览状态改变：BUILDING");
                LitematicaSchematic schematic = schematicSupplier.get();
                this.throwIfCancelled();
                if (schematic == null) {
                    throw new IllegalStateException("Cannot capture Litematica selection");
                }
                MeshData built = QuickLitematicaPreviewMeshBuilder.build(
                        schematic,
                        this.cancelled,
                        value -> this.progress = value,
                        this::initializeDimensions,
                        this::publishStaticLayers,
                        this.staticMeshStore
                );
                this.throwIfCancelled();
                if (!built.withinBudget()) {
                    built.closeDynamic();
                    this.rejectBuild(built.vertexCount() > QuickLitematicaPreviewVertexBudget.vertexLimit());
                    return;
                }

                synchronized (this) {
                    this.throwIfCancelled();
                    this.meshData = built;
                }
                this.publishStaticLayers(built.layers().stream().filter(LayerMesh::needsTranslucentSorting).toList());

                synchronized (this) {
                    this.throwIfCancelled();
                    this.trace.milestone("CPU 模型构建完成：顶点={}，层批次={}，尺寸={}x{}x{}", built.vertexCount(), built.layers().size(), built.sizeX(), built.sizeY(), built.sizeZ());
                    this.meshData = built;
                    this.progress = 1.0F;
                    this.state = State.READY;
                    this.trace.event("预览状态改变：READY");
                }
            } catch (CancellationException ignored) {
                this.cancelBuild();
            } catch (PreviewTooLargeException failure) {
                this.rejectBuild(failure.vertexLimit);
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.cancelBuild();
                } else if (isPreviewTooLarge(e)) {
                    this.rejectBuild(isPreviewVertexLimit(e));
                } else {
                    this.failBuild(e);
                }
            }
        }

        void loadOrBuild() {
            try {
                this.progress = PROGRESS_START;
                this.trace.event("后台任务开始：排队/调度等待={} us", (System.nanoTime() - this.startedAtNanos) / 1_000L);
                long hashStart = QuickLitematicaPreviewLog.startTimer();
                String sourceHash = hashFileCancellable(this.sourcePath, this.cancelled);
                this.trace.event("源文件核验完成：SHA256={}，文件={}，大小={} B，耗时={} us", sourceHash, this.sourcePath,
                        this.sourceSize, QuickLitematicaPreviewLog.microsSince(hashStart));
                Path cacheDirectory = this.cachePath.getParent();
                if (cacheDirectory == null) {
                    throw new IOException("3D preview cache path has no parent directory");
                }
                Files.createDirectories(cacheDirectory);
                MeshData pendingMesh = null;
                try (var phase = QuickLitematicaPreviewLog.phase("等待同投影后台缓存提交")) {
                    var lease = borrowPendingMesh(this.cachePath, sourceHash, this.resourcePackSignature);
                    if (lease == null) {
                        awaitPendingWrite(this.cachePath, sourceHash, this.resourcePackSignature);
                    } else {
                        try {
                            synchronized (this) {
                                this.throwIfCancelled();
                                this.borrowedMesh = lease;
                                pendingMesh = lease.mesh;
                            }
                        } catch (RuntimeException | Error failure) {
                            lease.close();
                            throw failure;
                        }
                    }
                }
                this.throwIfCancelled();
                Path readCachePath = this.cachePath;
                CacheIndexEntry indexEntry = readCacheIndexEntry(this.cacheSlot);
                boolean sourceHashMatches = indexEntry != null && sourceHash.equals(indexEntry.sourceHash());
                boolean cacheSignatureMatches = sourceHashMatches
                        && this.resourcePackSignature.equals(indexEntry.resourcePackSignature());
                this.trace.event("缓存判定：索引={}，源哈希匹配={}，资源签名匹配={}，读取路径={}，缓存版本/标记由文件头校验",
                        indexEntry, sourceHashMatches, cacheSignatureMatches, readCachePath);
                MeshData cached = pendingMesh != null ? pendingMesh
                        : cacheSignatureMatches ? CacheFile.read(readCachePath, this.cancelled, this.staticMeshStore) : null;
                if (cached != null) {
                    this.trace.event("静态网格缓存命中：层批次={}，顶点={}，动态状态/方块实体/实体={}/{}/{}，跳过原理图读取和模型构建",
                            cached.layers().size(), cached.vertexCount(), cached.blockStates.size(), cached.blockEntities.size(), cached.entities.size());
                    this.throwIfCancelled();
                    synchronized (this) {
                        this.throwIfCancelled();
                        this.initializeDimensions(cached.sizeX(), cached.sizeY(), cached.sizeZ());
                        this.meshData = cached;
                        this.state = State.BUILDING;
                    }
                    // 有界发布可能等待渲染线程消费，不能持有预览锁或提前标记上传完成。
                    this.publishStaticLayers(cached.layers());
                    synchronized (this) {
                        this.throwIfCancelled();
                        this.progress = 1.0F;
                        this.state = State.READY;
                        this.trace.event("预览状态改变：READY");
                    }
                    return;
                }

                this.state = State.BUILDING;
                this.trace.event("预览状态改变：BUILDING");
                Path convertedPath = this.convertedSchematicPath();
                boolean convertedSignatureMatches = convertedCacheMatches(readConvertedCacheIndexEntry(this.cacheSlot),
                        indexEntry, sourceHash, this.resourcePackSignature);
                LitematicaSchematic schematic = convertedSignatureMatches
                        ? QuickLitematicaPreviewSchematicFiles.readSchematic(convertedPath, this.cancelled, false)
                        : null;
                boolean convertedCacheHit = schematic != null;
                this.trace.event("转换原理图缓存判定：命中={}，路径={}，源哈希匹配={}，资源签名匹配={}", convertedCacheHit, convertedPath, sourceHashMatches, convertedSignatureMatches);
                if (!convertedCacheHit) {
                    deleteQuietly(convertedPath);
                    schematic = QuickLitematicaPreviewSchematicFiles.readSchematic(this.sourcePath, this.cancelled, true);
                }
                int effectiveSourceVersion = schematic.getMetadata().getMinecraftDataVersion();
                // 上游禁用 DFU 时不能把未转换的 NBT 写成当前版本缓存。
                LitematicaSchematic convertedSchematic = !convertedCacheHit
                        && effectiveSourceVersion < LitematicaSchematic.MINECRAFT_DATA_VERSION
                        && fi.dy.masa.litematica.util.DataFixerMode.getEffectiveSchema(effectiveSourceVersion) != null
                        ? schematic : null;
                this.trace.event("转换原理图缓存保存判定：需要保存={}，有效源版本={}，目标版本={}，已命中转换缓存={}",
                        convertedSchematic != null, effectiveSourceVersion, LitematicaSchematic.MINECRAFT_DATA_VERSION, convertedCacheHit);
                MeshData built;
                try {
                    built = QuickLitematicaPreviewMeshBuilder.build(
                            schematic,
                            this.cancelled,
                            value -> this.progress = value,
                            this::initializeDimensions,
                            this::publishStaticLayers,
                            this.staticMeshStore
                    );
                } catch (RuntimeException | Error failure) {
                    if (convertedSchematic != null && isPreviewTooLarge(failure) && !this.isCancelled()) {
                        this.saveConvertedCache(convertedSchematic, sourceHash);
                    }
                    throw failure;
                }
                this.throwIfCancelled();
                if (!built.withinBudget()) {
                    if (convertedSchematic != null) this.saveConvertedCache(convertedSchematic, sourceHash);
                    built.closeDynamic();
                    this.rejectBuild(built.vertexCount() > QuickLitematicaPreviewVertexBudget.vertexLimit());
                    return;
                }

                synchronized (this) {
                    this.throwIfCancelled();
                    this.meshData = built;
                }
                this.publishStaticLayers(built.layers().stream().filter(LayerMesh::needsTranslucentSorting).toList());

                synchronized (this) {
                    this.throwIfCancelled();
                    this.saveCacheAsync(built, sourceHash, convertedSchematic);
                    this.trace.milestone("CPU 模型构建完成：顶点={}，层批次={}，尺寸={}x{}x{}", built.vertexCount(), built.layers().size(), built.sizeX(), built.sizeY(), built.sizeZ());
                    this.meshData = built;
                    this.progress = 1.0F;
                    this.state = State.READY;
                    this.trace.event("预览状态改变：READY");
                }
            } catch (CancellationException ignored) {
                this.cancelBuild();
            } catch (PreviewTooLargeException failure) {
                this.rejectBuild(failure.vertexLimit);
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.cancelBuild();
                    return;
                }
                if (isPreviewTooLarge(e)) {
                    this.rejectBuild(isPreviewVertexLimit(e));
                    return;
                }
                this.failBuild(e);
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
                long actualSize = Files.size(this.sourcePath);
                if (actualSize != this.sourceSize) {
                    this.trace.event("当前预览失效：原因=source_size_changed，源={}，大小={}->{}", this.sourcePath, this.sourceSize, actualSize);
                    return true;
                }
                long actualModified = Files.getLastModifiedTime(this.sourcePath).toMillis();
                if (actualModified != this.sourceModifiedMillis) {
                    this.trace.event("当前预览失效：原因=source_time_changed，源={}，时间={}->{}", this.sourcePath, this.sourceModifiedMillis, actualModified);
                    return true;
                }
                String actualSignature = currentCacheSignature();
                boolean changed = !actualSignature.equals(this.resourcePackSignature);
                if (changed) this.trace.event("当前预览失效：原因=resource_signature_changed，签名={}->{}", this.resourcePackSignature, actualSignature);
                return changed;
            } catch (IOException e) {
                this.trace.event("当前预览需重新核验：原因=source_stat_failed，源={}，异常={}", this.sourcePath, e.toString());
                return true;
            }
        }

        String sourceName() {
            Path fileName = this.sourcePath.getFileName();
            return fileName == null ? this.sourcePath.toString() : fileName.toString();
        }

        private void saveConvertedCache(LitematicaSchematic schematic, String sourceHash) {
            try {
                if (QuickLitematicaPreviewSchematicFiles.writeConvertedSchematic(schematic, this.convertedSchematicPath(), this.cacheSlot)) {
                    writeConvertedCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                    this.trace.milestone("converted-cache-independent-v1：转换原理图及独立签名已提交，网格超限或压缩不影响复用");
                }
            } catch (IOException failure) {
                LOGGER.warn("转换原理图缓存索引保存失败：源={}，继续原读取回退", this.sourcePath, failure);
            }
        }

        private void saveCacheAsync(MeshData built, String sourceHash, @Nullable LitematicaSchematic schematic) {
            // 快照保留小网格顶点或大网格暂存引用；关闭预览不会打断独立的缓存保存。
            MeshData snapshot = new MeshData(List.copyOf(built.layers()), built.blockStates, built.blockEntities,
                    built.entities, built.sizeX(), built.sizeY(), built.sizeZ());
            Path sourcePath = this.sourcePath;
            Path cachePath = this.cachePath;
            Path tmpPath = this.tmpPath;
            String cacheSlot = this.cacheSlot;
            String resourcePackSignature = this.resourcePackSignature;
            this.trace.event("后台缓存保存排队：路径={}，快照层批次={}，顶点={}", cachePath, snapshot.layers().size(), snapshot.vertexCount());
            long cacheQueued = QuickLitematicaPreviewLog.startTimer();
            Runnable cacheWrite = this.trace.wrap(() -> {
                LOGGER.info("后台缓存保存开始：路径={}，排队={} us", cachePath, QuickLitematicaPreviewLog.microsSince(cacheQueued));
                try {
                    if (schematic != null) this.saveConvertedCache(schematic, sourceHash);
                    CacheFile.writeAtomically(tmpPath, cachePath, snapshot, snapshot.layers(), new AtomicBoolean(), QuickLitematicaPreviewLog.progressSink("缓存压缩写入"));
                    try (var phase = QuickLitematicaPreviewLog.phase("提交缓存索引")) {
                        writeCacheIndexEntry(cacheSlot, sourcePath, sourceHash, resourcePackSignature);
                    }
                    this.trace.milestone("缓存文件及索引提交成功：路径={}，源哈希={}", cachePath, sourceHash);
                    QuickLitematicaPreviewLog.file("缓存保存完成", cachePath);
                } catch (Exception e) {
                    deleteTmpQuietly(tmpPath);
                    LOGGER.error("后台缓存保存失败：源={}，临时={}，最终={}，显示结果保留，临时文件清理", sourcePath, tmpPath, cachePath, e);
                } finally {
                    snapshot.releaseStaticVertices();
                    this.staticMeshStore.releaseCache();
                    this.trace.summary("后台缓存保存任务结束");
                }
            });
            this.staticMeshStore.retainForCache();
            try {
                writeAsync(cachePath, cacheWrite, sourceHash, resourcePackSignature, snapshot.vertexCount(), snapshot, this.staticMeshStore);
            } catch (RuntimeException | Error failure) {
                this.staticMeshStore.releaseCache();
                throw failure;
            }
        }

        Path convertedSchematicPath() {
            return this.cachePath.resolveSibling(this.cacheSlot + ".converted.litematic");
        }

        void initializeDimensions(int sizeX, int sizeY, int sizeZ) {
            this.dimensions = new PreviewDimensions(sizeX, sizeY, sizeZ);
        }

        void publishStaticLayers(List<LayerMesh> layers) {
            for (LayerMesh layer : layers) {
                this.throwIfCancelled();
                // 透明层加载期间本就不绘制；等整层数量确定后再按批上传，避免合并时复制整层显存。
                if (layer.needsTranslucentSorting() && this.meshData == null) continue;
                this.staticUploads.publish(layer, this::throwIfCancelled);
                this.trace.event("网格批次发布：层={}，顶点={}，量化={} B，上传队列剩余预算={} KiB",
                        layer.layer(), layer.vertexCount(), layer.quantizedByteCount(), this.staticUploads.availableKiB());
            }
        }

        private void releaseBorrowedMesh() {
            var lease = this.borrowedMesh;
            this.borrowedMesh = null;
            if (lease != null) lease.close();
        }

        void discardPartialStatic() {
            this.staticUploads.discard();
            this.releaseBorrowedMesh();
            this.staticMeshStore.close();
            this.dimensions = null;
            this.closeRendererOnRenderThread();
        }

        void completeStaticUploadIfReady() {
            if (this.cancelled.get() || this.staticUploadComplete || this.uploadScheduled || this.state != State.READY || !this.staticUploads.isEmpty()) {
                return;
            }

            this.mergeTransparentBuffers();
            this.staticUploadComplete = true;
            this.trace.milestone("静态 GPU 上传调用全部完成，CPU 顶点可释放");
            this.staticMeshStore.close();
            this.releaseBorrowedMesh();
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
            this.staticUploads.discard();
            this.releaseBorrowedMesh();
            this.staticMeshStore.close();
            this.meshData = null;
            this.dimensions = null;
            this.state = State.TOO_LARGE;
            this.trace.event("预览状态改变：TOO_LARGE，顶点保护触发={}，强制渲染={}", this.vertexLimitExceeded, this.forceRender);
            this.progress = 1.0F;
            this.trace.event("GPU/动态资源预算失败：状态={}，缓存文件失效，容量判定明细见前序事件", this.state);
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
            this.staticUploads.discard();
            this.releaseBorrowedMesh();
        }

        void refreshCacheSourceHash() {
            try (var scope = this.trace.bind(); var phase = QuickLitematicaPreviewLog.phase("预览图更新后刷新缓存源哈希")) {
                String sourceHash = hashFile(this.sourcePath);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                this.captureSourceStamp();
            } catch (IOException ignored) {
                LOGGER.warn("预览局部失败：入口=refreshCacheSourceHash，继续原有回退", ignored);
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
            if (this.isCancelled() || this.state == State.FAILED) {
                throw new CancellationException();
            }
        }

        boolean isCancelled() {
            return this.cancelled.get() || Thread.currentThread().isInterrupted();
        }

        void uploadIfNeeded() {
            if (this.uploadScheduled || this.cancelled.get() || (this.state != State.BUILDING && this.state != State.READY)) return;
            var uploadTicket = this.staticUploads.poll();
            if (uploadTicket == null) {
                this.completeStaticUploadIfReady();
                return;
            }
            LayerMesh layer = uploadTicket.layer;
            this.trace.event("静态上传排队：层={}，顶点={}，量化大小={} B，剩余批次={}", layer.layer(), layer.vertexCount(), layer.quantizedByteCount(), this.staticUploads.size());
            long uploadQueued = QuickLitematicaPreviewLog.startTimer();
            this.uploadScheduled = true;
            Runnable upload = this.trace.wrap(() -> {
                LOGGER.info("静态上传开始：层={}，调度等待={} us", layer.layer(), QuickLitematicaPreviewLog.microsSince(uploadQueued));
                try (var phase = QuickLitematicaPreviewLog.phase("静态批次解码/排序/上传 CPU 调用")) {
                    if (!this.cancelled.get() && (this.state == State.BUILDING || this.state == State.READY)) this.uploadMesh(layer);
                    LOGGER.info("静态批次上传返回：层={}，已取消={}（后端失败另见明细）", layer.layer(), this.cancelled.get());
                } catch (Throwable failure) {
                    LOGGER.error("Failed to upload a 3D preview batch for {}", this.sourceName(), failure);
                    this.failBuild(failure);
                } finally {
                    uploadTicket.close();
                    this.uploadScheduled = false;
                    this.completeStaticUploadIfReady();
                }
            });
            boolean dispatched = false;
            try {
                if (RenderSystem.isOnRenderThread()) upload.run();
                else MinecraftClient.getInstance().execute(upload);
                dispatched = true;
            } catch (RuntimeException | Error failure) {
                this.failBuild(failure);
            } finally {
                if (!dispatched) {
                    uploadTicket.close();
                    this.uploadScheduled = false;
                }
            }
        }

        void captureSnapshot(
                int resolution,
                int backgroundColor,
                DragState drag,
                String failureTranslationKey,
                Consumer<Text> messageCallback,
                Consumer<NativeImage> imageCallback
        ) {
            try (var scope = this.trace.bind()) {
                this.trace.event("图像操作请求：操作=captureSnapshot，分辨率={}，背景={}，状态={}", resolution, Integer.toHexString(backgroundColor), this.state);
                if (isShaderPackActive()) {
                    messageCallback.accept(Text.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                    return;
                }

                MeshData data = this.meshData;
                if (this.state != State.READY || data == null || this.cancelled.get() || QuickLitematicaPreviewResources.reloading()) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.staticUploadComplete || data.hasDynamicContent() && data.dynamicSceneProgress() < 1.0F) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }
                this.prepareDynamicBuffers(data);
                if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                    messageCallback.accept(Text.translatable(this.dynamicBufferFallback
                            ? "quickcraft.litematica.preview_3d.export_dynamic_failed"
                            : "quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }
                if (data.vertexCount() > 0 && !this.hasUploadedBuffers()) {
                    messageCallback.accept(Text.translatable(failureTranslationKey));
                    return;
                }
                if (!this.snapshotInProgress.compareAndSet(false, true)) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    messageCallback.accept(Text.translatable("quickcraft.litematica.preview_3d.exporting"));
                    return;
                }

                this.takeNativeSnapshot(resolution, backgroundColor, drag, data, this.trace.wrapConsumer(image -> {
                this.trace.milestone("快照像素读回完成：分辨率={}，背景={}", resolution, Integer.toHexString(backgroundColor));
                imageCallback.accept(image);
            }), this.trace.wrapConsumer(failure -> {
                    LOGGER.error("快照渲染或像素读回失败：分辨率={}", resolution, failure);
                    this.snapshotInProgress.set(false);
                    messageCallback.accept(Text.translatable(failureTranslationKey));
                }));
            }
        }

        void exportPng(int resolution, int backgroundColor, DragState drag, Path outputDirectory, Consumer<Text> callback) {
            try (var scope = this.trace.bind()) {
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
                        LOGGER.warn("预览局部失败：入口=exportPng，继续原有回退", ignored);
                        LOGGER.error("图像导出准备失败：目录={}", outputDirectory, ignored);
                    image.close();
                    this.snapshotInProgress.set(false);
                    callback.accept(Text.translatable("quickcraft.litematica.preview_3d.export_failed"));
                    return;
                }

                Util.getIoWorkerExecutor().execute(this.trace.wrap("PNG编码/保存", () -> {
                    try {
                        image.writeTo(outputPath);
                        QuickLitematicaPreviewLog.file("PNG保存完成", outputPath);
                        MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                                "quickcraft.litematica.preview_3d.export_success",
                                outputPath.getFileName().toString()
                        )));
                    } catch (Exception ignored) {
                            LOGGER.error("PNG保存失败：路径={}", outputPath, ignored);
                        MinecraftClient.getInstance().execute(() -> callback.accept(Text.translatable(
                                "quickcraft.litematica.preview_3d.export_failed"
                        )));
                    } finally {
                        image.close();
                        this.snapshotInProgress.set(false);
                    }
                }));
                });
            }
        }

        void copyImage(int resolution, int backgroundColor, DragState drag, Consumer<Text> callback) {
            try (var scope = this.trace.bind()) {
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

                Util.getIoWorkerExecutor().execute(this.trace.wrap("剪贴板编码/写入", () -> {
                    try {
                        QuickLitematicaPreviewClipboard.copyImage(image, ((backgroundColor >>> 24) & 0xFF) != 0xFF);
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
                }));
                });
            }
        }

        @Override
        public synchronized void close() {
            // 缓存临时文件归独立写入任务所有，关闭预览只取消构建并释放显示资源。
            this.trace.event("关闭预览：当前状态={}，待上传批次={}，仅取消构建/读取，已交接缓存写入独立继续", this.state, this.staticUploads.size());
            this.trace.summary("显示资源释放前");
            this.cancelled.set(true);
            PENDING_PREVIEW_BUILD.compareAndSet(this, null);
            Future<?> task = this.future;
            if (task != null) {
                task.cancel(true);
            }

            this.staticUploads.discard();
            this.releaseBorrowedMesh();
            this.staticMeshStore.close();
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.dimensions = null;
            this.closeRendererOnRenderThread();
        }

        void closeRendererOnRenderThread() {
            if (RenderSystem.isOnRenderThread()) this.trace.wrap("GPU/动态资源释放", this::closeRenderer).run();
            else MinecraftClient.getInstance().execute(this.trace.wrap("GPU/动态资源释放", this::closeRenderer));
        }

        static int readImageArgb(NativeImage image, int x, int y) {
            return NativePreview.readImageArgb(image, x, y);
        }

        final void render(DrawContext context, int x, int y, int size, DragState drag) {
            if (QuickLitematicaPreviewLog.enabled()) this.trace.milestone("预览任务状态快照：状态={}，缓存={}，源大小={} B，尺寸={}，待上传批次={}，上传调度中={}",
                    this.state, this.cachePath, this.sourceSize, this.dimensions, this.staticUploads.size(), this.uploadScheduled);
            State currentState = this.state;
            if ((currentState == State.BUILDING || currentState == State.READY) && this.dimensions != null) {
                this.uploadIfNeeded();
                MeshData data = this.meshData;
                if (data != null && this.staticUploadComplete) {
                    try (var scope = this.trace.bind(); var phase = QuickLitematicaPreviewLog.phase("动态场景分帧准备")) {
                        data.prepareDynamicScene();
                    } catch (RuntimeException | Error failure) {
                        this.failBuild(failure);
                        return;
                    }
                }
                if (this.hasUploadedBuffers()
                        || currentState == State.READY && data != null && (data.hasDynamicContent() || this.staticUploadComplete)) {
                    try {
                        this.drawPreview(context, x, y, size, drag);
                    } catch (RuntimeException | Error failure) {
                        this.failBuild(failure);
                        return;
                    }
                    if (data != null && data.hasDynamicContent() && !this.dynamicBuffersReady && !this.dynamicBufferFallback) {
                        context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,
                                Text.translatable("quickcraft.litematica.preview_3d.dynamic_preparing", (int) (this.dynamicProgress * 100)),
                                x + size / 2, y + size - 12, 0xFFDDDDDD);
                    }
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
                case TOO_LARGE -> StringUtils.translate(this.vertexLimitExceeded
                        ? "quickcraft.litematica.preview_3d.vertex_limit" : "quickcraft.litematica.preview_3d.too_large");
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
        abstract void mergeTransparentBuffers();
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
        final boolean vertexLimit;

        PreviewTooLargeException() { this(false); }

        PreviewTooLargeException(boolean vertexLimit) { this.vertexLimit = vertexLimit; }
    }

    static boolean isPreviewVertexLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PreviewTooLargeException tooLarge) return tooLarge.vertexLimit;
        }
        return false;
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

    record LayerMesh(LayerKey layer, byte[] vertices,
                     QuickLitematicaPreviewMeshStore store, long offset, int quantizedByteCount) {
        LayerMesh(LayerKey layer, byte[] quantizedVertices) {
            this(layer, quantizedVertices, null, 0, quantizedVertices.length);
        }

        byte[] quantizedVertices() {
            return this.vertices != null ? this.vertices : this.store.read(this.offset, this.quantizedByteCount);
        }

        boolean needsTranslucentSorting() {
            return this.layer.isTranslucent();
        }

        int vertexCount() {
            return this.quantizedByteCount / QUANTIZED_VERTEX_BYTES;
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
        // 构建在线程池，动态绘制在渲染线程；任务的强制状态随网格交接。
        final boolean forceRender = QuickLitematicaPreviewVertexBudget.isForced();
        List<LayerMesh> layers;
        final List<BlockStateData> blockStates;
        final List<BlockEntityData> blockEntities;
        final List<EntityData> entities;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        @Nullable
        DynamicScene dynamicScene;
        private boolean dynamicSceneInitialized;
        @Nullable private QuickLitematicaPreviewDynamicSceneBuilder dynamicSceneBuilder;

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
                if (vertices > QuickLitematicaPreviewVertexBudget.vertexLimit()) {
                    return false;
                }
            }
            return this.blockStates.size() <= QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES)
                    && this.blockEntities.size() <= QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES)
                    && this.entities.size() <= QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES);
        }

        void releaseStaticVertices() {
            this.layers = List.of();
        }

        boolean hasDynamicContent() {
            return !this.blockEntities.isEmpty() || !this.entities.isEmpty();
        }

        @Nullable
        synchronized DynamicScene dynamicSceneIfReady() {
            if (this.dynamicScene == null) this.dynamicSceneInitialized = false;
            return this.dynamicScene;
        }

        synchronized void prepareDynamicScene() {
            if (this.dynamicSceneInitialized || !this.hasDynamicContent()) return;
            if (this.dynamicSceneBuilder == null) {
                this.dynamicSceneBuilder = new QuickLitematicaPreviewDynamicSceneBuilder(this.blockStates, this.blockEntities, this.entities);
            }
            if (this.dynamicSceneBuilder.advance(QuickLitematicaPreviewBatchBudget.TARGET_NANOS)) {
                this.dynamicScene = this.dynamicSceneBuilder.scene();
                this.dynamicSceneInitialized = true;
                this.dynamicSceneBuilder = null;
            }
        }

        float dynamicSceneProgress() {
            return this.dynamicSceneInitialized || !this.hasDynamicContent() ? 1.0F
                    : this.dynamicSceneBuilder == null ? 0.0F : this.dynamicSceneBuilder.progress();
        }

        synchronized void closeDynamic() {
            this.dynamicScene = null;
            this.dynamicSceneBuilder = null;
        }
    }

    record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    static <V> Map<BlockPos, V> copyPreviewPositions(Map<BlockPos, V> source) {
        // 密集 BlockPos 的哈希集中，Map.copyOf 的线性探测会退化；保留 HashMap 的桶结构与只读快照语义。
        try (var phase = QuickLitematicaPreviewLog.phase("动态位置表只读快照")) {
            LOGGER.info("位置表快照开始：数量={}，容器=HashMap", source.size());
            return Collections.unmodifiableMap(new HashMap<>(source));
        }
    }

    record DynamicScene(DummyWorld world, Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
        boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }

        DynamicScene(Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
            this(null, blockEntities, entities);
        }
    }

    record Bounds(BlockPos min, BlockPos max) {
        static Bounds from(Collection<Box> boxes) {
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
        final QuickLitematicaPreviewFluids fluidVisibility = new QuickLitematicaPreviewFluids();
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

            return this.blockStateContainer.get(pos.getX() - this.bounds.min().getX(),
                    pos.getY() - this.bounds.min().getY(), pos.getZ() - this.bounds.min().getZ());
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
    public static void registerSpecialRenderer() {
        QuickLitematicaPreviewResources.initialize();
        QuickLitematicaPreviewAccess.registerSpecialRenderer();
    }

}
