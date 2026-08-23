package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.LitematicaFeatureRenderDispatcherAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaStagedVertexBufferAccessor;
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
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.mojang.math.Axis;
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
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
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
    private static final AtomicBoolean SHADER_DISABLE_WARNING_LOGGED = new AtomicBoolean();
    private static final Map<fi.dy.masa.litematica.gui.GuiSchematicBrowserBase, Manager> MANAGERS = new WeakHashMap<>();
    // 预览构建专用单线程池：避免与 Util.getMainWorkerExecutor 共享导致排队等几秒。
    // 单线程足够（预览一次只构建一个文件），且避免 BlockRenderDispatcher 多线程竞争。
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D");
        thread.setDaemon(true);
        return thread;
    });
    // v17：26.2 RenderType/模型数据和 PIP 提交队列；缓存固定绑定投影路径并校验内容和材质包签名。
    // v16：26.1 方块模型、流体和 PIP 渲染管线；禁止复用 1.21.11 顶点和动态场景缓存。
    // v13：回退箱子静态化（entity atlas 纹理与方块 VBO 不兼容，紫色方块）；保留 GZIP+量化+视口剔除+邻居登记修复。
    // v12：箱子顶点静态化到独立 VBO，缓存追加 chestVertices 字段。
    // v11：保留 v10 的 GZIP + 顶点量化；箱子方块实体改回动态渲染，避免 chest atlas 被写进方块 VBO。
    // 升版本会让旧缓存一次性失效；之后 mod 版本号变化不再清缓存（token 已不含 mod 版本）。
    private static final int CACHE_FORMAT_VERSION = 17;
    private static final int CACHE_MAGIC = 0x51435033; // QCP3
    private static final String CACHE_DIR_NAME = "litematica-preview-cache";
    private static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    private static final String CACHE_INDEX_FILE_NAME = "cache-index.properties";
    private static final String CACHE_RENDER_MARKER = "quickcraft-model-mesh-v19-stable-path-content-resource-signature-dynamic-render-state-mc26.2";
    private static final int EXPAND_BUTTON_SIZE = 16;
    private static final int COMPAT_CLIPBOARD_MAX_DIMENSION = 4096;
    private static final int EMBEDDED_PREVIEW_DIMENSION = 1024;
    // 预算必须卡在构建阶段前面：顶点 packed 后仍会占用 CPU/GPU 大块连续内存。
    private static final int MAX_UPLOAD_VERTICES = 12_000_000;
    private static final int MAX_DYNAMIC_BLOCK_STATES = 300_000;
    private static final int MAX_DYNAMIC_BLOCK_ENTITIES = 32_768;
    private static final int MAX_DYNAMIC_ENTITIES = 8_192;
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;
    private static final float DEFAULT_SLANT_RADIANS = (float) Math.toRadians(32.0);
    private static final float MAX_PITCH_RADIANS = (float) Math.toRadians(85.0);
    private static final float PREVIEW_FIT_PADDING = 0.95F;
    private static final long NBT_READ_LIMIT_BYTES = 32L * 1024L * 1024L;
    private static final int VERTEX_BYTES = 44;
    // 图集 UV 保留 float32，避免 float16 截断后跨进相邻 sprite；lightmap 是两个 16-bit 分量组成的 packed int。
    private static final int QUANTIZED_VERTEX_BYTES = 32;
    private static final int MAX_QUANTIZED_LAYER_BYTES = MAX_UPLOAD_VERTICES * QUANTIZED_VERTEX_BYTES;
    private static final int CACHE_IO_CHUNK_BYTES = 1024 * 1024;
    private static final float PROGRESS_START = 0.02F;
    private static final float PROGRESS_MESHING_START = 0.10F;
    private static final float PROGRESS_MESHING_END = 0.80F;
    private static final float PROGRESS_CACHE_WRITE = 0.82F;
    private static final float PROGRESS_STATIC_CACHE_END = 0.93F;
    private static final float PROGRESS_BLOCK_STATES_CACHE_END = 0.95F;
    private static final float PROGRESS_BLOCK_ENTITIES_CACHE_END = 0.99F;
    private static final AtomicBoolean SPECIAL_RENDERER_REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean CACHE_DIRECTORY_READY = new AtomicBoolean();
    private static final Object CACHE_INDEX_LOCK = new Object();
    private static final Properties CACHE_INDEX = new Properties();
    private static final long SOURCE_CHECK_INTERVAL_MILLIS = 1_000L;
    @Nullable
    private static volatile Path currentCacheDirectory;

    private QuickLitematicaPreview3D() {
    }

    public static void registerSpecialRenderer() {
        if (SPECIAL_RENDERER_REGISTERED.compareAndSet(false, true)) {
            PictureInPictureRendererRegistry.register(context -> new PreviewGuiElementRenderer());
        }
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
        Minecraft.getInstance().setScreen(new QuickLitematicaPreview3DScreen(
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
            if (SHADER_API_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Iris shader state could not be queried; disabling QuickCraft 3D previews for this session", throwable);
            }
            return true;
        }
    }

    public static boolean prepare3DPreview() {
        if (!isShaderPackActive()) {
            return true;
        }
        if (!QuickCraftConfigs.shouldAutoDisableShadersFor3DPreview()) {
            return false;
        }

        try {
            // Iris 是可选依赖，反射稳定的 v0 API 可避免把它变成硬依赖。
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object config = apiClass.getMethod("getConfig").invoke(api);
            Class<?> configClass = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            configClass.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
            boolean disabled = !isShaderPackActive();
            if (disabled) {
                InfoUtils.printActionbarMessage("quickcraft.message.litematica.preview_3d.shader_auto_disabled");
            }
            return disabled;
        } catch (Throwable throwable) {
            if (SHADER_DISABLE_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Iris shaders could not be disabled before opening a QuickCraft 3D preview", throwable);
            }
            return false;
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

            if (mouseButton == 0 && this.showExpandButton && this.isExpandButtonHovered(mouseX, mouseY) && this.currentEntry != null) {
                DirectoryEntry entry = this.currentEntry;
                if (this.current == null && this.currentPath != null) {
                    boolean hasEmbeddedPreview = this.hasEmbeddedPreviewImage;
                    this.switchTo(this.currentPath, entry);
                    this.currentEntry = entry;
                    this.hasEmbeddedPreviewImage = hasEmbeddedPreview;
                }
                Minecraft.getInstance().gui.setScreen(new QuickLitematicaPreview3DScreen(this.owner, entry.getName(), this));
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
            Preview preview = this.current;
            Path target = this.currentPath;
            if (!this.canEditPreviewImage() || preview == null || target == null) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_unavailable"));
                return;
            }

            Path selected;
            try {
                selected = QuickLitematicaPreviewImageWriter.chooseImage(target);
            } catch (Throwable throwable) {
                LOGGER.error("Failed to open the preview image file picker", throwable);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            }
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

            Util.ioPool().execute(() -> {
                try {
                    QuickLitematicaPreviewImageWriter.removePreview(target);
                    preview.refreshCacheSourceHash();
                    this.finishPreviewWrite(preview, false, callback, Component.translatable(
                            "quickcraft.litematica.preview_3d.preview_remove_success", target.getFileName().toString()));
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to remove the Litematica preview image from {}", target, throwable);
                    this.failPreviewWrite(callback, throwable);
                }
            });
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
                    LOGGER.warn("Failed to refresh the Litematica preview image cache", throwable);
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

    private static final class Preview implements AutoCloseable {
        private final Path sourcePath;
        private final Path cachePath;
        private final Path tmpPath;
        private final String cacheSlot;
        private final String resourcePackSignature;
        private volatile long sourceSize;
        private volatile long sourceModifiedMillis;
        private final long startedAtNanos = System.nanoTime();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Map<LayerKey, LayerBuffer> layerBuffers = new EnumMap<>(LayerKey.class);
        private final Projection snapshotProjection = new Projection();
        private final ProjectionMatrixBuffer snapshotProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft PNG projection");
        private final ProjectionMatrixBuffer dynamicProjectionBuffer = new ProjectionMatrixBuffer("QuickCraft dynamic preview projection");
        @Nullable
        private GpuBuffer previewLightingBuffer;
        private volatile MeshData meshData;
        private volatile float progress;
        private volatile State state = State.LOADING;
        @Nullable
        private volatile Future<?> future;
        @Nullable
        private PreparedDynamicScene preparedDynamicScene;
        @Nullable
        private StagedVertexBuffer dynamicStagedVertexBuffer;
        @Nullable
        private FeatureRenderDispatcher dynamicDispatcher;
        @Nullable
        private FeatureRenderDispatcher.PreparedFrame dynamicFrame;
        private boolean dynamicBufferFallback;
        private boolean dynamicStateFallback;
        private boolean uploadScheduled;
        private final AtomicBoolean snapshotInProgress = new AtomicBoolean();

        private Preview(Path sourcePath, Path cachePath, Path tmpPath,
                        String cacheSlot, String resourcePackSignature) {
            this.sourcePath = sourcePath;
            this.cachePath = cachePath;
            this.tmpPath = tmpPath;
            this.cacheSlot = cacheSlot;
            this.resourcePackSignature = resourcePackSignature;
            this.captureSourceStamp();
        }

        private static Preview create(DirectoryEntry entry) {
            Path sourcePath = entry.getFullPath().toAbsolutePath().normalize();
            String cacheSlot = cacheKey(sourcePath);
            Path cachePath = cacheDirectory().resolve(cacheSlot + ".qcp3d");
            Preview preview = new Preview(sourcePath, cachePath,
                    cachePath.resolveSibling(cachePath.getFileName() + ".tmp"),
                    cacheSlot, currentResourcePackSignature());
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
            Preview preview = new Preview(Path.of(safeName + ".litematic"), transientPath, transientPath, "", "");
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

        private void render(GuiGraphicsExtractor context, int x, int y, int size, DragState drag) {
            if (this.state == State.READY && this.meshData != null) {
                this.uploadIfNeeded();
                if (!this.layerBuffers.isEmpty() || this.meshData.hasDynamicContent() || this.meshData.vertexCount() == 0) {
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
            if (!this.layerBuffers.isEmpty() || this.uploadScheduled || this.meshData == null) {
                return;
            }

            this.uploadScheduled = true;
            MeshData data = this.meshData;
            if (this.cancelled.get()) {
                return;
            }

            EnumMap<LayerKey, LayerBuffer> uploaded = new EnumMap<>(LayerKey.class);
            try {
                if (!data.withinBudget()) {
                    this.markTooLarge(data);
                    return;
                }

                for (LayerMesh layerMesh : data.layers()) {
                    if (layerMesh.vertexCount() == 0) {
                        continue;
                    }

                    LayerBuffer buffer = uploadLayer(layerMesh);
                    if (buffer != null) {
                        uploaded.put(layerMesh.layer(), buffer);
                    }
                }

                if (this.cancelled.get()) {
                    uploaded.values().forEach(LayerBuffer::close);
                    return;
                }

                this.closeBuffers();
                this.layerBuffers.putAll(uploaded);
                data.releaseStaticVertices();
            } catch (Throwable ignored) {
                uploaded.values().forEach(LayerBuffer::close);
                this.releaseMeshData();
                this.state = State.TOO_LARGE;
                this.progress = 1.0F;
            }
        }

        private void markTooLarge(@Nullable MeshData data) {
            this.closeBuffers();
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
            this.state = State.TOO_LARGE;
            this.progress = 1.0F;
            deleteTmpQuietly(this.tmpPath);
            deleteQuietly(this.cachePath);
        }

        private void releaseMeshData() {
            this.closeBuffers();
            MeshData data = this.meshData;
            if (data != null) {
                data.closeDynamic();
            }
            this.meshData = null;
        }

        @Nullable
        private static LayerBuffer uploadLayer(LayerMesh layerMesh) {
            int vertexCount = layerMesh.vertexCount();
            int allocatorSize = allocatorSize(vertexCount);
            ByteBufferBuilder allocator = new ByteBufferBuilder(allocatorSize);
            try {
                RenderType renderLayer = layerMesh.layer().renderLayer();
                BufferBuilder builder = new BufferBuilder(allocator, renderLayer.primitiveTopology(), renderLayer.format());
                CacheFile.decodeQuantizedToBuilder(layerMesh.quantizedVertices(), builder);

                com.mojang.blaze3d.vertex.MeshData built = builder.build();
                if (built == null) {
                    return null;
                }

                try {
                    if (layerMesh.layer() == LayerKey.TRANSLUCENT) {
                        built.sortQuads(allocator, VertexSorting.byDistance(0.0F, 0.0F, 1000.0F));
                    }

                    var drawParameters = built.drawState();
                    GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "QuickCraft preview vertices",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            built.vertexBuffer()
                    );
                    boolean customIndexBuffer = built.indexBuffer() != null;
                    GpuBuffer indexBuffer = customIndexBuffer
                            ? RenderSystem.getDevice().createBuffer(
                                    () -> "QuickCraft preview indices",
                                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                    built.indexBuffer()
                            )
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).getBuffer(drawParameters.indexCount());
                    var indexType = customIndexBuffer
                            ? drawParameters.indexType()
                            : RenderSystem.getSequentialBuffer(drawParameters.primitiveTopology()).type();
                    return new LayerBuffer(vertexBuffer, indexBuffer, drawParameters.indexCount(), indexType, customIndexBuffer);
                } finally {
                    built.close();
                }
            } finally {
                allocator.close();
            }
        }

        private static int allocatorSize(int vertexCount) {
            long bytes = Math.max(256L, (long) vertexCount * VERTEX_BYTES);
            return (int) Math.min(Integer.MAX_VALUE - 8L, bytes);
        }

        private void drawSpecial(PreviewGuiElement element, PoseStack matrices) {
            MeshData data = this.meshData;
            if (data == null || this.cancelled.get()) {
                return;
            }

            var previousLights = RenderSystem.getShaderLights();
            var colorTarget = Objects.requireNonNull(RenderSystem.outputColorTextureOverride);
            RenderSystem.backupProjectionMatrix();
            try {
                this.setupPreviewProjection(colorTarget.getWidth(0), colorTarget.getHeight(0), element.dragScale());
                matrices.pushPose();
                try {
                    // 26.1+ PIP 使用倒置 Y 投影并预先翻转 Z；这里恢复预览使用的世界坐标方向和面朝向。
                    matrices.scale(1.0F, -1.0F, -1.0F);
                    matrices.translate(element.dragX(), -element.dragY(), 0.0F);
                    matrices.mulPose(Axis.XP.rotation(element.pitch()));
                    matrices.mulPose(Axis.YP.rotation((float) element.angle()));
                    float scale = data.scaleFactor(element.size(), element.size()) * element.size() * 0.5F * element.dragScale();
                    matrices.scale(scale, scale, scale);
                    matrices.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);
                    Matrix4f dynamicModelView = new Matrix4f(matrices.last().pose());
                    this.applyLight(dynamicModelView);
                    this.drawBuffers(dynamicModelView);
                    if (data.hasDynamicContent()) {
                        this.prepareDynamicFrame(data);
                        if (this.dynamicFrame != null) {
                            this.drawDynamicFrame(dynamicModelView);
                        } else {
                            this.prepareDynamicStates(data);
                            SubmitNodeStorage fallbackNodes = new SubmitNodeStorage();
                            if (this.preparedDynamicScene != null) {
                                this.drawPreparedDynamic(this.preparedDynamicScene, dynamicModelView, element.size(), fallbackNodes);
                            } else {
                                this.drawDynamic(data, dynamicModelView, element.size(), fallbackNodes);
                            }
                            Minecraft.getInstance().gameRenderer.featureRenderDispatcher().renderAllFeatures(fallbackNodes);
                        }
                    }
                } finally {
                    matrices.popPose();
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.setShaderLights(previousLights);
            }
        }

        private void setupPreviewProjection(int width, int height, float zoom) {
            float depthRange = Math.max(1000.0F, width * Math.max(1.0F, zoom));
            this.snapshotProjection.setupOrtho(-depthRange, depthRange, width, height, true);
            RenderSystem.setProjectionMatrix(this.snapshotProjectionBuffer.getBuffer(this.snapshotProjection), ProjectionType.ORTHOGRAPHIC);
        }

        // 1.21.6+ 的地形明暗已烘焙进顶点颜色；独立 UBO 只修正动态方块实体和实体，且不污染原版全局光照。
        private void applyLight(Matrix4f viewMatrix) {
            Matrix4f lightTransform = new Matrix4f(viewMatrix);
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

        private void drawBuffers(Matrix4f modelView) {
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                LayerBuffer buffer = this.layerBuffers.get(layer);
                if (buffer != null) {
                    drawLayerBuffer(layer, buffer, modelView);
                }
            }
        }

        private static void drawLayerBuffer(LayerKey layer, LayerBuffer buffer, Matrix4f modelView) {
            RenderType renderLayer = layer.renderLayer();
            Matrix4fStack renderStack = RenderSystem.getModelViewStack();
            renderStack.pushMatrix();
            try {
                renderStack.set(modelView);
                renderLayer.prepare().drawFromBuffer(
                        buffer.vertexBuffer(),
                        buffer.indexBuffer(),
                        buffer.indexType(),
                        0,
                        0,
                        buffer.indexCount()
                );
            } finally {
                renderStack.popMatrix();
            }
        }

        private void prepareDynamicFrame(MeshData data) {
            if (this.dynamicFrame != null || this.dynamicBufferFallback || !data.hasDynamicContent()) {
                return;
            }

            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.dynamicBufferFallback = true;
                this.preparedDynamicScene = PreparedDynamicScene.EMPTY;
                data.closeDynamic();
                return;
            }

            StagedVertexBuffer stagedBuffer = null;
            FeatureRenderDispatcher dispatcher = null;
            FeatureRenderDispatcher.PreparedFrame frame = null;
            try {
                Minecraft client = Minecraft.getInstance();
                SubmitNodeStorage submitNodes = new SubmitNodeStorage();
                PoseStack matrices = new PoseStack();
                CameraRenderState cameraState = new CameraRenderState();

                scene.blockEntities().forEach((pos, entity) -> {
                    matrices.pushPose();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entity, matrices, submitNodes, cameraState);
                    } catch (Throwable ignored) {
                    } finally {
                        matrices.popPose();
                    }
                });

                scene.entities().forEach(renderedEntity -> {
                    try {
                        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(renderedEntity.entity(), 0.0F);
                        renderState.lightCoords = renderedEntity.light();
                        renderState.distanceToCameraSq = 0.0D;
                        client.getEntityRenderDispatcher().submit(
                                renderState,
                                cameraState,
                                renderedEntity.x(),
                                renderedEntity.y(),
                                renderedEntity.z(),
                                matrices,
                                submitNodes
                        );
                    } catch (Throwable ignored) {
                    }
                });

                stagedBuffer = new StagedVertexBuffer(() -> "QuickCraft preview dynamic", 4 * 1024 * 1024);
                dispatcher = new FeatureRenderDispatcher(
                        client.gameRenderer.renderBuffers(),
                        client.getModelManager(),
                        client.getAtlasManager(),
                        client.font,
                        client.gameRenderer.gameRenderState()
                );
                ((LitematicaFeatureRenderDispatcherAccessor) (Object) dispatcher)
                        .quickcraft$setStagedVertexBuffer(stagedBuffer);

                Matrix4fStack renderStack = RenderSystem.getModelViewStack();
                renderStack.pushMatrix();
                try {
                    renderStack.identity();
                    frame = dispatcher.prepareFrame(submitNodes);
                } finally {
                    renderStack.popMatrix();
                }

                LitematicaStagedVertexBufferAccessor stagedAccessor =
                        (LitematicaStagedVertexBufferAccessor) (Object) stagedBuffer;
                long vertexBytes = stagedAccessor.quickcraft$getCurrentVertexBuffer() == null
                        ? 0L
                        : stagedAccessor.quickcraft$getCurrentVertexBuffer().size();
                long indexBytes = stagedAccessor.quickcraft$getCurrentIndexBuffer() == null
                        ? 0L
                        : stagedAccessor.quickcraft$getCurrentIndexBuffer().size();
                if (vertexBytes + indexBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                    throw new IllegalStateException("Dynamic preview buffer exceeds limit");
                }

                // 顶点已上传到长期 GPU 缓冲，释放可能增长到上百 MB 的 CPU 暂存区。
                stagedAccessor.quickcraft$getStagingBuffer().close();
                data.closeDynamic();
                this.dynamicStagedVertexBuffer = stagedBuffer;
                this.dynamicDispatcher = dispatcher;
                this.dynamicFrame = frame;
            } catch (Throwable ignored) {
                closeQuietly(frame);
                closeQuietly(dispatcher);
                closeQuietly(stagedBuffer);
                this.dynamicBufferFallback = true;
                this.prepareDynamicStates(data);
            }
        }

        private void drawDynamicFrame(Matrix4f modelView) {
            FeatureRenderDispatcher.PreparedFrame frame = this.dynamicFrame;
            if (frame == null) {
                return;
            }

            Matrix4f projection = this.snapshotProjection.getMatrix(new Matrix4f()).mul(modelView);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(this.dynamicProjectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            try {
                frame.executeSolid();
                frame.executeTranslucent();
                frame.executeTranslucentAfterTerrain();
                frame.executeAlwaysOnTop();
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }

        private void prepareDynamicStates(MeshData data) {
            if (this.preparedDynamicScene != null || this.dynamicStateFallback || !data.hasDynamicContent()) {
                return;
            }

            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                this.preparedDynamicScene = PreparedDynamicScene.EMPTY;
                data.closeDynamic();
                return;
            }

            try {
                Minecraft client = Minecraft.getInstance();
                List<PreparedBlockEntity> blockEntities = new ArrayList<>();
                scene.blockEntities().forEach((pos, entity) -> {
                    try {
                        PreparedBlockEntity prepared = prepareBlockEntity(client, pos, entity);
                        if (prepared != null) blockEntities.add(prepared);
                    } catch (Throwable ignored) {
                    }
                });

                List<PreparedEntity> entities = new ArrayList<>();
                scene.entities().forEach(renderedEntity -> {
                    try {
                        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(renderedEntity.entity(), 0.0F);
                        renderState.lightCoords = renderedEntity.light();
                        renderState.distanceToCameraSq = 0.0D;
                        entities.add(new PreparedEntity(renderState, renderedEntity.x(), renderedEntity.y(), renderedEntity.z()));
                    } catch (Throwable ignored) {
                    }
                });

                this.preparedDynamicScene = new PreparedDynamicScene(List.copyOf(blockEntities), List.copyOf(entities));
                data.closeDynamic();
            } catch (Throwable ignored) {
                this.preparedDynamicScene = null;
                this.dynamicStateFallback = true;
            }
        }

        private void drawPreparedDynamic(
                PreparedDynamicScene scene,
                Matrix4f modelView,
                int viewSize,
                SubmitNodeCollector submitNodes
        ) {
            if (scene.isEmpty()) return;
            Minecraft client = Minecraft.getInstance();
            ViewportCuller culler = ViewportCuller.forPip(modelView, viewSize);
            PoseStack matrices = new PoseStack();
            matrices.mulPose(modelView);
            CameraRenderState cameraState = new CameraRenderState();

            scene.blockEntities().forEach(prepared -> {
                BlockPos pos = prepared.pos();
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) return;
                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    submitPreparedBlockEntity(prepared, matrices, submitNodes, cameraState);
                } catch (Throwable ignored) {
                } finally {
                    matrices.popPose();
                }
            });

            scene.entities().forEach(prepared -> {
                if (culler.isOutside((float) prepared.x(), (float) prepared.y(), (float) prepared.z())) return;
                try {
                    client.getEntityRenderDispatcher().submit(
                            prepared.state(), cameraState, prepared.x(), prepared.y(), prepared.z(), matrices, submitNodes
                    );
                } catch (Throwable ignored) {
                }
            });
        }

        private void drawDynamic(MeshData data, Matrix4f modelView, int viewSize, SubmitNodeCollector submitNodes) {
            DynamicScene scene = data.dynamicScene();
            if (scene.isEmpty()) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            ViewportCuller culler = ViewportCuller.forPip(modelView, viewSize);
            PoseStack matrices = new PoseStack();
            matrices.mulPose(modelView);
            CameraRenderState cameraState = new CameraRenderState();

            scene.blockEntities().forEach((pos, entity) -> {
                if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                    return;
                }

                matrices.pushPose();
                try {
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderBlockEntity(client, entity, matrices, submitNodes, cameraState);
                } catch (Throwable ignored) {
                } finally {
                    matrices.popPose();
                }
            });

            scene.entities().forEach(renderedEntity -> {
                if (culler.isOutside((float) renderedEntity.x(), (float) renderedEntity.y(), (float) renderedEntity.z())) {
                    return;
                }

                try {
                    EntityRenderState renderState = client.getEntityRenderDispatcher()
                            .extractEntity(renderedEntity.entity(), 0.0F);
                    renderState.lightCoords = renderedEntity.light();
                    renderState.distanceToCameraSq = 0.0D;
                    client.getEntityRenderDispatcher().submit(
                            renderState,
                            cameraState,
                            renderedEntity.x(),
                            renderedEntity.y(),
                            renderedEntity.z(),
                            matrices,
                            submitNodes
                    );
                } catch (Throwable ignored) {
                }
            });

        }

        private static <T extends BlockEntity, S extends BlockEntityRenderState> void renderBlockEntity(
                Minecraft client,
                T entity,
                PoseStack matrices,
                SubmitNodeCollector queue,
                CameraRenderState cameraState
        ) {
            BlockEntityRenderer<T, S> renderer = client.getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return;
            }

            S renderState = renderer.createRenderState();
            // 预览对象位于离屏假世界，不能使用真实玩家相机做方块实体距离判断和状态提取。
            renderer.extractRenderState(entity, renderState, 0.0F, Vec3.ZERO, null);
            renderState.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
            renderer.submit(renderState, matrices, queue, cameraState);
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

        private float displayProgress() {
            if (this.progress >= PROGRESS_MESHING_START || this.state == State.FAILED || this.state == State.TOO_LARGE) {
                return this.progress;
            }

            // Litematica/DataFixer 读取和 GZIP 缓存解压没有可观测的完成量；只在这段等待里平滑补到扫描开始前。
            float elapsedSeconds = (System.nanoTime() - this.startedAtNanos) / 1_000_000_000.0F;
            float readingProgress = Math.min(PROGRESS_MESHING_START - 0.01F, PROGRESS_START + elapsedSeconds * 0.02F);
            return Math.max(this.progress, readingProgress);
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
            this.closeBuffers();
            this.closeDynamicFrame();
            if (this.previewLightingBuffer != null && !this.previewLightingBuffer.isClosed()) {
                this.previewLightingBuffer.close();
            }
            this.preparedDynamicScene = null;
            this.snapshotProjectionBuffer.close();
            this.dynamicProjectionBuffer.close();
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

        private void refreshCacheSourceHash() {
            try {
                String sourceHash = hashFile(this.sourcePath);
                writeCacheIndexEntry(this.cacheSlot, this.sourcePath, sourceHash, this.resourcePackSignature);
                this.captureSourceStamp();
            } catch (IOException e) {
                LOGGER.warn("Failed to refresh the 3D preview cache signature for {}", this.sourcePath, e);
            }
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
                Minecraft client = Minecraft.getInstance();
                SubmitNodeStorage queue = new SubmitNodeStorage();
                CameraRenderState cameraState = new CameraRenderState();
                PoseStack matrices = new PoseStack();
                try (FeatureRenderDispatcher dispatcher = new FeatureRenderDispatcher(
                        queue,
                        client.getModelManager(),
                        collector,
                        client.getAtlasManager(),
                        client.renderBuffers().outlineBufferSource(),
                        collector,
                        client.font,
                        client.gameRenderer.getGameRenderState()
                )) {
                    scene.blockEntities().forEach((pos, entity) -> {
                        matrices.pushPose();
                        try {
                            matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                            renderBlockEntity(client, entity, matrices, queue, cameraState);
                        } catch (DynamicBufferTooLargeException e) {
                            throw e;
                        } catch (Throwable ignored) {
                        } finally {
                            matrices.popPose();
                        }
                    });

                    scene.entities().forEach(renderedEntity -> {
                        try {
                            EntityRenderState renderState = client.getEntityRenderDispatcher()
                                    .extractEntity(renderedEntity.entity(), 0.0F);
                            renderState.lightCoords = renderedEntity.light();
                            renderState.distanceToCameraSq = 0.0D;
                            client.getEntityRenderDispatcher().submit(
                                    renderState,
                                    cameraState,
                                    renderedEntity.x(),
                                    renderedEntity.y(),
                                    renderedEntity.z(),
                                    matrices,
                                    queue
                            );
                        } catch (DynamicBufferTooLargeException e) {
                            throw e;
                        } catch (Throwable ignored) {
                        }
                    });
                    dispatcher.renderAllFeatures();
                }

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

        private void drawDynamicBuffers(Matrix4f modelView) {
            for (DynamicLayerBuffer layerBuffer : this.dynamicBuffers) {
                drawLayerBuffer(layerBuffer.layer(), layerBuffer.buffer(), modelView);
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
            this.prepareDynamicFrame(data);
            if (this.dynamicFrame == null) {
                this.prepareDynamicStates(data);
            }
            if (data.vertexCount() > 0 && this.layerBuffers.isEmpty()) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                return;
            }
            if (!this.snapshotInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                return;
            }

            Path outputPath;
            RenderTarget framebuffer;
            try {
                Files.createDirectories(outputDirectory);
                outputPath = this.nextOutputPath(outputDirectory, resolution);
                framebuffer = new TextureTarget("QuickCraft PNG export", resolution, resolution, true, GpuFormat.RGBA8_UNORM);
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
            } catch (Throwable ignored) {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
                return;
            }

            boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
            copySnapshot(framebuffer, keepBackgroundOpaque, image -> Util.ioPool().execute(() -> {
                try {
                    image.writeToFile(outputPath);
                    Minecraft.getInstance().execute(() -> callback.accept(Component.translatable(
                            "quickcraft.litematica.preview_3d.export_success", outputPath.getFileName().toString()
                    )));
                } catch (Exception ignored) {
                    Minecraft.getInstance().execute(() -> callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed")));
                } finally {
                    image.close();
                    this.snapshotInProgress.set(false);
                }
            }), throwable -> {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_failed"));
            });
        }

        private void copyImage(int resolution, int backgroundColor, DragState drag, Consumer<Component> callback) {
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
            this.prepareDynamicBuffers(data);
            if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_dynamic_failed"));
                return;
            }
            if (data.vertexCount() > 0 && this.layerBuffers.isEmpty()) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                return;
            }
            if (!this.snapshotInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                return;
            }

            RenderTarget framebuffer;
            try {
                framebuffer = new TextureTarget("QuickCraft clipboard snapshot", resolution, resolution, true);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        Objects.requireNonNull(framebuffer.getColorTexture()),
                        backgroundColor,
                        Objects.requireNonNull(framebuffer.getDepthTexture()),
                        1.0D
                );
                this.renderSnapshot(framebuffer, data, drag);
            } catch (Throwable ignored) {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
                return;
            }

            boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
            copySnapshot(framebuffer, keepBackgroundOpaque, image -> Util.ioPool().execute(() -> {
                try {
                    copyToWindowsClipboard(image);
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
            }), throwable -> {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.copy_failed"));
            });
        }

        private void captureSnapshot(int resolution, int backgroundColor, DragState drag,
                                     Consumer<Component> callback, Consumer<NativeImage> imageCallback) {
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
            this.prepareDynamicBuffers(data);
            if (data.hasDynamicContent() && !this.dynamicBuffersReady) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.export_dynamic_failed"));
                return;
            }
            if (data.vertexCount() > 0 && this.layerBuffers.isEmpty()) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            }
            if (!this.snapshotInProgress.compareAndSet(false, true)) {
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.exporting"));
                return;
            }

            RenderTarget framebuffer;
            try {
                framebuffer = new TextureTarget("QuickCraft embedded preview", resolution, resolution, true);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        Objects.requireNonNull(framebuffer.getColorTexture()),
                        backgroundColor,
                        Objects.requireNonNull(framebuffer.getDepthTexture()),
                        1.0D
                );
                this.renderSnapshot(framebuffer, data, drag);
            } catch (Throwable throwable) {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
                return;
            }

            boolean keepBackgroundOpaque = ((backgroundColor >>> 24) & 0xFF) == 0xFF;
            copySnapshot(framebuffer, keepBackgroundOpaque, imageCallback, throwable -> {
                this.snapshotInProgress.set(false);
                callback.accept(Component.translatable("quickcraft.litematica.preview_3d.preview_write_failed"));
            });
        }

        private void renderSnapshot(RenderTarget framebuffer, MeshData data, DragState drag) {
            var previousColorTarget = RenderSystem.outputColorTextureOverride;
            var previousDepthTarget = RenderSystem.outputDepthTextureOverride;
            var previousLights = RenderSystem.getShaderLights();
            RenderSystem.outputColorTextureOverride = framebuffer.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = framebuffer.getDepthTextureView();
            RenderSystem.backupProjectionMatrix();
            this.setupPreviewProjection(framebuffer.width, framebuffer.height, drag.scale);

            PoseStack matrices = new PoseStack();
            try {
                matrices.translate(framebuffer.width / 2.0F, framebuffer.height / 2.0F, 0.0F);
                matrices.scale(1.0F, -1.0F, 1.0F);
                float viewportSize = Math.max(1, drag.size);
                matrices.translate(drag.dx * framebuffer.width / viewportSize, -drag.dy * framebuffer.height / viewportSize, 0.0F);
                matrices.mulPose(Axis.XP.rotation(drag.pitch));
                matrices.mulPose(Axis.YP.rotation((float) drag.angle));
                double diagonal = Math.sqrt(
                        (double) data.sizeX() * data.sizeX()
                                + (double) data.sizeY() * data.sizeY()
                                + (double) data.sizeZ() * data.sizeZ()
                );
                float scale = (float) (PREVIEW_FIT_PADDING * framebuffer.width / Math.max(1.0, diagonal)) * drag.scale;
                matrices.scale(scale, scale, scale);
                matrices.translate(-data.sizeX() / 2.0F, -data.sizeY() / 2.0F, -data.sizeZ() / 2.0F);
                Matrix4f modelView = new Matrix4f(matrices.last().pose());
                this.applyLight(modelView);
                this.drawBuffers(modelView);
                if (data.hasDynamicContent()) {
                    if (this.dynamicFrame != null) {
                        this.drawDynamicFrame(modelView);
                    } else {
                        SubmitNodeStorage submitNodes = new SubmitNodeStorage();
                        if (this.preparedDynamicScene != null) {
                            this.drawPreparedDynamic(this.preparedDynamicScene, modelView, framebuffer.width, submitNodes);
                        } else {
                            this.drawDynamic(data, modelView, framebuffer.width, submitNodes);
                        }
                        Minecraft.getInstance().gameRenderer.featureRenderDispatcher().renderAllFeatures(submitNodes);
                    }
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.outputColorTextureOverride = previousColorTarget;
                RenderSystem.outputDepthTextureOverride = previousDepthTarget;
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
            device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
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
                        if (image != null) image.close();
                        errorCallback.accept(throwable);
                    }
                });
            }, 0);
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

        private Path nextOutputPath(Path outputDirectory, int resolution) {
            String fileName = this.sourcePath.getFileName().toString();
            int extension = fileName.lastIndexOf('.');
            String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
            baseName = baseName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
            if (baseName.isBlank()) baseName = "render";
            String stem = baseName + "_" + Util.getFilenameFormattedDateTime() + "_" + resolution + "x" + resolution;
            Path outputPath = outputDirectory.resolve(stem + ".png");
            int suffix = 2;
            while (Files.exists(outputPath)) {
                outputPath = outputDirectory.resolve(stem + "_" + suffix++ + ".png");
            }
            return outputPath;
        }

        private void closeBuffers() {
            this.layerBuffers.values().forEach(LayerBuffer::close);
            this.layerBuffers.clear();
        }

        private void closeDynamicFrame() {
            FeatureRenderDispatcher.PreparedFrame frame = this.dynamicFrame;
            FeatureRenderDispatcher dispatcher = this.dynamicDispatcher;
            StagedVertexBuffer stagedBuffer = this.dynamicStagedVertexBuffer;
            this.dynamicFrame = null;
            this.dynamicDispatcher = null;
            this.dynamicStagedVertexBuffer = null;
            closeQuietly(frame);
            closeQuietly(dispatcher);
            closeQuietly(stagedBuffer);
        }

        private static void closeQuietly(@Nullable AutoCloseable resource) {
            if (resource == null) {
                return;
            }
            try {
                resource.close();
            } catch (Exception ignored) {
            }
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

    private record PreparedDynamicScene(List<PreparedBlockEntity> blockEntities, List<PreparedEntity> entities) {
        private static final PreparedDynamicScene EMPTY = new PreparedDynamicScene(List.of(), List.of());

        private boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }
    }

    private record PreparedBlockEntity(BlockPos pos, BlockEntityRenderer<?, ?> renderer, BlockEntityRenderState state) {
    }

    private record PreparedEntity(EntityRenderState state, double x, double y, double z) {
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PreparedBlockEntity prepareBlockEntity(Minecraft client, BlockPos pos, BlockEntity entity) {
        BlockEntityRenderer renderer = client.getBlockEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) return null;
        BlockEntityRenderState state = (BlockEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0.0F, Vec3.ZERO, null);
        state.lightCoords = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
        return new PreparedBlockEntity(pos, renderer, state);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void submitPreparedBlockEntity(
            PreparedBlockEntity prepared,
            PoseStack matrices,
            SubmitNodeCollector submitNodes,
            CameraRenderState cameraState
    ) {
        ((BlockEntityRenderer) prepared.renderer()).submit(prepared.state(), matrices, submitNodes, cameraState);
    }

    private record PreviewGuiElement(
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

    private static final class PreviewGuiElementRenderer extends PictureInPictureRenderer<PreviewGuiElement> {
        @Override
        public Class<PreviewGuiElement> getRenderStateClass() {
            return PreviewGuiElement.class;
        }

        @Override
        protected void renderToTexture(PreviewGuiElement element, PoseStack matrices, SubmitNodeCollector submitNodes) {
            element.preview().drawSpecial(element, matrices);
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

    private record LayerBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount,
                               com.mojang.blaze3d.IndexType indexType,
                               boolean ownsIndexBuffer) implements AutoCloseable {
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
        int screenWidth = client.gui.screen() == null ? client.getWindow().getGuiScaledWidth() : client.gui.screen().width;
        int screenHeight = client.gui.screen() == null ? client.getWindow().getGuiScaledHeight() : client.gui.screen().height;
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
            updateDigest(digest, SharedConstants.getCurrentVersion().name());
            Minecraft.getInstance().getResourcePackRepository().getSelectedIds()
                    .forEach(id -> updateDigest(digest, id));
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

            Minecraft client = Minecraft.getInstance();
            Path runDirectory = client.gameDirectory.toPath();
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

    private static void writeCacheIndexEntry(String slot, Path sourcePath, String sourceHash,
                                             String resourceSignature) throws IOException {
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
            Minecraft client = Minecraft.getInstance();
            if (client.level == null) {
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

            ModelBlockRenderer blockRenderer = new ModelBlockRenderer(true, true, client.getBlockColors());
            FluidRenderer fluidRenderer = new FluidRenderer(client.getModelManager().getFluidStateModelSet());

            for (String regionName : schematic.getAreas().keySet()) {
                throwIfCancelled(cancelled);
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                Box area = schematic.getAreas().get(regionName);
                if (container == null || area == null) {
                    continue;
                }

                RegionBlockView view = new RegionBlockView(container, area);
                RegionBounds regionBounds = RegionBounds.from(area);
                Map<BlockPos, CompoundTag> schematicBlockEntities = schematic.getBlockEntityMapForRegion(regionName);
                recordEntities(blockStates, entities, view, schematic, regionName, area, bounds, cancelled);

                for (BlockPos pos : BlockPos.betweenClosed(regionBounds.min(), regionBounds.max())) {
                    throwIfCancelled(cancelled);
                    BlockState state = view.getBlockState(pos);
                    if (!state.isAir()) {
                        BlockPos renderPos = pos.subtract(bounds.min());
                        recordBlockEntity(blockStates, blockEntities, blockEntityRendererCache, view, state, schematicBlockEntities, pos, renderPos, bounds);
                        renderFluidIfPresent(collector, fluidRenderer, view, state, pos, renderPos);
                        renderBlockModel(collector, blockRenderer, view, state, pos, renderPos);
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

        private static void recordBlockEntity(
                Map<BlockPos, BlockStateData> blockStates,
                List<BlockEntityData> blockEntities,
                Map<BlockState, Boolean> blockEntityRendererCache,
                RegionBlockView view,
                BlockState state,
                @Nullable Map<BlockPos, CompoundTag> schematicBlockEntities,
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

            CompoundTag nbt = schematicBlockEntities == null
                    ? new CompoundTag()
                    : schematicBlockEntities.getOrDefault(schematicPos.subtract(view.bounds.min()), new CompoundTag());
            CompoundTag entityNbt = sanitizeBlockEntityNbt(nbt);
            entityNbt.putInt("x", renderPos.getX());
            entityNbt.putInt("y", renderPos.getY());
            entityNbt.putInt("z", renderPos.getZ());
            blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), NbtUtils.writeBlockState(state), entityNbt));
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

        private static CompoundTag sanitizeBlockEntityNbt(CompoundTag nbt) {
            CompoundTag sanitized = nbt.copy();
            // 3D 预览只需要容器外观，不需要把箱子/潜影盒内部物品也带进缓存和动态渲染。
            sanitized.remove("Items");
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
                double x = info.posVec().x + regionOrigin.getX() - bounds.min().getX();
                double y = info.posVec().y + regionOrigin.getY() - bounds.min().getY();
                double z = info.posVec().z + regionOrigin.getZ() - bounds.min().getZ();
                entities.add(new EntityData(x, y, z, copyEntityNbtAt(info.nbt(), x, y, z)));
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
                    layer -> new FluidVertexConsumer(collector.consumerFor(layer), transform),
                    state,
                    fluidState
            );
        }

        private static void renderBlockModel(
                MeshCollector collector,
                ModelBlockRenderer blockRenderer,
                RegionBlockView view,
                BlockState state,
                BlockPos pos,
                BlockPos renderPos
        ) {
            if (state.getRenderShape() != RenderShape.MODEL) {
                return;
            }

            blockRenderer.tesselateBlock(
                    (x, y, z, quad, instance) -> collector.consumerFor(quad.materialInfo().layer())
                            .putBlockBakedQuad(x, y, z, quad, instance),
                    renderPos.getX(),
                    renderPos.getY(),
                    renderPos.getZ(),
                    view,
                    pos,
                    state,
                    Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state),
                    state.getSeed(pos)
            );
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
        };

        private static final LayerKey[] DRAW_ORDER = {SOLID, CUTOUT_MIPPED, CUTOUT, TRIPWIRE, TRANSLUCENT};
        private final int id;

        LayerKey(int id) {
            this.id = id;
        }

        abstract RenderType renderLayer();

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

        private VertexConsumer consumerFor(RenderType renderLayer) {
            LayerKey layer = LayerKey.from(renderLayer);
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        private VertexConsumer consumerFor(ChunkSectionLayer renderLayer) {
            LayerKey layer = LayerKey.from(renderLayer);
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        private void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            if (this.vertexCount >= MAX_UPLOAD_VERTICES) {
                throw new PreviewTooLargeException();
            }

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
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

        private boolean isEmpty() {
            return this.position == 0;
        }

        private void add(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            this.ensureCapacity(this.position + QUANTIZED_VERTEX_BYTES);
            this.writeInt(Float.floatToIntBits(x));
            this.writeInt(Float.floatToIntBits(y));
            this.writeInt(Float.floatToIntBits(z));
            this.writeInt(argb);
            this.writeInt(Float.floatToIntBits(u));
            this.writeInt(Float.floatToIntBits(v));
            this.writeShort((short) overlay);
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

    private static HolderGetter<Block> blockLookup(RegistryAccess registryManager) {
        return registryManager.lookupOrThrow(Registries.BLOCK);
    }

    private record BlockStateData(int x, int y, int z, CompoundTag stateNbt) {
        private BlockState state(RegistryAccess registryManager) {
            return NbtUtils.readBlockState(blockLookup(registryManager), this.stateNbt);
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

        @Nullable
        private LayerMesh layer(LayerKey key) {
            for (LayerMesh layer : this.layers) {
                if (layer.layer() == key) {
                    return layer;
                }
            }
            return null;
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

    private record EntityData(double x, double y, double z, CompoundTag entityNbt) {
        @Nullable
        private RenderedEntity instantiate(DummyWorld world) {
            try {
                Entity entity = EntityUtils.createEntityAndPassengersFromNBT(this.entityNbt.copy(), world);
                if (entity == null) {
                    return null;
                }

                entity.setPos(this.x, this.y, this.z);
                int light = Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(entity, 0.0F);
                return new RenderedEntity(entity, this.x, this.y, this.z, light);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private record RenderedEntity(Entity entity, double x, double y, double z, int light) {
    }

    private record BlockEntityData(int x, int y, int z, CompoundTag stateNbt, CompoundTag entityNbt) {
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
                    return null;
                }

                if (!this.entityNbt.isEmpty()) {
                    blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), this.entityNbt.copy()));
                }
                blockEntity.setLevel(world);
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

        private static ViewportCuller forPip(Matrix4f modelView, int viewSize) {
            int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
            int physicalSize = Math.max(1, viewSize * guiScale);
            Projection projection = new Projection();
            projection.setupOrtho(-1000.0F, 1000.0F, physicalSize, physicalSize, true);
            return new ViewportCuller(modelView, projection.getMatrix(new Matrix4f()), physicalSize);
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
            BlockPos pos1 = box.getPos1() == null ? BlockPos.ZERO : box.getPos1();
            BlockPos pos2 = box.getPos2() == null ? pos1 : box.getPos2();
            return new RegionBounds(BlockPos.min(pos1, pos2), BlockPos.max(pos1, pos2));
        }

        private long volume() {
            return (long) (this.max.getX() - this.min.getX() + 1)
                    * (this.max.getY() - this.min.getY() + 1)
                    * (this.max.getZ() - this.min.getZ() + 1);
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
                            NbtIo.read(input, NbtAccounter.create(NBT_READ_LIMIT_BYTES))
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
                            NbtIo.read(input, NbtAccounter.create(NBT_READ_LIMIT_BYTES)),
                            NbtIo.read(input, NbtAccounter.create(NBT_READ_LIMIT_BYTES))
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
                            NbtIo.read(input, NbtAccounter.create(NBT_READ_LIMIT_BYTES))
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
                    NbtIo.write(blockState.stateNbt(), output);
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
                    NbtIo.write(blockEntity.stateNbt(), output);
                    NbtIo.write(blockEntity.entityNbt(), output);
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
                    NbtIo.write(entity.entityNbt(), output);
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

        // ---- 顶点解编码工具：float32 (UV) / octahedral 8-bit (法线) ----

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
                int overlay = readShort(quantized, offset + 24) & 0xFFFF;
                int light = readInt(quantized, offset + 26);
                decodeNormal(readShort(quantized, offset + 30), normal);
                builder.addVertex(x, y, z, argb, u, v, overlay, light, normal[0], normal[1], normal[2]);
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
