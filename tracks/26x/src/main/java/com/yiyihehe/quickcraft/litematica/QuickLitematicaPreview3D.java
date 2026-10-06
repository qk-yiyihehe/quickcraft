package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.InputConstants;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.FakeLightingProvider;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

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
            return MeshBuilder.hasPreviewBlockEntityRenderer(provider, state, BlockPos.ZERO);
        } catch (Throwable failure) {
            LOGGER.warn("方块实体预览渲染器核验失败：类型={}，保留原数据及 DFU", id, failure);
            return true;
        }
    }

    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderDispatcher 多线程竞争。
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    private static final int EXPAND_BUTTON_SIZE = 16;
    private static final int COMPAT_CLIPBOARD_MAX_DIMENSION = 4096;
    private static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    // 1600 万顶点约对应 704 MiB 静态 GPU 顶点数据；只放宽静态网格，动态内容上限仍保持原值。
    static final int MAX_UPLOAD_VERTICES = 16_000_000;
    static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    static final int MAX_DYNAMIC_ENTITIES = 8_192;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final float MAX_PITCH_RADIANS = (float) Math.toRadians(85.0);
    private static final float PREVIEW_FIT_PADDING = 0.95F;
    static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int VERTEX_BYTES = 44;
    static final int QUANTIZED_VERTEX_BYTES = 32;
    static final int MAX_QUANTIZED_LAYER_BYTES = MAX_UPLOAD_VERTICES * QUANTIZED_VERTEX_BYTES;
    static final int STATIC_BATCH_TARGET_VERTICES = 250_000;
    private static final float PROGRESS_START = 0.05F;
    private static final float PROGRESS_MESHING_START = 0.15F;
    private static final float PROGRESS_MESHING_END = 0.80F;
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
        }

        private void render(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview, GuiGraphicsExtractor drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toAbsolutePath().normalize();
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

        private void renderLauncher(@Nullable DirectoryEntry entry, boolean hasEmbeddedPreview,
                                    GuiGraphicsExtractor drawContext, int x, int y, int size) {
            if (entry == null || !isSupportedLitematic(entry)) {
                this.clearCurrent();
                return;
            }

            Path path = entry.getFullPath().toAbsolutePath().normalize();
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

            this.viewX = x;
            this.viewY = y;
            this.viewSize = Math.max(1, size);
            this.showExpandButton = showExpandButton;
            this.drag.setViewport(this.viewX, this.viewY, this.viewSize);

            RenderUtils.drawOutlinedBox(GuiContext.fromGuiGraphics(drawContext), this.viewX, this.viewY, this.viewSize, this.viewSize, 0xB0101010, 0xFF707070);
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
            this.cancelPendingPreviewImagePicker();
            this.clearCurrent();
        }

        private boolean canHandleMouse(double mouseX, double mouseY) {
            return (this.current != null || this.currentEntry != null)
                    && is3DPreviewAvailable()
                    && this.drag.inViewport(mouseX, mouseY);
        }

        private void switchTo(Path path, DirectoryEntry entry) {
            if (this.current != null) this.current.trace.event("切换/重新核验投影：旧={}，新={}", this.currentPath, path);
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
        final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile MeshData meshData;
        private volatile float progress;
        private volatile State state = State.LOADING;
        @Nullable
        private volatile Future<?> future;
        private final ConcurrentLinkedQueue<LayerMesh> pendingStaticLayers = new ConcurrentLinkedQueue<>();
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
            if (QuickLitematicaPreviewLog.enabled()) this.trace.event("渲染环境：后端={}，资源包={}，源类型={}",
                    this.backend.getClass().getName(), Minecraft.getInstance().getResourcePackRepository().getSelectedIds(), cacheSlot.isBlank() ? "游戏内选区" : "文件");
            this.captureSourceStamp();
            this.trace.event("任务创建：源={}，缓存={}，临时文件={}，缓存槽={}，资源签名={}，源大小={} B，修改时间={}，顶点/动态状态/方块实体/实体上限={}/{}/{}/{}",
                    sourcePath, cachePath, tmpPath, cacheSlot, resourcePackSignature, this.sourceSize, this.sourceModifiedMillis,
                    MAX_UPLOAD_VERTICES, MAX_DYNAMIC_BLOCK_STATES, MAX_DYNAMIC_BLOCK_ENTITIES, MAX_DYNAMIC_ENTITIES);
        }

        QuickLitematicaPreviewBackend backend() {
            return this.backend;
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
            Path sourcePath = entry.getFullPath().toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new Preview(sourcePath, cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot, currentResourcePackSignature());
            preview.progress = PROGRESS_START;
            preview.future = PREVIEW_EXECUTOR.submit(preview.trace.wrap("预览后台读取/构建", () -> {
                try { preview.loadOrBuild(); }
                finally { preview.trace.summary("后台读取/构建任务结束：" + preview.state); }
            }));
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
            preview.future = PREVIEW_EXECUTOR.submit(preview.trace.wrap("选区捕获/构建", () -> preview.loadGenerated(schematicSupplier)));
            return preview;
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
                    this.trace.event("预览状态改变：TOO_LARGE");
                    this.progress = 1.0F;
                    return;
                }

                this.trace.milestone("CPU 模型构建完成：顶点={}，层批次={}，尺寸={}x{}x{}", built.vertexCount(), built.layers().size(), built.sizeX(), built.sizeY(), built.sizeZ());
                this.meshData = built;
                this.progress = 1.0F;
                this.state = State.READY;
                this.trace.event("预览状态改变：READY");
            } catch (CancellationException ignored) {
                this.state = State.CANCELLED;
                this.trace.event("预览状态改变：CANCELLED");
                this.discardPartialStatic();
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.trace.event("预览状态改变：TOO_LARGE");
                this.progress = 1.0F;
                this.discardPartialStatic();
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                    this.trace.event("预览状态改变：CANCELLED");
                    this.discardPartialStatic();
                } else if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.trace.event("预览状态改变：TOO_LARGE");
                    this.progress = 1.0F;
                    this.discardPartialStatic();
                } else {
                    LOGGER.error("Failed to build a 3D preview from the Litematica selection", e);
                    this.state = State.FAILED;
                    this.trace.event("预览状态改变：FAILED");
                    this.discardPartialStatic();
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
                try (var phase = QuickLitematicaPreviewLog.phase("等待同投影后台缓存提交")) {
                    awaitPendingWrite(this.cachePath);
                }
                this.throwIfCancelled();
                Path readCachePath = this.cachePath;
                CacheIndexEntry indexEntry = readCacheIndexEntry(this.cacheSlot);
                boolean sourceHashMatches = indexEntry != null && sourceHash.equals(indexEntry.sourceHash());
                boolean cacheSignatureMatches = sourceHashMatches
                        && this.resourcePackSignature.equals(indexEntry.resourcePackSignature());
                this.trace.event("缓存判定：索引={}，源哈希匹配={}，资源签名匹配={}，读取路径={}，缓存版本/标记由文件头校验",
                        indexEntry, sourceHashMatches, cacheSignatureMatches, readCachePath);
                MeshData cached = cacheSignatureMatches ? CacheFile.read(readCachePath, this.cancelled) : null;
                if (cached != null) {
                    this.trace.event("静态网格缓存命中：层批次={}，顶点={}，动态状态/方块实体/实体={}/{}/{}，跳过原理图读取和模型构建",
                            cached.layers().size(), cached.vertexCount(), cached.blockStates().size(), cached.blockEntities().size(), cached.entities().size());
                    this.throwIfCancelled();
                    this.initializeDimensions(cached.sizeX(), cached.sizeY(), cached.sizeZ());
                    this.publishStaticLayers(cached.layers());
                    this.meshData = cached;
                    this.progress = 1.0F;
                    this.state = State.READY;
                    this.trace.event("预览状态改变：READY");
                    return;
                }

                this.state = State.BUILDING;
                this.trace.event("预览状态改变：BUILDING");
                Path convertedPath = this.convertedSchematicPath();
                LitematicaSchematic schematic = cacheSignatureMatches
                        ? QuickLitematicaPreviewSchematicFiles.readSchematic(convertedPath, this.cancelled, false)
                        : null;
                boolean convertedCacheHit = schematic != null;
                this.trace.event("转换原理图缓存判定：命中={}，路径={}，源哈希匹配={}，资源签名匹配={}", convertedCacheHit, convertedPath, sourceHashMatches, cacheSignatureMatches);
                if (!convertedCacheHit) {
                    deleteQuietly(convertedPath);
                    schematic = QuickLitematicaPreviewSchematicFiles.readSchematic(this.sourcePath, this.cancelled, true);
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
                    this.trace.event("预览状态改变：TOO_LARGE");
                    this.progress = 1.0F;
                    deleteQuietly(this.cachePath);
                    return;
                }

                int effectiveSourceVersion = Math.max(schematic.getMetadata().getMinecraftDataVersion(),
                        fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue());
                // 上游禁用 DFU 时不能把未转换的 NBT 写成当前版本缓存。
                LitematicaSchematic convertedSchematic = !convertedCacheHit
                        && effectiveSourceVersion < LitematicaSchematic.MINECRAFT_DATA_VERSION
                        && fi.dy.masa.litematica.util.DataFixerMode.getEffectiveSchema(effectiveSourceVersion) != null
                        ? schematic : null;
                this.trace.event("转换原理图缓存保存判定：需要保存={}，有效源版本={}，目标版本={}，已命中转换缓存={}",
                        convertedSchematic != null, effectiveSourceVersion, LitematicaSchematic.MINECRAFT_DATA_VERSION, convertedCacheHit);
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
                this.state = State.CANCELLED;
                this.trace.event("预览状态改变：CANCELLED");
                this.discardPartialStatic();
            } catch (PreviewTooLargeException ignored) {
                this.state = State.TOO_LARGE;
                this.trace.event("预览状态改变：TOO_LARGE");
                this.progress = 1.0F;
                this.discardPartialStatic();
                deleteQuietly(this.cachePath);
            } catch (Exception e) {
                if (this.isCancelled()) {
                    this.state = State.CANCELLED;
                    this.trace.event("预览状态改变：CANCELLED");
                    this.discardPartialStatic();
                    return;
                }
                if (isPreviewTooLarge(e)) {
                    this.state = State.TOO_LARGE;
                    this.trace.event("预览状态改变：TOO_LARGE");
                    this.progress = 1.0F;
                    this.discardPartialStatic();
                    deleteQuietly(this.cachePath);
                    return;
                }
                LOGGER.error("Failed to build 3D preview for {}", this.sourceName(), e);
                this.state = State.FAILED;
                this.trace.event("预览状态改变：FAILED");
                this.discardPartialStatic();
                deleteQuietly(this.cachePath);
            }
        }

        private void saveCacheAsync(MeshData built, String sourceHash, @Nullable LitematicaSchematic schematic) {
            // 快照持有量化顶点和 NBT，GPU 上传释放顶点、关闭预览均不影响后台保存。
            MeshData snapshot = new MeshData(List.copyOf(built.layers()), built.blockStates(), built.blockEntities(),
                    built.entities(), built.sizeX(), built.sizeY(), built.sizeZ());
            Path sourcePath = this.sourcePath;
            Path cachePath = this.cachePath;
            Path tmpPath = this.tmpPath;
            Path convertedPath = this.convertedSchematicPath();
            String cacheSlot = this.cacheSlot;
            String resourcePackSignature = this.resourcePackSignature;
            this.trace.event("后台缓存保存排队：路径={}，快照层批次={}，顶点={}", cachePath, snapshot.layers().size(), snapshot.vertexCount());
            long cacheQueued = QuickLitematicaPreviewLog.startTimer();
            writeAsync(cachePath, this.trace.wrap(() -> {
                LOGGER.info("后台缓存保存开始：路径={}，排队={} us", cachePath, QuickLitematicaPreviewLog.microsSince(cacheQueued));
                try {
                    CacheFile.writeAtomically(tmpPath, cachePath, snapshot, snapshot.layers(), new AtomicBoolean(), QuickLitematicaPreviewLog.progressSink("缓存压缩写入"));
                    try (var phase = QuickLitematicaPreviewLog.phase("提交缓存索引")) {
                        writeCacheIndexEntry(cacheSlot, sourcePath, sourceHash, resourcePackSignature);
                    }
                    this.trace.milestone("缓存文件及索引提交成功：路径={}，源哈希={}", cachePath, sourceHash);
                    QuickLitematicaPreviewLog.file("缓存保存完成", cachePath);
                    if (schematic != null) {
                        QuickLitematicaPreviewSchematicFiles.writeConvertedSchematic(schematic, convertedPath, cacheSlot);
                    }
                } catch (Exception e) {
                    deleteTmpQuietly(tmpPath);
                    LOGGER.error("后台缓存保存失败：源={}，临时={}，最终={}，显示结果保留，临时文件清理", sourcePath, tmpPath, cachePath, e);
                } finally {
                    this.trace.summary("后台缓存保存任务结束");
                }
            }));
        }

        private Path convertedSchematicPath() {
            return this.cachePath.resolveSibling(this.cacheSlot + ".converted.litematic");
        }

        private void initializeDimensions(int sizeX, int sizeY, int sizeZ) {
            this.dimensions = new PreviewDimensions(sizeX, sizeY, sizeZ);
        }

        private void publishStaticLayers(List<LayerMesh> layers) {
            if (!this.cancelled.get()) {
                if (QuickLitematicaPreviewLog.enabled()) {
                    for (LayerMesh layer : layers) this.trace.event("网格批次发布：层={}，顶点={}，量化={} B", layer.layer(), layer.vertexCount(), layer.quantizedVertices().length);
                }
                this.pendingStaticLayers.addAll(layers);
            }
        }

        private void discardPartialStatic() {
            this.pendingStaticLayers.clear();
            this.dimensions = null;
            if (RenderSystem.isOnRenderThread()) {
                this.backend.clearBuffers();
            } else {
                Minecraft.getInstance().execute(this.backend::clearBuffers);
            }
        }

        private void render(GuiGraphicsExtractor context, int x, int y, int size, DragState drag) {
            if (QuickLitematicaPreviewLog.enabled()) this.trace.milestone("预览任务状态快照：状态={}，缓存={}，源大小={} B，尺寸={}，待上传批次={}，上传调度中={}",
                    this.state, this.cachePath, this.sourceSize, this.dimensions, this.pendingStaticLayers.size(), this.uploadScheduled);
            State currentState = this.state;
            if ((currentState == State.BUILDING || currentState == State.READY) && this.dimensions != null) {
                this.uploadIfNeeded();
                MeshData data = this.meshData;
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
                    return;
                }
            }

            this.renderProgress(context, x, y, size);
        }

        private void uploadIfNeeded() {
            if (this.uploadScheduled || this.cancelled.get()) {
                return;
            }

            LayerMesh layerMesh = this.pendingStaticLayers.poll();
            if (layerMesh == null) {
                this.completeStaticUploadIfReady();
                return;
            }

            this.trace.event("静态上传排队：层={}，顶点={}，量化大小={} B，剩余批次={}", layerMesh.layer(), layerMesh.vertexCount(), layerMesh.quantizedVertices().length, this.pendingStaticLayers.size());
            long uploadQueued = QuickLitematicaPreviewLog.startTimer();
            this.uploadScheduled = true;
            Runnable upload = this.trace.wrap(() -> {
                LOGGER.info("静态上传开始：层={}，调度等待={} us", layerMesh.layer(), QuickLitematicaPreviewLog.microsSince(uploadQueued));
                try (var phase = QuickLitematicaPreviewLog.phase("静态批次解码/排序/上传 CPU 调用")) {
                    if (this.cancelled.get()) {
                        return;
                    }

                    boolean success = this.backend.uploadLayer(layerMesh);
                    LOGGER.info("静态批次上传返回：层={}，成功={}，已取消={}", layerMesh.layer(), success, this.cancelled.get());
                    if (!success || this.cancelled.get()) {
                        return;
                    }
                } catch (Throwable e) {
                    LOGGER.error("Failed to upload a 3D preview batch for {}", this.sourceName(), e);
                    this.markTooLarge(this.meshData);
                } finally {
                    this.uploadScheduled = false;
                    this.completeStaticUploadIfReady();
                }
            });

            if (RenderSystem.isOnRenderThread()) {
                upload.run();
            } else {
                Minecraft.getInstance().execute(upload);
            }
        }

        private void completeStaticUploadIfReady() {
            if (this.backend.isStaticUploadComplete() || this.uploadScheduled || this.state != State.READY || !this.pendingStaticLayers.isEmpty()) {
                return;
            }

            this.backend.setStaticUploadComplete(true);
            this.trace.milestone("静态 GPU 上传调用全部完成，CPU 顶点可释放");
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
            this.pendingStaticLayers.clear();
            this.meshData = null;
            this.dimensions = null;
            this.state = State.TOO_LARGE;
            this.trace.event("预览状态改变：TOO_LARGE");
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
            this.pendingStaticLayers.clear();
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
            this.trace.event("关闭预览：当前状态={}，待上传批次={}，仅取消构建/读取，已交接缓存写入独立继续", this.state, this.pendingStaticLayers.size());
            this.trace.summary("显示资源释放前");
            this.cancelled.set(true);
            Future<?> task = this.future;
            if (task != null) {
                task.cancel(true);
            }

            this.pendingStaticLayers.clear();
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
                String actualSignature = currentResourcePackSignature();
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
                if (this.state != State.READY || data == null) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()) {
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
                if (this.state != State.READY || data == null) {
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()) {
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
                        copyToWindowsClipboard(image, ((backgroundColor >>> 24) & 0xFF) != 0xFF);
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
                if (this.state != State.READY || data == null) {
                    this.trace.event("快照请求被拒绝：操作=captureSnapshot，状态={}，上传中={}，取消={}", this.state, this.uploadScheduled, this.cancelled.get());
                    callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_not_ready"));
                    return;
                }

                this.uploadIfNeeded();
                if (!this.backend.isStaticUploadComplete()) {
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
            String sanitized = base.replaceAll("[^a-zA-Z0-9_.-]", "_");
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
                case TOO_LARGE -> StringUtils.translate("quickcraft.litematica.preview_3d.too_large");
                default -> StringUtils.translate("quickcraft.litematica.preview_3d.rendering");
            };

            context.centeredText(Minecraft.getInstance().font, text, x + size / 2, barY - 14, textColor);
            RenderUtils.drawOutlinedBox(GuiContext.fromGuiGraphics(context), barX, barY, barWidth, 10, 0xB0000000, 0xFF707070);
            if (fill > 0) {
                context.fill(barX + 1, barY + 1, barX + 1 + fill, barY + 9,
                        this.state == State.FAILED || this.state == State.TOO_LARGE ? 0xFFAA3333 : 0xFF4DB36A);
            }
        }

        // Minecraft 客户端会启用 java.awt.headless，图片剪贴板必须绕过 AWT 直接写 Win32。
        private static void copyToWindowsClipboard(NativeImage image, boolean preserveTransparency) throws InterruptedException {
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
                            row[x] = image.getPixel(x, y);
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
                                compatRow[x] = image.getPixel(sourceX, sourceY);
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
                    throw new IllegalStateException("Could not open/empty clipboard");
                }

                if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIBV5, dibV5Handle) == null) {
                    throw new IllegalStateException("SetClipboardData(CF_DIBV5) failed");
                }
                clipboardOwnsDibV5 = true;

                if (WindowsClipboard.INSTANCE.SetClipboardData(WindowsClipboard.CF_DIB, dibHandle) == null) {
                    throw new IllegalStateException("SetClipboardData(CF_DIB) failed");
                }
                clipboardOwnsDib = true;

                if (pngHandle != null) {
                    int pngFormat = getWindowsPngClipboardFormat();
                    if (pngFormat != 0 && WindowsClipboard.INSTANCE.SetClipboardData(pngFormat, pngHandle) != null) {
                        clipboardOwnsPng = true;
                    }
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
                if (pngHandle != null && !clipboardOwnsPng) {
                    WindowsMemory.INSTANCE.GlobalFree(pngHandle);
                }
            }
        }

        private static byte[] encodeClipboardPng(NativeImage image, int width, int height) throws IOException {
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            if (width == image.getWidth() && height == image.getHeight()) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        bufferedImage.setRGB(x, y, image.getPixel(x, y));
                    }
                }
            } else {
                for (int y = 0; y < height; y++) {
                    int sourceY = Math.min(image.getHeight() - 1, (int) ((y + 0.5) * image.getHeight() / height));
                    for (int x = 0; x < width; x++) {
                        int sourceX = Math.min(image.getWidth() - 1, (int) ((x + 0.5) * image.getWidth() / width));
                        bufferedImage.setRGB(x, y, image.getPixel(sourceX, sourceY));
                    }
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", output);
            return output.toByteArray();
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

        private static int getWindowsPngClipboardFormat() {
            try {
                return User32.INSTANCE.RegisterClipboardFormat("PNG");
            } catch (Throwable ignored) {
                LOGGER.warn("预览局部失败：入口=getWindowsPngClipboardFormat，继续原有回退", ignored);
                return 0;
            }
        }

        private void throwIfCancelled() {
            if (this.isCancelled()) {
                throw new CancellationException();
            }
        }
    }

    static final class PreviewTooLargeException extends RuntimeException {
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

    private interface WindowsClipboard extends StdCallLibrary {
        WindowsClipboard INSTANCE = Native.load("user32", WindowsClipboard.class, W32APIOptions.DEFAULT_OPTIONS);
        int CF_DIB = 8;
        int CF_DIBV5 = 17;

        boolean OpenClipboard(Pointer owner);

        boolean EmptyClipboard();

        int RegisterClipboardFormat(String formatName);

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

    private static final class MeshBuilder {

        private static MeshData build(
                LitematicaSchematic schematic,
                AtomicBoolean cancelled,
                ProgressSink progressSink,
                DimensionsSink dimensionsSink,
                Consumer<List<LayerMesh>> batchSink
        ) {
            try (var phase = QuickLitematicaPreviewLog.phase("模型扫描/记录/网格构建")) {
                Minecraft client = Minecraft.getInstance();
                if (client.level == null) {
                    throw new IllegalStateException("Litematica preview needs a loaded client world");
                }
                progressSink.set(PROGRESS_MESHING_START);

                Bounds bounds = Bounds.from(schematic.getAreas().values());
                dimensionsSink.set(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
                MeshCollector collector = new MeshCollector();
                @Nullable PreviewCtm.Context ctmContext = PreviewCtm.create(collector, client);
                final List<LayerMesh> layers = new ArrayList<>();
                Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
                List<BlockEntityData> blockEntities = new ArrayList<>();
                List<EntityData> entities = new ArrayList<>();
                Map<BlockState, Boolean> blockEntityRendererCache = new HashMap<>();
                long total = Math.max(1L, totalVolume(schematic.getAreas().values()));

                ModelBlockRenderer blockRenderer = new ModelBlockRenderer(true, true, client.getBlockColors());
                FluidRenderer fluidRenderer = new FluidRenderer(client.getModelManager().getFluidStateModelSet());
                long scannedVolume = 0L;

                for (String regionName : schematic.getAreas().keySet()) {
                    throwIfCancelled(cancelled);
                    LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                    Box area = schematic.getAreas().get(regionName);
                    if (container == null || area == null) {
                        LOGGER.warn("跳过区域：名称={}，原因=missing_container_or_area，容器存在={}，区域存在={}", regionName, container != null, area != null);
                        continue;
                    }

                    RegionBlockView view = new RegionBlockView(container, area);
                    RegionBounds regionBounds = RegionBounds.from(area);
                    Map<BlockPos, ?> schematicBlockEntities = schematic.getBlockEntityMapForRegion(regionName);
                    recordEntities(blockStates, entities, view, schematic, regionName, area, bounds, cancelled);

                    long regionVolume = regionBounds.volume();
                    long regionStart = scannedVolume;
                    QuickLitematicaPreviewLog.RegionStats regionStats = QuickLitematicaPreviewLog.enabled()
                            ? new QuickLitematicaPreviewLog.RegionStats(regionName, regionVolume) : null;
                    try {
                        visitNonAirBlocks(container, regionBounds, cancelled, wordProgress -> progressSink.set(
                                PROGRESS_MESHING_START + (PROGRESS_MESHING_END - PROGRESS_MESHING_START)
                                        * ((regionStart + wordProgress * regionVolume) / (float) Math.max(1L, total))
                        ), (pos, state) -> {
                            try {
                                BlockPos renderPos = pos.subtract(bounds.min());
                                long entityStart = regionStats == null ? 0L : System.nanoTime();
                                recordBlockEntity(blockStates, blockEntities, blockEntityRendererCache, view, state, schematicBlockEntities, pos, renderPos, bounds);
                                long fluidStart = regionStats == null ? 0L : System.nanoTime();
                                renderFluidIfPresent(collector, fluidRenderer, view, state, pos, renderPos);
                                long modelStart = regionStats == null ? 0L : System.nanoTime();
                                renderBlockModel(collector, blockRenderer, ctmContext, view, state, pos, renderPos);
                                if (regionStats != null) regionStats.record(state.toString(), fluidStart - entityStart,
                                        modelStart - fluidStart, System.nanoTime() - modelStart);

                            } catch (RuntimeException | Error failure) {
                                LOGGER.error("方块模型记录失败：区域={}，位置={}，状态={}", regionName, pos, state, failure);
                                throw failure;
                            }
                            if (collector.shouldPublishOpaqueBatch()) {
                                List<LayerMesh> batch = collector.drainOpaqueMeshes();
                                layers.addAll(batch);
                                batchSink.accept(batch);
                            }
                        });
                    } finally {
                        if (regionStats != null) regionStats.close();
                    }
                    scannedVolume += regionVolume;
                }

                progressSink.set(PROGRESS_MESHING_END);
                List<LayerMesh> finalBatch = collector.drainAllMeshes();
                layers.addAll(finalBatch);
                batchSink.accept(finalBatch);
                List<LayerMesh> completeLayers = List.copyOf(layers);
                int vertices = vertexCount(completeLayers);
                if (QuickLitematicaPreviewLog.enabled()) LOGGER.info("模型构建统计：顶点={}/{}，层批次={}，动态状态={}/{}，方块实体={}/{}，实体={}/{}，扫描体积={}",
                        vertices, MAX_UPLOAD_VERTICES, completeLayers.size(), blockStates.size(), MAX_DYNAMIC_BLOCK_STATES,
                        blockEntities.size(), MAX_DYNAMIC_BLOCK_ENTITIES, entities.size(), MAX_DYNAMIC_ENTITIES, scannedVolume);
                if (vertices > MAX_UPLOAD_VERTICES
                        || blockStates.size() > MAX_DYNAMIC_BLOCK_STATES
                        || blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES
                        || entities.size() > MAX_DYNAMIC_ENTITIES) {
                    throw new PreviewTooLargeException();
                }
                return new MeshData(completeLayers, new ArrayList<>(blockStates.values()), blockEntities, entities, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            }
        }

        private static int vertexCount(List<LayerMesh> layers) {
            int count = 0;
            for (LayerMesh layer : layers) {
                count += layer.vertexCount();
            }
            return count;
        }

        private static void visitNonAirBlocks(
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
                for (BlockPos pos : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
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

        private static long totalVolume(Collection<Box> boxes) {
            long total = 0L;
            for (Box box : boxes) {
                RegionBounds bounds = RegionBounds.from(box);
                total += bounds.volume();
            }
            return total;
        }

        private static void recordBlockEntity(
                Map<BlockPos, BlockStateData> blockStates,
                List<BlockEntityData> blockEntities,
                Map<BlockState, Boolean> blockEntityRendererCache,
                RegionBlockView view,
                BlockState state,
                @Nullable Map<BlockPos, ?> schematicBlockEntities,
                BlockPos schematicPos,
                BlockPos renderPos,
                Bounds bounds
        ) {
            if (!(state.getBlock() instanceof EntityBlock provider)) {
                return;
            }

            if (!blockEntityRendererCache.computeIfAbsent(state, key -> hasPreviewBlockEntityRenderer(provider, key, renderPos))) {
                return;
            }

            recordDynamicBlockState(blockStates, state, renderPos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborSchematicPos = schematicPos.relative(direction);
                BlockState neighborState = view.getBlockState(neighborSchematicPos);
                if (!neighborState.isAir()) {
                    recordDynamicBlockState(blockStates, neighborState, neighborSchematicPos.subtract(bounds.min()));
                }
            }

            Object data = schematicBlockEntities == null
                    ? null
                    : schematicBlockEntities.get(schematicPos.subtract(view.bounds.min()));
            CompoundTag nbt = data == null
                    ? new CompoundTag()
                    : QuickLitematicaDataCompat.toVanillaNbt(data);
            CompoundTag blockStateNbt = NbtUtils.writeBlockState(state);
            CompoundTag entityNbt = sanitizeBlockEntityNbt(nbt, blockStateNbt.getStringOr("Name", ""));
            entityNbt.putInt("x", renderPos.getX());
            entityNbt.putInt("y", renderPos.getY());
            entityNbt.putInt("z", renderPos.getZ());
            blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), blockStateNbt, entityNbt));
            if (blockEntities.size() > MAX_DYNAMIC_BLOCK_ENTITIES) {
                throw new PreviewTooLargeException();
            }
        }

        private static boolean hasPreviewBlockEntityRenderer(EntityBlock provider, BlockState state, BlockPos renderPos) {
            BlockEntity blockEntity = provider.newBlockEntity(renderPos, state);
            if (blockEntity == null) {
                return false;
            }

            return Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity) != null;
        }

        private static CompoundTag sanitizeBlockEntityNbt(CompoundTag nbt, String blockId) {
            CompoundTag sanitized = nbt.copy();
            // 火堆物品参与动态渲染，只删减已知普通容器的库存。
            String id = sanitized.getStringOr("id", "");
            if ((sanitized.contains("Items") || sanitized.contains("Inventory"))
                    && isNonVisualInventory(id.isEmpty() ? blockId : id) && isPreviewInventoryHidden(blockId)) {
                sanitized.remove("Items");
                sanitized.remove("Inventory");
            }
            return sanitized;
        }

        private static void recordDynamicBlockState(Map<BlockPos, BlockStateData> blockStates, BlockState state, BlockPos renderPos) {
            if (blockStates.size() >= MAX_DYNAMIC_BLOCK_STATES && !blockStates.containsKey(renderPos)) {
                throw new PreviewTooLargeException();
            }

            blockStates.put(renderPos.immutable(), new BlockStateData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtUtils.writeBlockState(state)));
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

            BlockPos regionOrigin = area.getPos1() == null ? BlockPos.ZERO : area.getPos1();
            for (LitematicaSchematic.EntityInfo info : regionEntities) {
                throwIfCancelled(cancelled);
                Vec3 pos = QuickLitematicaDataCompat.entityPos(info);
                double x = pos.x + regionOrigin.getX() - bounds.min().getX();
                double y = pos.y + regionOrigin.getY() - bounds.min().getY();
                double z = pos.z + regionOrigin.getZ() - bounds.min().getZ();
                CompoundTag nbt = QuickLitematicaDataCompat.entityNbt(info);
                entities.add(new EntityData(x, y, z, copyEntityNbtAt(nbt, x, y, z)));
                recordEntityNearbyBlockStates(blockStates, view, bounds, x, y, z);
                if (entities.size() > MAX_DYNAMIC_ENTITIES) {
                    throw new PreviewTooLargeException();
                }
            }
        }

        private static void recordEntityNearbyBlockStates(Map<BlockPos, BlockStateData> blockStates, RegionBlockView view, Bounds bounds, double x, double y, double z) {
            BlockPos center = BlockPos.containing(x, y, z);
            // 矿车 controller 会读取实体所在的铁轨；展示框/画还会查询附着方向的邻居。
            BlockState centerState = view.getBlockState(center.offset(bounds.min()));
            if (!centerState.isAir()) {
                recordDynamicBlockState(blockStates, centerState, center);
            }
            for (Direction direction : Direction.values()) {
                BlockPos renderPos = center.relative(direction);
                BlockPos schematicPos = renderPos.offset(bounds.min());
                BlockState state = view.getBlockState(schematicPos);
                if (!state.isAir()) {
                    recordDynamicBlockState(blockStates, state, renderPos);
                }
            }
        }

        private static CompoundTag copyEntityNbtAt(CompoundTag source, double x, double y, double z) {
            CompoundTag copy = source.copy();
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(x));
            pos.add(DoubleTag.valueOf(y));
            pos.add(DoubleTag.valueOf(z));
            copy.put("Pos", pos);
            return copy;
        }

        private static void renderFluidIfPresent(
                MeshCollector collector,
                FluidRenderer fluidRenderer,
                RegionBlockView view,
                BlockState state,
                BlockPos pos,
                BlockPos renderPos
        ) {
            FluidState fluidState = state.getFluidState();
            if (fluidState.isEmpty()) {
                return;
            }

            Matrix4f transform = new Matrix4f()
                    .translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15))
                    .translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());
            fluidRenderer.tesselate(
                    view,
                    pos,
                    layer -> new FluidVertexConsumer(collector.consumerFor(LayerKey.fromFluid(layer)), transform),
                    state,
                    fluidState
            );
        }

        private static void renderBlockModel(
                MeshCollector collector,
                ModelBlockRenderer blockRenderer,
                @Nullable PreviewCtm.Context ctmContext,
                RegionBlockView view,
                BlockState state,
                BlockPos pos,
                BlockPos renderPos
        ) {
            if (state.getRenderShape() != RenderShape.MODEL) {
                return;
            }

            var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            if (ctmContext != null && PreviewCtm.isContinuityModel(model)
                    && ctmContext.emit(model, view, state, pos, renderPos)) {
                return;
            }

            blockRenderer.tesselateBlock(
                    (x, y, z, quad, instance) -> collector.consumerFor(
                                    state.is(Blocks.NETHER_PORTAL) ? LayerKey.PORTAL : LayerKey.from(quad.materialInfo().layer()))
                            .putBlockBakedQuad(x, y, z, quad, instance),
                    renderPos.getX(),
                    renderPos.getY(),
                    renderPos.getZ(),
                    view,
                    pos,
                    state,
                    model,
                    state.getSeed(pos)
            );
        }

        private static final class PreviewCtm {
            private static final boolean ACTIVE = FabricLoader.getInstance().isModLoaded("continuity");
            @Nullable
            private static String cachedRuntimeToken;

            @Nullable
            private static Context create(MeshCollector collector, Minecraft client) {
                if (!ACTIVE) {
                    return null;
                }
                try {
                    Renderer renderer = Renderer.get();
                    return new Context(
                            renderer.altModelBlockRenderer(true, true, client.getBlockColors()),
                            renderer.quadEmitter(quad -> quad.buffer(
                                    OverlayTexture.NO_OVERLAY,
                                    collector.consumerFor(quad.chunkLayer())
                            ))
                    );
                } catch (Throwable ignored) {
                    LOGGER.warn("预览局部失败：入口=renderBlockModel，继续原有回退", ignored);
                    return null;
                }
            }

            private static boolean isContinuityModel(Object model) {
                return model != null
                        && model.getClass().getName().startsWith("me.pepperbell.continuity.");
            }

            private static String runtimeToken() {
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

            private record Context(AltModelBlockRenderer renderer, QuadEmitter emitter) {
                private boolean emit(
                        net.minecraft.client.renderer.block.dispatch.BlockStateModel model,
                        RegionBlockView view,
                        BlockState state,
                        BlockPos pos,
                        BlockPos renderPos
                ) {
                    try {
                        this.renderer.tesselateBlock(
                                this.emitter,
                                renderPos.getX(),
                                renderPos.getY(),
                                renderPos.getZ(),
                                view,
                                pos,
                                state,
                                model,
                                state.getSeed(pos)
                        );
                        return true;
                    } catch (Throwable ignored) {
                        LOGGER.warn("预览局部失败：入口=renderBlockModel，继续原有回退", ignored);
                        return false;
                    }
                }
            }
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

        private static LayerKey from(RenderType layer) {
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

        private static LayerKey from(ChunkSectionLayer layer) {
            return switch (layer) {
                case SOLID -> SOLID;
                case CUTOUT -> CUTOUT;
                case TRANSLUCENT -> TRANSLUCENT;
            };
        }

        private static LayerKey fromFluid(ChunkSectionLayer layer) {
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

    private static final class MeshCollector {
        private final EnumMap<LayerKey, RecordingVertexConsumer> consumers = new EnumMap<>(LayerKey.class);
        private int vertexCount;

        private VertexConsumer consumerFor(RenderType renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        private VertexConsumer consumerFor(LayerKey layer) {
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        private VertexConsumer consumerFor(ChunkSectionLayer renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        private void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            if (this.vertexCount >= MAX_UPLOAD_VERTICES) {
                LOGGER.warn("网格顶点预算超限：实际={}，上限={}，拒绝继续记录", this.vertexCount, MAX_UPLOAD_VERTICES);
                throw new PreviewTooLargeException();
            }

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
        }

        private boolean shouldPublishOpaqueBatch() {
            int vertices = 0;
            for (Map.Entry<LayerKey, RecordingVertexConsumer> entry : this.consumers.entrySet()) {
                if (!entry.getKey().isTranslucent()) {
                    vertices += entry.getValue().vertices.vertexCount();
                }
            }
            return vertices >= STATIC_BATCH_TARGET_VERTICES;
        }

        private List<LayerMesh> drainOpaqueMeshes() {
            return this.drainMeshes(false);
        }

        private List<LayerMesh> drainAllMeshes() {
            return this.drainMeshes(true);
        }

        private List<LayerMesh> drainMeshes(boolean includeTranslucent) {
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

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private final MeshCollector collector;
        private final QuantizedVertexBuffer vertices = new QuantizedVertexBuffer();
        private float x;
        private float y;
        private float z;
        private int argb = 0xFFFFFFFF;
        private float u;
        private float v;
        private int overlay = OverlayTexture.NO_OVERLAY;
        private int light = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;

        private RecordingVertexConsumer(MeshCollector collector) {
            this.collector = collector;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.argb = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            this.argb = argb;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.overlay = OverlayTexture.pack(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
            return this;
        }

        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.collector.addVertex(this.vertices, this.x, this.y, this.z, this.argb, this.u, this.v, this.overlay, this.light, x, y, z);
            this.overlay = OverlayTexture.NO_OVERLAY;
            this.light = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            this.collector.addVertex(this.vertices, x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
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
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(this.transform, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            this.delegate.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        public VertexConsumer setUv3(float u, float v) {
            QuickLitematicaPreviewAccess.delegateSetUv3(this.delegate, u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            this.delegate.setLineWidth(width);
            return this;
        }
    }

    private static final class QuantizedVertexBuffer {
        private byte[] bytes = new byte[QUANTIZED_VERTEX_BYTES * 256];
        private int position;

        boolean isEmpty() {
            return this.position == 0;
        }

        int vertexCount() {
            return this.position / QUANTIZED_VERTEX_BYTES;
        }

        private void add(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
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

    static record LayerMesh(LayerKey layer, byte[] quantizedVertices) {
        int vertexCount() {
            return this.quantizedVertices.length / QUANTIZED_VERTEX_BYTES;
        }
    }

    private static HolderGetter<Block> blockLookup(RegistryAccess registryManager) {
        return registryManager.lookupOrThrow(Registries.BLOCK);
    }

    record BlockStateData(int x, int y, int z, CompoundTag stateNbt) {
        private BlockState state(RegistryAccess registryManager) {
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
        private List<LayerMesh> layers;
        private final List<BlockStateData> blockStates;
        private final List<BlockEntityData> blockEntities;
        private final List<EntityData> entities;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        @Nullable
        private DynamicScene dynamicScene;

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
        private RenderedEntity instantiate(DummyWorld world) {
            try {
                Entity entity = QuickLitematicaDataCompat.createEntity(this.entityNbt.copy(), world);
                if (entity == null) {
                    LOGGER.warn("动态实体实例化返回空：位置={},{},{}，原因=upstream_returned_null", this.x, this.y, this.z);
                    return null;
                }

                entity.setPos(this.x, this.y, this.z);
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
        private BlockEntity instantiate(DummyWorld world) {
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

    static record DynamicScene(DummyWorld world, Map<BlockPos, BlockEntity> blockEntities, List<RenderedEntity> entities) {
        private static DynamicScene create(List<BlockStateData> blockStateData, List<BlockEntityData> blockEntityData, List<EntityData> entityData) {
            if (blockStateData.isEmpty() && blockEntityData.isEmpty() && entityData.isEmpty()) {
                return new DynamicScene(Map.of(), List.of());
            }

            Minecraft client = Minecraft.getInstance();
            if (client.level == null) {
                return new DynamicScene(Map.of(), List.of());
            }

            DummyWorld world = DummyWorld.fromWorld(client.level);
            Map<BlockPos, BlockState> blockStates = new HashMap<>();
            for (BlockStateData data : blockStateData) {
                blockStates.put(new BlockPos(data.x(), data.y(), data.z()), data.state(world.registryAccess()));
            }
            world.setBlockStates(blockStates);

            Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
            for (BlockEntityData data : blockEntityData) {
                BlockEntity blockEntity = data.instantiate(world);
                if (blockEntity != null) {
                    blockEntities.put(blockEntity.getBlockPos(), blockEntity);
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

    private record Bounds(BlockPos min, BlockPos max) {
        private static Bounds from(Collection<Box> boxes) {
            BlockPos min = BlockPos.ZERO;
            BlockPos max = BlockPos.ZERO;
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

    private record RegionBounds(BlockPos min, BlockPos max) {
        private static RegionBounds from(Box box) {
            BlockPos pos1 = box.getPos1() == null ? BlockPos.ZERO : box.getPos1();
            BlockPos pos2 = box.getPos2() == null ? pos1 : box.getPos2();
            return new RegionBounds(BlockPos.min(pos1, pos2), BlockPos.max(pos1, pos2));
        }

        private long volume() {
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

    private static final class RegionBlockView implements BlockAndTintGetter {
        private final RegionBounds bounds;
        private final LitematicaBlockStateContainer blockStateContainer;
        private final Minecraft client = Minecraft.getInstance();
        private final LevelLightEngine lightingProvider;

        private RegionBlockView(LitematicaBlockStateContainer container, Box area) {
            this.blockStateContainer = container;
            this.bounds = RegionBounds.from(area);
            ClientLevel world = Objects.requireNonNull(this.client.level, "No loaded world for Litematica preview");
            this.lightingProvider = new FakeLightingProvider(new ChunkCacheSchematic(world, world, BlockPos.ZERO, 0));
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return Objects.requireNonNull(this.client.level).cardinalLighting();
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.lightingProvider;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return Objects.requireNonNull(this.client.level).getBlockTint(pos, colorResolver);
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
        public int getMinY() {
            return 0;
        }
    }

    private static final class DummyWorld extends WorldSchematic {
        private Map<BlockPos, BlockState> blockStates = Map.of();
        private Map<BlockPos, BlockEntity> blockEntities = Map.of();

        private DummyWorld(WritableLevelData properties, RegistryAccess registryManager, Holder<DimensionType> dimensionEntry, WorldRendererSchematic renderer) {
            super(properties, registryManager, dimensionEntry, renderer);
        }

        private static DummyWorld fromWorld(ClientLevel world) {
            return new DummyWorld(world.getLevelData(), world.registryAccess(), world.dimensionTypeRegistration(), new WorldRendererSchematic(Minecraft.getInstance()));
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

    interface ProgressSink {
        void set(float value);
    }

    private interface NonAirBlockConsumer {
        void accept(BlockPos position, BlockState state);
    }

    private interface DimensionsSink {
        void set(int sizeX, int sizeY, int sizeZ);
    }

    static String ctmRuntimeToken() {
        return MeshBuilder.PreviewCtm.runtimeToken();
    }

}
