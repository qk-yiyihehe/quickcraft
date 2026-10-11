package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.InputConstants;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Litematica 文件和游戏内选区的真实方块模型 3D 预览。
 * 构建阶段调用 Minecraft 自带方块渲染器，把材质、异形模型、透明层和流体都录成可缓存的 CPU 顶点。
 */
public final class QuickLitematicaPreview3D {
    private static final AtomicBoolean SHADER_API_ERROR_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SHADER_DISABLE_ERROR_LOGGED = new AtomicBoolean();
    static final QuickLitematicaPreviewLog LOGGER = QuickLitematicaPreviewLog.LOGGER;
    static String inferPreviewBlockEntityId(String blockId, int sourceDataVersion) {
        // 704 之前使用旧式 TileEntity 名称；无法可靠恢复时保留原数据给上游处理。
        if (sourceDataVersion < 704 || !blockId.startsWith("minecraft:")) return "";
        try {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return "";
            BlockState state = block.defaultBlockState();
            if (!(state.getBlock() instanceof EntityBlock provider)) return "";
            BlockEntity blockEntity = provider.newBlockEntity(BlockPos.ZERO, state);
            if (blockEntity == null || !blockEntity.getClass().getName().startsWith("net.minecraft.")) return "";
            var typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
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
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return false;
            BlockState state = block.defaultBlockState();
            if (!(state.getBlock() instanceof EntityBlock provider)) return false;
            BlockEntity blockEntity = provider.newBlockEntity(BlockPos.ZERO, state);
            if (blockEntity == null) return false;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity);
            return renderer == null || renderer.getClass().getName().startsWith("net.minecraft.");
        } catch (Throwable failure) {
            LOGGER.warn("预览库存可见性核验失败：方块={}，保留库存及 DFU", blockId, failure);
            return false;
        }
    }

    static boolean hasPreviewRendererForBlockEntity(String id) {
        try {
            var blockKey = Identifier.parse(id);
            if (!BuiltInRegistries.BLOCK.containsKey(blockKey)) return true;
            var block = BuiltInRegistries.BLOCK.getValue(blockKey);
            // 模组替换原版方块类时，不假定其所有状态都使用同一种方块实体。
            if (block == null || !block.getClass().getName().startsWith("net.minecraft.")) return true;
            BlockState state = block.defaultBlockState();
            if (!(state.getBlock() instanceof EntityBlock provider)) return false;
            // 与网格构建使用相同的类型/渲染器检查，兼容为原版方块注册渲染器的模组。
            return QuickLitematicaPreviewMeshBuilder.hasPreviewBlockEntityRenderer(provider, state, BlockPos.ZERO);
        } catch (Throwable failure) {
            LOGGER.warn("方块实体预览渲染器核验失败：类型={}，保留原数据及 DFU", id, failure);
            return true;
        }
    }

    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 客户端线程访问；也覆盖不进浏览器映射的选区预览，不延长屏幕的生命周期。
    private static final java.util.Set<Manager> LIVE_MANAGERS = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderDispatcher 多线程竞争。
    private static final AtomicReference<Preview> PENDING_PREVIEW_BUILD = new AtomicReference<>();
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    private static final int EXPAND_BUTTON_SIZE = 16;
    private static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    // 1600 万顶点约对应 671 MiB 静态 GPU 顶点数据；只放宽静态网格，动态内容上限仍保持原值。
    static final int MAX_UPLOAD_VERTICES = QuickLitematicaPreviewVertexBudget.DEFAULT_VERTEX_LIMIT;
    static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    static final int MAX_DYNAMIC_ENTITIES = 8_192;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final float MAX_PITCH_RADIANS = (float) Math.toRadians(85.0);
    private static final float PREVIEW_FIT_PADDING = 0.95F;
    static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int VERTEX_BYTES = 44;
    static final int QUANTIZED_VERTEX_BYTES = 32;
    static final int STATIC_BATCH_TARGET_VERTICES = 250_000;
    private static final float PROGRESS_START = 0.05F;
    static final float PROGRESS_MESHING_START = 0.15F;
    static final float PROGRESS_MESHING_END = 0.80F;
    private static final float PROGRESS_SCAN_START = 0.20F;
    private static final float PROGRESS_SCAN_END = 0.55F;
    private static final float PROGRESS_BUILD_START = 0.55F;
    private static final float PROGRESS_BUILD_END = 0.80F;
    static final float PROGRESS_CACHE_WRITE = 0.82F;
    static final float PROGRESS_STATIC_CACHE_END = 0.93F;
    static final float PROGRESS_BLOCK_STATES_CACHE_END = 0.95F;
    static final float PROGRESS_BLOCK_ENTITIES_CACHE_END = 0.99F;
    private static final AtomicBoolean SPECIAL_RENDERER_REGISTERED = new AtomicBoolean();
    static volatile boolean refreshCachedPreviewDynamicTransforms;
    private static final long SOURCE_CHECK_INTERVAL_MILLIS = 1_000L;

    private QuickLitematicaPreview3D() {
    }

    public static boolean shouldRefreshCachedPreviewDynamicTransforms() {
        return refreshCachedPreviewDynamicTransforms;
    }

    public static void registerSpecialRenderer() {
        if (SPECIAL_RENDERER_REGISTERED.compareAndSet(false, true)) {
            QuickLitematicaPreviewResources.initialize();
            QuickLitematicaPreviewAccess.registerSpecialRenderer();
        }
    }

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
        QuickClientScreenAccess.setScreen(Minecraft.getInstance(), new QuickLitematicaPreview3DScreen(
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
            GuiGraphicsExtractor drawContext,
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

    private static void renderShaderDisabled(GuiGraphicsExtractor context, int x, int y, int size) {
        Minecraft client = Minecraft.getInstance();
        RenderUtils.drawOutlinedBox(
                GuiContext.fromGuiGraphics(context),
                x, y, size, size,
                0xB0101010, 0xFF707070
        );
        Component message = Component.translatable("quickcraft.message.litematica.preview_3d.shader_disabled");
        var lines = client.font.split(message, Math.max(1, size - 16));
        int lineStep = client.font.lineHeight + 2;
        int textY = y + (size - lines.size() * lineStep) / 2;
        for (var line : lines) {
            context.centeredText(client.font, line, x + size / 2, textY, 0xFFFFCC55);
            textY += lineStep;
        }
    }

    public static boolean is3DPreviewAvailable() {
        return QuickCraftConfigs.isLitematica3DPreviewEnabled() && !isShaderPackActive();
    }

    public static boolean isShaderPackActive() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return (boolean) apiClass.getMethod("isShaderPackInUse").invoke(api);
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable throwable) {
            if (QuickLitematicaPreviewLog.enabled() && SHADER_API_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shader state could not be queried; disabling QuickCraft 3D previews for this session", throwable);
            }
            return true;
        }
    }

    static boolean tryDisableShaders() {
        try {
            // Iris 是可选依赖，通过其公开 v0 API 查询和修改光影状态。
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object config = apiClass.getMethod("getConfig").invoke(api);
            Class<?> configClass = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            configClass.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
            return true;
        } catch (Throwable failure) {
            if (QuickLitematicaPreviewLog.enabled() && SHADER_DISABLE_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shaders could not be disabled before opening a QuickCraft 3D preview", failure);
            }
            return false;
        }
    }

    static void resourcesReloaded() {
        for (Manager manager : LIVE_MANAGERS) {
            if (manager.current != null) manager.current.close();
        }
    }

    public static boolean prepare3DPreview() {
        if (!isShaderPackActive()) return true;
        if (!QuickCraftConfigs.shouldAutoDisableShadersFor3DPreview()
                || !tryDisableShaders()) return false;
        boolean disabled = !isShaderPackActive();
        if (disabled) InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.shader_auto_disabled");
        return disabled;
    }

    public static final class Manager implements AutoCloseable {
        @Nullable
        private Preview current;
        private String generatedName;
        private Supplier<LitematicaSchematic> generatedSupplier;
        @Nullable
        private Path currentPath;
        @Nullable
        private DirectoryEntry currentEntry;
        private final DragState drag = new DragState();
        private final Screen owner;
        private final Runnable previewMetadataRefresh;
        private final AtomicBoolean previewImageWriteInProgress = new AtomicBoolean();
        @Nullable
        private Consumer<Component> pendingPreviewImageCallback;
        private boolean pendingPreviewImageRestoreFullscreen;
        private boolean pendingPreviewImageKeepMaximized;
        private int pendingPreviewImageWaitTicks;
        private boolean hasEmbeddedPreviewImage;
        private int viewX;
        private int viewY;
        private int viewSize;
        private boolean showExpandButton;
        private long nextSourceCheckMillis;

        private Manager(Screen owner, Runnable previewMetadataRefresh) {
            this.owner = owner;
            this.previewMetadataRefresh = previewMetadataRefresh;
            LIVE_MANAGERS.add(this);
        }

        private void render(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, GuiGraphicsExtractor drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toAbsolutePath().normalize();
            if (!path.equals(this.currentPath)) {
                this.switchTo(path, entry);
            }
            this.currentEntry = entry;
            this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
            this.renderCurrent(drawContext, x, y, size, true);
        }

        private void renderLauncher(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview,
                                    GuiGraphicsExtractor drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toAbsolutePath().normalize();
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

        void renderFullscreen(GuiGraphicsExtractor drawContext, int x, int y, int size) {
            this.renderCurrent(drawContext, x, y, size, false);
        }

        private void renderCurrent(GuiGraphicsExtractor drawContext, int x, int y, int size, boolean showExpandButton) {
            if (!is3DPreviewAvailable()) {
                return;
            }

            this.checkCurrentSource();
            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = showExpandButton;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            RenderUtils.drawOutlinedBox(GuiContext.fromGuiGraphics(drawContext), this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
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

            if (mouseButton == InputConstants.MOUSE_BUTTON_LEFT && this.showExpandButton && this.isExpandButtonHovered(mouseX, mouseY) && this.currentEntry != null) {
                DirectoryEntry entry = this.currentEntry;
                if (this.current == null && this.currentPath != null) {
                    boolean hasEmbeddedPreview = this.hasEmbeddedPreviewImage;
                    this.switchTo(this.currentPath, entry);
                    this.currentEntry = entry;
                    this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
                }
                if (this.current != null) this.current.trace.event("进入全屏：复用任务，当前状态={}", this.current.state);
                QuickClientScreenAccess.setScreen(Minecraft.getInstance(), new QuickLitematicaPreview3DScreen(this.owner, entry.getName(), this));
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
            return Minecraft.getInstance().gameDirectory.toPath().resolve("渲染图");
        }

        int recommendedExportResolution() {
            Preview preview = this.current;
            return preview == null ? 0 : preview.recommendedExportResolution();
        }

        void exportPng(int resolution, int backgroundColor, Consumer<Component> callback) {

            Preview preview = this.current;
            if (preview == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                return;
            }
            preview.exportPng(resolution, backgroundColor, this.drag, this.outputDirectory(), callback);
        }

        void copyImage(int resolution, int backgroundColor, Consumer<Component> callback) {

            Preview preview = this.current;
            if (preview == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
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

        void saveCurrentViewAsPreview(int backgroundColor, Consumer<Component> callback) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canEditPreviewImage() || preview == null || target == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            preview.captureSnapshot(EMBEDDED_PREVIEW_DIMENSION, backgroundColor, this.drag, callback, image -> {
                try {
                    this.writePreviewAsync(preview, target, image.getPixels(), callback);
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to convert the 3D view into Litematica preview pixels", throwable);
                    this.previewImageWriteInProgress.set(false);
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                } finally {
                    image.close();
                    preview.snapshotInProgress.set(false);
                }
            });
        }

        void selectPreviewImage(Consumer<Component> callback) {
            if (!this.canEditPreviewImage() || this.current == null || this.currentPath == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            Minecraft client = Minecraft.getInstance();
            if (QuickClientScreenAccess.isFullscreen(client)) {
                QuickClientScreenAccess.exitFullscreen(client);
                this.pendingPreviewImageCallback = callback;
                this.pendingPreviewImageRestoreFullscreen = true;
                this.pendingPreviewImageKeepMaximized = true;
                this.pendingPreviewImageWaitTicks = 5;
                return;
            }
            this.openPreviewImagePicker(callback, false, QuickClientScreenAccess.isMaximizedWindow(client));
        }

        void pollPendingPreviewImagePicker() {
            Consumer<Component> callback = this.pendingPreviewImageCallback;
            if (callback == null) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (this.pendingPreviewImageRestoreFullscreen
                    && this.pendingPreviewImageWaitTicks > 0
                    && QuickClientScreenAccess.isExclusiveFullscreen(client)) {
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
            QuickClientScreenAccess.restoreGameWindow(Minecraft.getInstance(), restore, keepMaximized);
        }

        private void openPreviewImagePicker(Consumer<Component> callback, boolean restoreFullscreen, boolean keepMaximized) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (preview == null || target == null) {
                QuickClientScreenAccess.restoreGameWindow(Minecraft.getInstance(), restoreFullscreen, keepMaximized);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            Minecraft client = Minecraft.getInstance();
            if (keepMaximized) {
                QuickClientScreenAccess.maximizeGameWindow(client);
            }
            QuickClientScreenAccess.setAutoIconify(client, false);
            try {
                QuickLitematicaPreviewImageWriter.chooseImage(target, selected -> {
                    QuickClientScreenAccess.setAutoIconify(client, true);
                    QuickClientScreenAccess.restoreGameWindow(client, restoreFullscreen, keepMaximized);
                    this.handleSelectedPreviewImage(preview, target, selected, callback);
                });
            } catch (Throwable throwable) {
                LOGGER.error("Failed to open the preview image file picker", throwable);
                QuickClientScreenAccess.setAutoIconify(client, true);
                QuickClientScreenAccess.restoreGameWindow(client, restoreFullscreen, keepMaximized);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
            }
        }

        private void handleSelectedPreviewImage(Preview preview, Path target, @Nullable Path selected,
                                                Consumer<Component> callback) {
            if (selected == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.image_selection_cancelled"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            Util.ioPool().execute(() -> {
                try {
                    int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(selected);
                    QuickLitematicaPreviewImageWriter.writePreview(target, pixels);
                    preview.refreshCacheSourceHash();
                    this.finishPreviewWrite(preview, true, callback, Component.translatable(
                            "quickcraft.litematica.preview_3d.preview_write_success", target.getFileName().toString()));
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to set the Litematica preview image from {}", selected, throwable);
                    this.failPreviewWrite(callback, throwable);
                }
            });
        }

        void removePreviewImage(Consumer<Component> callback) {
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canRemovePreviewImage() || preview == null || target == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_remove_unavailable"));
                return;
            }
            if (!this.previewImageWriteInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_writing"));
                return;
            }

            Util.ioPool().execute(preview.trace.wrap("原理图预览图更新", () -> {
                try {
                    QuickLitematicaPreviewImageWriter.removePreview(target);
                    preview.refreshCacheSourceHash();
                    this.finishPreviewWrite(preview, false, callback, Component.translatable(
                            "quickcraft.litematica.preview_3d.preview_remove_success", target.getFileName().toString()));
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to remove the Litematica preview image from {}", target, throwable);
                    this.failPreviewWrite(callback, throwable);
                }
            }));
        }

        private void writePreviewAsync(Preview preview, Path target, int[] pixels, Consumer<Component> callback) {
            Util.ioPool().execute(() -> {
                try {
                    QuickLitematicaPreviewImageWriter.writePreview(target, pixels);
                    preview.refreshCacheSourceHash();
                    this.finishPreviewWrite(preview, true, callback, Component.translatable(
                            "quickcraft.litematica.preview_3d.preview_write_success", target.getFileName().toString()));
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to set the Litematica preview image for {}", target, throwable);
                    this.failPreviewWrite(callback, throwable);
                }
            });
        }

        private void finishPreviewWrite(Preview preview, boolean hasEmbeddedPreview,
                                        Consumer<Component> callback, Component message) {
            Minecraft.getInstance().execute(() -> {
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

        private void failPreviewWrite(Consumer<Component> callback, Throwable throwable) {
            Minecraft.getInstance().execute(() -> {
                this.previewImageWriteInProgress.set(false);
                callback.accept(Component.translatable(QuickLitematicaPreviewImageWriter.failureTranslationKey(throwable)));
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

        private boolean canHandleMouse(double mouseX, double mouseY) {
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

        private void switchTo(Path path, DirectoryEntry entry) {
            if (this.current != null) this.current.trace.event("切换/重新核验投影：旧={}，新={}", this.currentPath, path);
            this.clearCurrent();
            this.currentPath = path;
            this.currentEntry = entry;
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

        private boolean canForceRender() {
            return this.currentEntry != null && this.current != null && !this.current.forceRender
                    && this.current.state == State.TOO_LARGE;
        }

        private int forceRenderButtonWidth() {
            return Math.min(this.viewSize - 12, Minecraft.getInstance().font.width(
                    StringUtils.translate("quickcraft.litematica.preview_3d.force_render")) + 16);
        }

        private boolean isForceRenderButtonHovered(double mouseX, double mouseY) {
            int width = this.forceRenderButtonWidth();
            int x = this.viewX + (this.viewSize - width) / 2;
            int y = this.viewY + this.viewSize / 2 + 14;
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 18;
        }

        private void drawForceRenderButton(GuiGraphicsExtractor context) {
            Minecraft client = Minecraft.getInstance();
            double mouseX = client.mouseHandler.getScaledXPos(client.getWindow());
            double mouseY = client.mouseHandler.getScaledYPos(client.getWindow());
            int width = this.forceRenderButtonWidth();
            int x = this.viewX + (this.viewSize - width) / 2;
            int y = this.viewY + this.viewSize / 2 + 14;
            boolean hovered = this.isForceRenderButtonHovered(mouseX, mouseY);
            RenderUtils.drawOutlinedBox(GuiContext.fromGuiGraphics(context), x, y, width, 18, hovered ? 0xE0603020 : 0xD0302010, 0xFFCC8855);
            String label = StringUtils.translate("quickcraft.litematica.preview_3d.force_render");
            context.centeredText(client.font, label, this.viewX + this.viewSize / 2, y + 5, 0xFFFFCC77);
            if (hovered) {
                context.setTooltipForNextFrame(client.font, Component.translatable("quickcraft.litematica.preview_3d.force_render_warning"), (int) mouseX, (int) mouseY);
            }
        }

        private void drawExpandButton(GuiGraphicsExtractor context) {
            int x = this.viewX + this.viewSize - EXPAND_BUTTON_SIZE - 3;
            int y = this.viewY + 3;
            Minecraft client = Minecraft.getInstance();
            double mouseX = client.mouseHandler.getScaledXPos(client.getWindow());
            double mouseY = client.mouseHandler.getScaledYPos(client.getWindow());
            boolean hovered = this.isExpandButtonHovered(mouseX, mouseY);
            int backgroundColor = hovered ? 0xA0505050 : 0x40101010;
            context.fill(x + 2, y + 2, x + EXPAND_BUTTON_SIZE - 2, y + EXPAND_BUTTON_SIZE - 2, backgroundColor);
            this.drawExpandIcon(context, x, y, hovered ? 0xFFFFFFFF : 0xFFE0E0E0);
            if (hovered) {
                context.setTooltipForNextFrame(client.font, Component.translatable("quickcraft.litematica.preview_3d.expand"), (int) mouseX, (int) mouseY);
            }
        }

        private void drawExpandIcon(GuiGraphicsExtractor context, int x, int y, int color) {
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
        return Files.isRegularFile(entry.getFullPath()) && FileType.fromFile(entry.getFullPath()) == FileType.LITEMATICA_SCHEMATIC;
    }

    static void runBuildTask(Runnable work, Consumer<Throwable> failureSink) {
        // submit 会把 Error 收进无人读取的 Future，必须在任务内交接失败与资源清理。
        try {
            work.run();
        } catch (RuntimeException | Error failure) {
            failureSink.accept(failure);
        }
    }

    static final class Preview implements AutoCloseable {
        final Path sourcePath;
        final Path cachePath;
        final Path tmpPath;
        final String cacheSlot;
        final String resourcePackSignature;
        private volatile long sourceSize;
        private volatile long sourceModifiedMillis;
        private final long startedAtNanos = System.nanoTime();
        final QuickLitematicaPreviewLog.Trace trace;
        boolean forceRender;
        volatile boolean vertexLimitExceeded;
        final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile MeshData meshData;
        private volatile float progress;
        private volatile State state = State.LOADING;
        @Nullable
        private volatile Future<?> future;
        private final QuickLitematicaPreviewUploadQueue staticUploads = new QuickLitematicaPreviewUploadQueue();
        private final QuickLitematicaPreviewMeshStore staticMeshStore = new QuickLitematicaPreviewMeshStore();
        @Nullable private volatile QuickLitematicaPreviewCache.MeshLease borrowedMesh;

        @Nullable
        private volatile PreviewDimensions dimensions;
        private boolean uploadScheduled;
        private final QuickLitematicaPreviewBackend backend;
        private final AtomicBoolean snapshotInProgress = new AtomicBoolean();

        private Preview(Path sourcePath, Path cachePath, Path tmpPath,
                        String cacheSlot, String resourcePackSignature) {
            this.sourcePath = sourcePath;
            this.cachePath = cachePath;
            this.tmpPath = tmpPath;
            this.cacheSlot = cacheSlot;
            this.resourcePackSignature = resourcePackSignature;
            this.backend = QuickLitematicaPreviewAccess.createBackend(this);
            this.trace = new QuickLitematicaPreviewLog.Trace(sourcePath, this.startedAtNanos);
            this.trace.event("CPU 顶点策略：cpu-spill-v2，小网格驻留阈值={} B，待上传队列预算=64 MiB，大网格缓存副本暂存磁盘，进程退出自动删除；加载预览重画上限=4 Hz", QuickLitematicaPreviewMeshStore.MEMORY_BYTES);
            this.trace.event("预览正确性策略：resource-content-v1 / translucent-view-v3 / beacon-v1，透明材质合并，批内索引与跨批绘制顺序按视角重排");
            if (QuickLitematicaPreviewLog.enabled()) this.trace.event("渲染环境：后端={}，资源包={}，源类型={}",
                    this.backend.getClass().getName(), Minecraft.getInstance().getResourcePackRepository().getSelectedIds(), cacheSlot.isBlank() ? "游戏内选区" : "文件");
            this.captureSourceStamp();
            this.trace.event("任务创建：源={}，缓存={}，临时文件={}，缓存槽={}，资源签名={}，源大小={} B，修改时间={}，默认顶点/动态状态/方块实体/实体上限={}/{}/{}/{}",
                    sourcePath, cachePath, tmpPath, cacheSlot, resourcePackSignature, this.sourceSize, this.sourceModifiedMillis,
                    MAX_UPLOAD_VERTICES, MAX_DYNAMIC_BLOCK_STATES, MAX_DYNAMIC_BLOCK_ENTITIES, MAX_DYNAMIC_ENTITIES);
        }

        QuickLitematicaPreviewBackend backend() {
            return this.backend;
        }

        boolean reuseLoadingTexture(LoadingTexture texture, PreviewGuiElement element) {
            boolean uploading = !this.cancelled.get() && (this.state == State.BUILDING || this.state == State.READY)
                    && !this.backend.isStaticUploadComplete();
            boolean reuse = texture.canReuse(element, uploading, System.nanoTime());
            if (reuse) this.trace.milestone("加载期首次复用预览贴图，静态上传继续，视角改变或上传完成立即重画");
            return reuse;
        }

        Path sourcePath() {
            return this.sourcePath;
        }

        String sourceName() {
            Path fileName = this.sourcePath.getFileName();
            return fileName == null ? this.sourcePath.toString() : fileName.toString();
        }

        @Nullable
        MeshData meshData() {
            return this.meshData;
        }

        @Nullable
        PreviewDimensions dimensions() {
            return this.dimensions;
        }

        boolean isCancelled() {
            return this.cancelled.get() || Thread.currentThread().isInterrupted();
        }

        private static Preview create(DirectoryEntry entry) {
            return create(entry, false);
        }

        private static Preview create(DirectoryEntry entry, boolean forceRender) {
            Path sourcePath = entry.getFullPath().toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new Preview(sourcePath, cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot, currentCacheSignature());
            preview.forceRender = forceRender;
            preview.trace.event("预览预算：强制渲染={}，静态顶点上限={}，默认上限={}，强制时忽略动态数量/缓冲/NBT分配预算，仅当前任务生效",
                    forceRender, forceRender ? Integer.MAX_VALUE : MAX_UPLOAD_VERTICES, MAX_UPLOAD_VERTICES);
            preview.progress = PROGRESS_START;
            preview.startBuild("预览后台读取/构建",
                    () -> QuickLitematicaPreviewVertexBudget.run(forceRender, preview::loadOrBuild));
            return preview;
        }

        private static Preview createGenerated(String displayName, Supplier<LitematicaSchematic> schematicSupplier) {
            String safeName = displayName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (safeName.isBlank()) {
                safeName = "selection";
            }
            Path transientPath = cacheDirectory().resolve("selection-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
            Preview preview = new Preview(Path.of(safeName + ".litematic"), transientPath, transientPath, "", "");
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

        private void loadGenerated(Supplier<LitematicaSchematic> schematicSupplier) {
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

        private void loadOrBuild() {
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
                            cached.layers().size(), cached.vertexCount(), cached.blockStates().size(), cached.blockEntities().size(), cached.entities().size());
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
            MeshData snapshot = new MeshData(List.copyOf(built.layers()), built.blockStates(), built.blockEntities(),
                    built.entities(), built.sizeX(), built.sizeY(), built.sizeZ());
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

        private Path convertedSchematicPath() {
            return this.cachePath.resolveSibling(this.cacheSlot + ".converted.litematic");
        }

        private void initializeDimensions(int sizeX, int sizeY, int sizeZ) {
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

        private void discardPartialStatic() {
            this.staticUploads.discard();
            this.releaseBorrowedMesh();
            this.staticMeshStore.close();
            this.dimensions = null;
            if (RenderSystem.isOnRenderThread()) {
                this.backend.clearBuffers();
            } else {
                Minecraft.getInstance().execute(this.backend::clearBuffers);
            }
        }

        private void render(GuiGraphicsExtractor context, int x, int y, int size, DragState drag) {
            if (QuickLitematicaPreviewLog.enabled()) this.trace.milestone("预览任务状态快照：状态={}，缓存={}，源大小={} B，尺寸={}，待上传批次={}，上传调度中={}",
                    this.state, this.cachePath, this.sourceSize, this.dimensions, this.staticUploads.size(), this.uploadScheduled);
            State currentState = this.state;
            if ((currentState == State.BUILDING || currentState == State.READY) && this.dimensions != null) {
                this.uploadIfNeeded();
                MeshData data = this.meshData;
                if (data != null && this.backend.isStaticUploadComplete()) {
                    try (var scope = this.trace.bind(); var phase = QuickLitematicaPreviewLog.phase("动态场景分帧准备")) {
                        data.prepareDynamicScene();
                    } catch (RuntimeException | Error failure) {
                        this.failBuild(failure);
                        return;
                    }
                }
                if (this.backend.hasBuffers()
                        || (currentState == State.READY && data != null && (data.hasDynamicContent() || this.backend.isStaticUploadComplete()))) {
                    GuiContext guiContext = GuiContext.fromGuiGraphics(context);
                    guiContext.addSpecialElement(new PreviewGuiElement(
                            this,
                            x,
                            y,
                            size,
                            drag.dx,
                            drag.dy,
                            drag.angle,
                            drag.pitch,
                            drag.scale,
                            guiContext.peekLastScissor()
                    ));
                    float dynamicProgress = data == null ? 1.0F : (data.dynamicSceneProgress() + this.backend.dynamicPreparationProgress()) * 0.5F;
                    if (data != null && data.hasDynamicContent() && this.backend.isStaticUploadComplete() && dynamicProgress < 1.0F) {
                        context.fill(x + 2, y + size - 20, x + size - 2, y + size - 2, 0xB0000000);
                        context.centeredText(Minecraft.getInstance().font,
                                StringUtils.translate("quickcraft.litematica.preview_3d.dynamic_preparing", Math.round(dynamicProgress * 100.0F)),
                                x + size / 2, y + size - 15, 0xFFDDDDDD);
                    }
                    return;
                }
            }

            this.renderProgress(context, x, y, size);
        }

        private void uploadIfNeeded() {
            if (this.uploadScheduled || this.cancelled.get() || (this.state != State.BUILDING && this.state != State.READY)) {
                return;
            }

            var uploadTicket = this.staticUploads.poll();
            if (uploadTicket == null) {
                this.completeStaticUploadIfReady();
                return;
            }

            LayerMesh layerMesh = uploadTicket.layer;
            this.trace.event("静态上传排队：层={}，顶点={}，量化大小={} B，剩余批次={}", layerMesh.layer(), layerMesh.vertexCount(), layerMesh.quantizedByteCount(), this.staticUploads.size());
            long uploadQueued = QuickLitematicaPreviewLog.startTimer();
            this.uploadScheduled = true;
            Runnable upload = this.trace.wrap(() -> {
                LOGGER.info("静态上传开始：层={}，调度等待={} us", layerMesh.layer(), QuickLitematicaPreviewLog.microsSince(uploadQueued));
                try (var phase = QuickLitematicaPreviewLog.phase("静态批次解码/排序/上传 CPU 调用")) {
                    if (this.cancelled.get() || (this.state != State.BUILDING && this.state != State.READY)) {
                        return;
                    }

                    boolean success = this.backend.uploadLayer(layerMesh);
                    LOGGER.info("静态批次上传返回：层={}，成功={}，已取消={}", layerMesh.layer(), success, this.cancelled.get());
                    if (!success) {
                        this.failBuild(new IllegalStateException("Static preview batch upload failed"));
                        return;
                    }
                    if (this.cancelled.get()) {
                        return;
                    }
                } catch (Throwable e) {
                    LOGGER.error("Failed to upload a 3D preview batch for {}", this.sourceName(), e);
                    this.failBuild(e);
                } finally {
                    uploadTicket.close();
                    this.uploadScheduled = false;
                    this.completeStaticUploadIfReady();
                }
            });

            boolean dispatched = false;
            try {
                if (RenderSystem.isOnRenderThread()) upload.run();
                else Minecraft.getInstance().execute(upload);
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

        private void completeStaticUploadIfReady() {
            if (this.cancelled.get() || this.backend.isStaticUploadComplete() || this.uploadScheduled || this.state != State.READY || !this.staticUploads.isEmpty()) {
                return;
            }

            this.backend.setStaticUploadComplete(true);
            this.trace.milestone("静态 GPU 上传调用全部完成，CPU 顶点可释放");
            this.staticMeshStore.close();
            this.releaseBorrowedMesh();
            MeshData data = this.meshData;
            if (data != null) {
                data.releaseStaticVertices();
            }
        }

        private void markTooLarge(@Nullable MeshData data) {
            this.backend.clearBuffers();
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

        private void releaseMeshData() {
            this.backend.clearBuffers();
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.dimensions = null;
            this.staticUploads.discard();
            this.releaseBorrowedMesh();
        }

        private float displayProgress() {
            if (this.progress >= PROGRESS_MESHING_START || this.state == State.FAILED || this.state == State.TOO_LARGE) {
                return this.progress;
            }

            float elapsedSeconds = (System.nanoTime() - this.startedAtNanos) / 1_000_000_000.0F;
            float readingProgress = Math.min(PROGRESS_MESHING_START - 0.01F, PROGRESS_START + elapsedSeconds * 0.02F);
            return Math.max(this.progress, readingProgress);
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
            Runnable close = this.trace.wrap("GPU/动态资源释放", this.backend::close);
            if (RenderSystem.isOnRenderThread()) {
                close.run();
            } else {
                Minecraft.getInstance().execute(close);
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

        private void refreshCacheSourceHash() {
            try (var scope = this.trace.bind(); var phase = QuickLitematicaPreviewLog.phase("预览图更新后刷新缓存源哈希")) {
                String sourceHash = hashFile(this.sourcePath);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                this.captureSourceStamp();
            } catch (IOException ignored) {
                LOGGER.warn("预览局部失败：入口=refreshCacheSourceHash，继续原有回退", ignored);
            }
        }

        private int recommendedExportResolution() {
            MeshData data = this.meshData;
            if (data == null) return 0;
            long target = 4L * Math.max(data.sizeX(), Math.max(data.sizeY(), data.sizeZ()));
            if (target <= 512) return 512;
            if (target <= 1024) return 1024;
            if (target <= 2048) return 2048;
            if (target <= 4096) return 4096;
            return 8192;
        }

        private void exportPng(int resolution, int backgroundColor, DragState drag, Path outputDirectory, Consumer<Component> callback) {
            try (var scope = this.trace.bind()) {
                if (isShaderPackActive()) {
                    callback.accept(Component.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                    return;
                }

                MeshData data = this.meshData;
                if (this.state != State.READY || data == null || this.cancelled.get() || QuickLitematicaPreviewResources.reloading()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()
                        || (data.hasDynamicContent() && (data.dynamicSceneProgress() < 1.0F || this.backend.dynamicPreparationProgress() < 1.0F))) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }
                if (data.vertexCount() > 0 && !this.backend.hasBuffers()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                    return;
                }
                if (!this.snapshotInProgress.compareAndSet(false, true)) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                    return;
                }

                Path outputPath;
                try {
                    Files.createDirectories(outputDirectory);
                    outputPath = this.nextOutputPath(outputDirectory, resolution);
                } catch (Exception e) {
                    this.snapshotInProgress.set(false);
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                    return;
                }

                boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
                this.backend.captureSnapshot(resolution, backgroundColor, drag, data, keepBackgroundOpaque, image -> Util.ioPool().execute(this.trace.wrap("PNG编码/保存", () -> {
                    try {
                        image.writeToFile(outputPath);
                        QuickLitematicaPreviewLog.file("PNG保存完成", outputPath);
                        Minecraft.getInstance().execute(() -> callback.accept(Component.translatable(
                                "quickcraft.litematica.preview_3d.export_success", outputPath.getFileName().toString()
                        )));
                    } catch (Exception ignored) {
                        LOGGER.error("PNG保存失败：路径={}", outputPath, ignored);
                        Minecraft.getInstance().execute(() -> callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed")));
                    } finally {
                        image.close();
                        this.snapshotInProgress.set(false);
                    }
                })), throwable -> {
                    this.snapshotInProgress.set(false);
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                });
            }
        }

        private void copyImage(int resolution, int backgroundColor, DragState drag, Consumer<Component> callback) {
            try (var scope = this.trace.bind()) {
                if (!Platform.isWindows()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                    return;
                }

                if (isShaderPackActive()) {
                    callback.accept(Component.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                    return;
                }

                MeshData data = this.meshData;
                if (this.state != State.READY || data == null || this.cancelled.get() || QuickLitematicaPreviewResources.reloading()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()
                        || (data.hasDynamicContent() && (data.dynamicSceneProgress() < 1.0F || this.backend.dynamicPreparationProgress() < 1.0F))) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }
                if (data.vertexCount() > 0 && !this.backend.hasBuffers()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                    return;
                }
                if (!this.snapshotInProgress.compareAndSet(false, true)) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                    return;
                }

                boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
                this.backend.captureSnapshot(resolution, backgroundColor, drag, data, keepBackgroundOpaque, image -> Util.ioPool().execute(this.trace.wrap("剪贴板编码/写入", () -> {
                    try {
                        QuickLitematicaPreviewClipboard.copyImage(image, ((backgroundColor >>> 24) & 0xFF) != 0xFF);
                        Minecraft.getInstance().execute(() -> callback.accept(Component.translatable(
                                "quickcraft.litematica.preview_3d.copy_success"
                        )));
                    } catch (Throwable throwable) {
                        LOGGER.error("Failed to copy the 3D preview image to the Windows clipboard", throwable);
                        Minecraft.getInstance().execute(() -> callback.accept(Component.translatable(
                                "quickcraft.litematica.preview_3d.copy_failed"
                        )));
                    } finally {
                        image.close();
                        this.snapshotInProgress.set(false);
                    }
                })), throwable -> {
                    this.snapshotInProgress.set(false);
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                });
            }
        }

        private void captureSnapshot(int resolution, int backgroundColor, DragState drag,
                                     Consumer<Component> callback, Consumer<NativeImage> imageCallback) {
            try (var scope = this.trace.bind()) {
                this.trace.event("图像操作请求：操作=captureSnapshot，分辨率={}，背景={}，状态={}", resolution, Integer.toHexString(backgroundColor), this.state);
                if (isShaderPackActive()) {
                    callback.accept(Component.translatable("quickcraft.message.litematica.preview_3d.shader_disabled"));
                    return;
                }

                MeshData data = this.meshData;
                if (this.state != State.READY || data == null || this.cancelled.get() || QuickLitematicaPreviewResources.reloading()) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()
                        || (data.hasDynamicContent() && (data.dynamicSceneProgress() < 1.0F || this.backend.dynamicPreparationProgress() < 1.0F))) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }
                if (data.vertexCount() > 0 && !this.backend.hasBuffers()) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                    return;
                }
                if (!this.snapshotInProgress.compareAndSet(false, true)) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                    return;
                }

                boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
                this.backend.captureSnapshot(resolution, backgroundColor, drag, data, keepBackgroundOpaque, imageCallback, throwable -> {
                    this.snapshotInProgress.set(false);
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                });
            }
        }

        private Path nextOutputPath(Path outputDirectory, int resolution) {
            String base = this.sourcePath.getFileName().toString().replaceAll("\\.[^.]+$", "");
            String sanitized = base.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (sanitized.isBlank()) sanitized = "render";
            String prefix = sanitized + "_" + resolution + "x" + resolution;
            Path candidate = outputDirectory.resolve(prefix + ".png");
            int counter = 1;
            while (Files.exists(candidate)) {
                candidate = outputDirectory.resolve(prefix + "_" + counter + ".png");
                counter++;
            }
            return candidate;
        }

        private void renderProgress(GuiGraphicsExtractor context, int x, int y, int size) {
            int barWidth = Math.max(24, size - 12);
            int barX = x + (size - barWidth) / 2;
            int barY = y + size / 2 - 5;
            int fill = Math.max(0, Math.min(barWidth - 2, (int) ((barWidth - 2) * this.displayProgress())));
            int textColor = this.state == State.FAILED || this.state == State.TOO_LARGE ? 0xFFFF7777 : 0xFFDDDDDD;
            String text = switch (this.state) {
                case FAILED -> StringUtils.translate("quickcraft.litematica.preview_3d.failed");
                case TOO_LARGE -> StringUtils.translate(this.vertexLimitExceeded
                        ? "quickcraft.litematica.preview_3d.vertex_limit" : "quickcraft.litematica.preview_3d.too_large");
                default -> StringUtils.translate("quickcraft.litematica.preview_3d.rendering");
            };

            context.centeredText(Minecraft.getInstance().font, text, x + size / 2, barY - 14, textColor);
            RenderUtils.drawOutlinedBox(GuiContext.fromGuiGraphics(context), barX, barY, barWidth, 10, 0xB0000000, 0xFF707070);
            if (fill > 0) {
                context.fill(barX + 1, barY + 1, barX + 1 + fill, barY + 9,
                        this.state == State.FAILED || this.state == State.TOO_LARGE ? 0xFFAA3333 : 0xFF4DB36A);
            }
        }

        private void throwIfCancelled() {
            if (this.isCancelled() || this.state == State.FAILED) {
                throw new CancellationException();
            }
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

    static final class LoadingTexture {
        // 加载期避免每批上传后重画整个大网格；视角变化和最终完成必须立即刷新。
        private static final long REDRAW_NANOS = 250_000_000L;
        private PreviewGuiElement renderedElement;
        private long reuseStartedAt;
        private boolean reusing;

        boolean canReuse(PreviewGuiElement element, boolean uploading, long now) {
            if (!uploading || !element.equals(this.renderedElement)) return false;
            // 从下一帧开始计时，避免上次 GPU 阻塞耗尽间隔后又立即重画。
            if (!this.reusing) {
                this.reusing = true;
                this.reuseStartedAt = now;
            }
            return now - this.reuseStartedAt < REDRAW_NANOS;
        }

        void rendered(PreviewGuiElement element) {
            this.renderedElement = element;
            this.reusing = false;
        }
    }

    static record PreviewGuiElement(
            Preview preview,
            int x0,
            int y0,
            int size,
            float dragX,
            float dragY,
            double angle,
            float pitch,
            float dragScale,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds
    ) implements PictureInPictureRenderState {
        private PreviewGuiElement(
                Preview preview,
                int x,
                int y,
                int size,
                float dragX,
                float dragY,
                double angle,
                float pitch,
                float dragScale,
                @Nullable ScreenRectangle scissorArea
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
                    PictureInPictureRenderState.getBounds(x, y, x + size, y + size, scissorArea)
            );
        }

        @Override
        public int x1() {
            return this.x0 + this.size;
        }

        @Override
        public int y1() {
            return this.y0 + this.size;
        }

        @Override
        public float scale() {
            return 1.0F;
        }
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

    private static void translateToScreen(Matrix4fStack matrixStack, Minecraft client, float x, float y) {
        Screen screen = QuickClientScreenAccess.currentScreen(client);
        int screenWidth = screen == null ? client.getWindow().getGuiScaledWidth() : screen.width;
        int screenHeight = screen == null ? client.getWindow().getGuiScaledHeight() : screen.height;
        matrixStack.translate((2.0F * x - screenWidth) / screenHeight, -(2.0F * y - screenHeight) / screenHeight, 0.0F);
    }

    private enum State {
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

            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                this.angle += deltaX * 0.015;
                this.pitch = Math.max(
                        -MAX_PITCH_RADIANS,
                        Math.min(MAX_PITCH_RADIANS, this.pitch + (float) deltaY * 0.015F)
                );
                return true;
            }

            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
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

    enum LayerKey {
        SOLID(0) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.solidMovingBlock();
            }
        },
        CUTOUT_MIPPED(1) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.cutoutMovingBlock();
            }
        },
        CUTOUT(2) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.cutoutMovingBlock();
            }
        },
        TRIPWIRE(3) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.cutoutMovingBlock();
            }
        },
        TRANSLUCENT(4) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.translucentMovingBlock();
            }
        },
        PORTAL(5) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.translucentMovingBlock();
            }
        },
        FLUID(6) {
            @Override
            RenderType renderLayer() {
                return RenderTypes.translucentMovingBlock();
            }
        };

        static final LayerKey[] DRAW_ORDER = {SOLID, CUTOUT_MIPPED, CUTOUT, TRIPWIRE, PORTAL, FLUID, TRANSLUCENT};
        private final int id;

        LayerKey(int id) {
            this.id = id;
        }

        abstract RenderType renderLayer();

        boolean isTranslucent() {
            return this == PORTAL || this == FLUID || this == TRANSLUCENT;
        }

        boolean drawAfterEntities() {
            return this == TRANSLUCENT;
        }

        static LayerKey from(RenderType layer) {
            if (layer == RenderTypes.solidMovingBlock()) {
                return SOLID;
            }
            if (layer == RenderTypes.cutoutMovingBlock()) {
                return CUTOUT;
            }
            if (layer == RenderTypes.translucentMovingBlock() || layer.hasBlending()) {
                return TRANSLUCENT;
            }
            return SOLID;
        }

        static LayerKey from(ChunkSectionLayer layer) {
            return switch (layer) {
                case SOLID -> SOLID;
                case CUTOUT -> CUTOUT;
                case TRANSLUCENT -> TRANSLUCENT;
            };
        }

        static LayerKey fromFluid(ChunkSectionLayer layer) {
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

        int id() {
            return this.id;
        }
    }

    static record LayerMesh(LayerKey layer, byte[] vertices,
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

    private static HolderGetter<Block> blockLookup(RegistryAccess registryManager) {
        return registryManager.lookupOrThrow(Registries.BLOCK);
    }

    record BlockStateData(int x, int y, int z, CompoundTag stateNbt) {
        BlockState state(RegistryAccess registryManager) {
            return NbtUtils.readBlockState(blockLookup(registryManager), this.stateNbt);
        }
    }

    static record PreviewDimensions(int sizeX, int sizeY, int sizeZ) {
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
        private List<LayerMesh> layers;
        private final List<BlockStateData> blockStates;
        private final List<BlockEntityData> blockEntities;
        private final List<EntityData> entities;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        @Nullable
        private DynamicScene dynamicScene;
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

        List<BlockStateData> blockStates() {
            return this.blockStates;
        }

        List<BlockEntityData> blockEntities() {
            return this.blockEntities;
        }

        List<EntityData> entities() {
            return this.entities;
        }
    }

    record EntityData(double x, double y, double z, CompoundTag entityNbt) {
        @Nullable
        RenderedEntity instantiate(DummyWorld world) {
            try {
                Entity entity = QuickLitematicaDataCompat.createEntity(this.entityNbt.copy(), world);
                if (entity == null) {
                    LOGGER.warn("动态实体实例化返回空：位置={},{},{}，原因=upstream_returned_null", this.x, this.y, this.z);
                    return null;
                }

                QuickLitematicaPreviewDynamicSceneBuilder.positionEntity(entity, this.x, this.y, this.z);
                int light = Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(entity, 0.0F);
                return new RenderedEntity(entity, this.x, this.y, this.z, light);
            } catch (Throwable ignored) {
                LOGGER.warn("预览局部失败：入口=instantiate，继续原有回退", ignored);
                LOGGER.warn("动态实例化失败：类型=EntityData，位置={},{},{}", this.x, this.y, this.z, ignored);
                return null;
            }
        }
    }

    static record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    record BlockEntityData(int x, int y, int z, CompoundTag stateNbt, CompoundTag entityNbt) {
        @Nullable
        BlockEntity instantiate(DummyWorld world) {
            BlockState state = NbtUtils.readBlockState(blockLookup(world.registryAccess()), this.stateNbt);
            if (!(state.getBlock() instanceof EntityBlock provider)) {
                return null;
            }

            BlockPos pos = new BlockPos(this.x, this.y, this.z);
            try {
                BlockEntity blockEntity = provider.newBlockEntity(pos, state);
                if (blockEntity == null) {
                    LOGGER.warn("方块实体工厂返回空：位置={},{},{}，状态={}", this.x, this.y, this.z, state);
                    return null;
                }

                if (!this.entityNbt.isEmpty()) {
                    blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), this.entityNbt.copy()));
                }
                blockEntity.setLevel(world);
                return blockEntity;
            } catch (Throwable ignored) {
                LOGGER.warn("预览局部失败：入口=instantiate，继续原有回退", ignored);
                LOGGER.warn("动态实例化失败：类型=BlockEntityData，位置={},{},{}", this.x, this.y, this.z, ignored);
                return null;
            }
        }
    }

    static <V> Map<BlockPos, V> copyPreviewPositions(Map<BlockPos, V> source) {
        // 密集 BlockPos 的哈希集中，Map.copyOf 的线性探测会退化；保留 HashMap 的桶结构与只读快照语义。
        try (var phase = QuickLitematicaPreviewLog.phase("动态位置表只读快照")) {
            LOGGER.info("位置表快照开始：数量={}，容器=HashMap", source.size());
            return Collections.unmodifiableMap(new HashMap<>(source));
        }
    }

    static record DynamicScene(DummyWorld world, Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
        boolean isEmpty() {
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
    static final class ViewportCuller {
        private final Matrix4f modelView;
        private final Matrix4f projection;
        private final float minX;
        private final float maxX;
        private final float minY;
        private final float maxY;
        private final Vector4f scratch = new Vector4f();

        private ViewportCuller(Matrix4f modelView, Matrix4f projection, int viewSize) {
            this.modelView = modelView;
            this.projection = projection;
            // special GUI 使用独立离屏纹理；48px 安全余量换算成 NDC，覆盖延伸出位置点的模型。
            float margin = 96.0F / Math.max(1, viewSize);
            this.minX = -1.0F - margin;
            this.maxX = 1.0F + margin;
            this.minY = -1.0F - margin;
            this.maxY = 1.0F + margin;
        }

        static ViewportCuller forPip(Matrix4f modelView, int viewSize) {
            int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
            int physicalSize = Math.max(1, viewSize * guiScale);
            Projection projection = new Projection();
            projection.setupOrtho(-1000.0F, 1000.0F, physicalSize, physicalSize, true);
            return new ViewportCuller(modelView, projection.getMatrix(new Matrix4f()), physicalSize);
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
            return ndcX < this.minX || ndcX > this.maxX || ndcY < this.minY || ndcY > this.maxY;
        }
    }

    static final class DummyWorld extends WorldSchematic {
        Map<BlockPos, BlockState> blockStates = Map.of();
        Map<BlockPos, BlockEntity> blockEntities = Map.of();

        private DummyWorld(WritableLevelData properties, RegistryAccess registryManager, Holder<DimensionType> dimensionEntry, WorldRendererSchematic renderer) {
            super(properties, registryManager, dimensionEntry, renderer);
        }

        static DummyWorld fromWorld(ClientLevel world) {
            return new DummyWorld(world.getLevelData(), world.registryAccess(), world.dimensionTypeRegistration(), new WorldRendererSchematic(Minecraft.getInstance()));
        }

        private void setBlockStates(Map<BlockPos, BlockState> blockStates) {
            this.blockStates = copyPreviewPositions(blockStates);
        }

        private void setBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
            this.blockEntities = copyPreviewPositions(blockEntities);
        }

        @Override
        public long getGameTime() {
            return QuickLitematicaPreviewBeacons.isInitializing() ? 0L : super.getGameTime();
        }

        @Override
        public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
            return QuickLitematicaPreviewBeacons.isInitializing()
                    ? QuickLitematicaPreviewBeacons.initializationTop() : super.getHeight(type, x, z);
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

    interface ProgressSink {
        void set(float value);
    }

}
