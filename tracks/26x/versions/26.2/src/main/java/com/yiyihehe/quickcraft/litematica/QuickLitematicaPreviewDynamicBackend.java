package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yiyihehe.quickcraft.mixin.LitematicaFeatureRenderDispatcherAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaStagedVertexBufferAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/** 26.2 起的动态预览准备与资源生命周期；各版本保留自己的帧执行和投影调用。 */
abstract class QuickLitematicaPreviewDynamicBackend {
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;

    @Nullable
    protected PreparedDynamicScene preparedDynamicScene;

    @Nullable
    protected FeatureRenderDispatcher.PreparedFrame dynamicFrame;
    private final List<DynamicFrameBatch> dynamicBatches = new ArrayList<>();
    @Nullable
    private DynamicFrameBuild dynamicBuild;
    private boolean dynamicBatchingDisabled;
    private float dynamicProgress;
    private long dynamicBufferBytes;
    private boolean dynamicBufferFallback;
    private boolean dynamicStateFallback;
    @Nullable private DynamicStateBuild dynamicStateBuild;
    @Nullable private FullDynamicBuild fullDynamicBuild;

    protected float dynamicFrameProgress() {
        return this.dynamicBufferFallback ? 1.0F : this.dynamicProgress;
    }

    protected float dynamicContentProgress() {
        if (this.dynamicFrame != null) return 1.0F;
        if (!this.dynamicBufferFallback) return this.dynamicFrameProgress();
        if (this.preparedDynamicScene != null || this.dynamicStateFallback) return 1.0F;
        return this.dynamicStateBuild == null ? 0.0F : this.dynamicStateBuild.records.progress();
    }

    protected boolean cacheIncompatibleDynamicFrame() {
        return true;
    }

    protected void closeDynamicFrame() {
        var fullBuild = this.fullDynamicBuild;
        this.fullDynamicBuild = null;
        if (fullBuild != null) fullBuild.close();
        this.dynamicFrame = null;
        this.dynamicBuild = null;
        this.dynamicBatches.forEach(DynamicFrameBatch::close);
        this.dynamicBatches.clear();
        this.dynamicBufferBytes = 0L;
        this.dynamicProgress = 0.0F;
        this.dynamicBatchingDisabled = false;
        this.dynamicBufferFallback = false;
        this.dynamicStateFallback = false;
        this.dynamicStateBuild = null;
        this.preparedDynamicScene = null;
    }

    protected void forEachDynamicFrame(Consumer<FeatureRenderDispatcher.PreparedFrame> draw) {
        this.dynamicBatches.forEach(batch -> batch.renderTypes().execute(() -> draw.accept(batch.frame())));
    }

    protected void refreshDynamicFrameTransforms(Matrix4f modelView) {
        this.dynamicBatches.forEach(batch -> batch.renderTypes().refresh(modelView));
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

    // false 表示预算耗尽；调用方必须等待，不能回到整层即时提取。
    protected boolean prepareDynamicStates(QuickLitematicaPreview3D.MeshData data) {
        if (this.preparedDynamicScene != null || this.dynamicStateFallback || !data.hasDynamicContent()) {
            return true;
        }

        if (this.dynamicStateBuild == null) {
            var scene = data.dynamicSceneIfReady();
            if (scene == null) return false;
            if (scene.isEmpty()) {
                this.preparedDynamicScene = PreparedDynamicScene.EMPTY;
                data.closeDynamic();
                return true;
            }
            this.dynamicStateBuild = new DynamicStateBuild(scene);
            QuickLitematicaPreviewLog.LOGGER.info("动态渲染状态准备开始：方块实体={}，实体={}", data.blockEntities().size(), data.entities().size());
        }

        var build = this.dynamicStateBuild;
        var scene = build.records.nextScene();
        long start = System.nanoTime();
        try (var phase = QuickLitematicaPreviewLog.phase("动态渲染状态准备")) {
            Minecraft client = Minecraft.getInstance();
            scene.blockEntities().forEach((pos, entity) -> {
                try {
                    PreparedBlockEntity prepared = prepareBlockEntity(client, pos, entity);
                    if (prepared != null) build.blockEntities.add(prepared);
                } catch (RuntimeException ignored) {
                    QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=prepareDynamicStates，继续原有跳过/回退", ignored);
                }
            });
            scene.entities().forEach(renderedEntity -> {
                try {
                    EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(renderedEntity.entity(), 0.0F);
                    renderState.lightCoords = renderedEntity.light();
                    renderState.distanceToCameraSq = 0.0D;
                    build.entities.add(new PreparedEntity(renderState, renderedEntity.x(), renderedEntity.y(), renderedEntity.z()));
                } catch (RuntimeException ignored) {
                    QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=prepareDynamicStates，继续原有跳过/回退", ignored);
                }
            });
            if (!build.records.finishCollected(System.nanoTime() - start)) return false;
            this.preparedDynamicScene = new PreparedDynamicScene(List.copyOf(build.blockEntities), List.copyOf(build.entities));
            this.dynamicStateBuild = null;
            QuickLitematicaPreviewLog.LOGGER.info("dynamic-states-sliced-v1：动态渲染状态准备完成，方块实体={}/{}，实体={}/{}",
                    build.blockEntities.size(), data.blockEntities().size(), build.entities.size(), data.entities().size());
            data.closeDynamic();
            return true;
        } catch (RuntimeException ignored) {
            QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=prepareDynamicStates，继续原有跳过/回退", ignored);
            this.dynamicStateBuild = null;
            this.preparedDynamicScene = null;
            this.dynamicStateFallback = true;
            return true;
        }
    }

    @Nullable
    private static <T extends BlockEntity, S extends BlockEntityRenderState> PreparedBlockEntity prepareBlockEntity(
            Minecraft client,
            BlockPos pos,
            T entity
    ) {
        @SuppressWarnings("unchecked")
        BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) client.getBlockEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return null;
        }
        S renderState = renderer.createRenderState();
        renderer.extractRenderState(entity, renderState, 0.0F, Vec3.ZERO, null);
            if (renderState instanceof net.minecraft.client.renderer.blockentity.state.BeaconRenderState beam) {
                // 正交预览的宽度随整个模型缩放，不应按真实世界到相机原点的距离加粗。
                beam.beamRadiusScale = 1.0F;
            }
        return new PreparedBlockEntity(pos, renderer, renderState);
    }

    protected void drawPreparedDynamic(
            PreparedDynamicScene scene,
            Matrix4f modelView,
            int viewSize,
            SubmitNodeCollector submitNodes
    ) {
        if (scene.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
        PoseStack matrices = new PoseStack();
        matrices.mulPose(modelView);
        CameraRenderState cameraState = new CameraRenderState();

        scene.blockEntities().forEach(prepared -> {
            BlockPos pos = prepared.pos();
            if (!(prepared.renderState() instanceof net.minecraft.client.renderer.blockentity.state.BeaconRenderState)
                    && culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                return;
            }
            matrices.pushPose();
            try {
                matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                submitPreparedBlockEntity(prepared, matrices, submitNodes, cameraState);
            } catch (RuntimeException ignored) {
                QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=drawPreparedDynamic，继续原有跳过/回退", ignored);
            } finally {
                matrices.popPose();
            }
        });

        scene.entities().forEach(prepared -> {
            if (culler.isOutside((float) prepared.x(), (float) prepared.y(), (float) prepared.z())) {
                return;
            }
            try {
                client.getEntityRenderDispatcher().submit(
                        prepared.state(),
                        cameraState,
                        prepared.x(),
                        prepared.y(),
                        prepared.z(),
                        matrices,
                        submitNodes
                );
            } catch (RuntimeException ignored) {
                QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=drawPreparedDynamic，继续原有跳过/回退", ignored);
            }
        });
    }

    private static <T extends BlockEntity, S extends BlockEntityRenderState> void submitPreparedBlockEntity(
            PreparedBlockEntity prepared,
            PoseStack matrices,
            SubmitNodeCollector submitNodes,
            CameraRenderState cameraState
    ) {
        @SuppressWarnings("unchecked")
        BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) prepared.renderer();
        @SuppressWarnings("unchecked")
        S renderState = (S) prepared.renderState();
        renderer.submit(renderState, matrices, submitNodes, cameraState);
    }

    protected void drawDynamic(QuickLitematicaPreview3D.MeshData data, Matrix4f modelView, int viewSize, SubmitNodeCollector submitNodes) {
        QuickLitematicaPreview3D.DynamicScene scene = data.dynamicSceneIfReady();
        if (scene == null) return;
        if (scene.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
        PoseStack matrices = new PoseStack();
        matrices.mulPose(modelView);
        CameraRenderState cameraState = new CameraRenderState();

        scene.blockEntities().forEach((pos, entity) -> {
            if (!(entity instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity)
                    && culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                return;
            }

            matrices.pushPose();
            try {
                matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                renderBlockEntity(client, entity, matrices, submitNodes, cameraState);
            } catch (RuntimeException ignored) {
                QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
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
            } catch (RuntimeException ignored) {
                QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=drawDynamic，继续原有跳过/回退", ignored);
            }
        });
    }

    private static <T extends BlockEntity, S extends BlockEntityRenderState> void renderBlockEntity(
            Minecraft client,
            T entity,
            PoseStack matrices,
            SubmitNodeCollector submitNodes,
            CameraRenderState cameraState
    ) {
        @SuppressWarnings("unchecked")
        BlockEntityRenderer<T, S> renderer = (BlockEntityRenderer<T, S>) client.getBlockEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return;
        }
        S renderState = renderer.createRenderState();
        renderer.extractRenderState(entity, renderState, 0.0F, Vec3.ZERO, null);
        if (renderState instanceof net.minecraft.client.renderer.blockentity.state.BeaconRenderState beam) {
            beam.beamRadiusScale = 1.0F;
        }
        renderer.submit(renderState, matrices, submitNodes, cameraState);
    }

    protected void prepareDynamicFrame(QuickLitematicaPreview3D.MeshData data) {
        if (this.dynamicFrame != null || this.dynamicBufferFallback || !data.hasDynamicContent()) return;
        if (this.dynamicBatchingDisabled) {
            if (this.cacheIncompatibleDynamicFrame()) this.prepareSingleDynamicFrame(data);
            else this.dynamicBufferFallback = true;
            return;
        }
        if (this.dynamicBuild == null) {
            QuickLitematicaPreview3D.DynamicScene scene = data.dynamicSceneIfReady();
            if (scene == null) return;
            if (scene.isEmpty()) {
                this.dynamicProgress = 1.0F;
                this.dynamicBufferFallback = true;
                data.closeDynamic();
                return;
            }
            this.dynamicBuild = new DynamicFrameBuild(scene);
            QuickLitematicaPreviewLog.LOGGER.info("动态分批准备开始：方块实体={}，实体={}，目标单批={} us，强制渲染={}",
                    scene.blockEntities().size(), scene.entities().size(), QuickLitematicaPreviewBatchBudget.TARGET_NANOS / 1_000L, data.forceRender);
        }

        DynamicFrameBuild build = this.dynamicBuild;
        long start = System.nanoTime();
        try (var phase = QuickLitematicaPreviewLog.phase("动态单批提交/几何构建/上传")) {
            SubmitNodeStorage submitNodes = new SubmitNodeStorage();
            PoseStack matrices = new PoseStack();
            CameraRenderState cameraState = new CameraRenderState();
            Minecraft client = Minecraft.getInstance();
            int count = 0;
            while (count < build.batchSize && (build.blocks.hasNext() || build.entities.hasNext())) {
                if (build.blocks.hasNext()) {
                    var entry = build.blocks.next();
                    BlockPos pos = entry.getKey();
                    matrices.pushPose();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entry.getValue(), matrices, submitNodes, cameraState);
                    } finally {
                        matrices.popPose();
                    }
                } else {
                    var entity = build.entities.next();
                    EntityRenderState state = client.getEntityRenderDispatcher().extractEntity(entity.entity(), 0.0F);
                    state.lightCoords = entity.light();
                    state.distanceToCameraSq = 0.0D;
                    client.getEntityRenderDispatcher().submit(state, cameraState, entity.x(), entity.y(), entity.z(), matrices, submitNodes);
                }
                count++;
            }
            if (!build.compatibility.accepts(submitNodes)) {
                int batches = this.dynamicBatches.size();
                this.closeDynamicFrame();
                this.dynamicBatchingDisabled = true;
                QuickLitematicaPreviewLog.LOGGER.info("动态分批回退：提交包含透明/特殊阶段、严格顺序、不同材质或非模型内容，已撤销={} 批，改用完整帧保留全局顺序", batches);
                return;
            }
            DynamicFrameBatch batch = createDynamicFrame(submitNodes, 64 * 1024);
            long totalBytes = this.dynamicBufferBytes + batch.bytes();
            if (!data.forceRender && totalBytes > MAX_DYNAMIC_BUFFER_BYTES) {
                batch.close();
                this.closeDynamicFrame();
                this.dynamicBatchingDisabled = true;
                QuickLitematicaPreviewLog.LOGGER.info("动态分批回退：累计 GPU 缓冲={} B，默认上限={} B，继续完整帧原有容量判定", totalBytes, MAX_DYNAMIC_BUFFER_BYTES);
                return;
            }
            try {
                this.dynamicBatches.add(batch);
            } catch (Error failure) {
                batch.close();
                throw failure;
            }
            this.dynamicBufferBytes = totalBytes;
            build.completed += count;
            long elapsed = System.nanoTime() - start;
            build.batchSize = QuickLitematicaPreviewBatchBudget.nextSize(build.batchSize, elapsed);
            this.dynamicProgress = Math.min(0.99F, build.completed / (float) build.total);
            QuickLitematicaPreviewLog.LOGGER.info("动态分批上传完成：批次={}，本批记录={}，完成={}/{}，单批={} us，CPU 暂存已释放，累计 GPU={} B，下批记录预算={}",
                    this.dynamicBatches.size(), count, build.completed, build.total, elapsed / 1_000L, this.dynamicBufferBytes, build.batchSize);
            if (!build.blocks.hasNext() && !build.entities.hasNext()) {
                // 所有批次均通过兼容性检查后才发布动态画面，半成品不会被当成完整内容。
                this.dynamicFrame = this.dynamicBatches.getFirst().frame();
                this.dynamicBuild = null;
                this.preparedDynamicScene = null;
                this.dynamicProgress = 1.0F;
                data.closeDynamic();
                QuickLitematicaPreviewLog.LOGGER.info("动态分批准备全部完成：记录={}，批次={}，GPU={} B", build.completed, this.dynamicBatches.size(), this.dynamicBufferBytes);
            }
        } catch (RuntimeException failure) {
            this.closeDynamicFrame();
            this.dynamicBatchingDisabled = true;
            QuickLitematicaPreviewLog.LOGGER.warn("动态分批准备失败：撤销全部未发布批次，下一帧回到原完整帧路径", failure);
        }
    }

    private void prepareSingleDynamicFrame(QuickLitematicaPreview3D.MeshData data) {
        if (this.fullDynamicBuild == null) {
            var scene = data.dynamicSceneIfReady();
            if (scene == null) return;
            this.fullDynamicBuild = new FullDynamicBuild(scene);
        }
        var build = this.fullDynamicBuild;
        try (var phase = QuickLitematicaPreviewLog.phase("动态完整缓存帧几何构建/上传")) {
            if (!build.submitted) {
                var scene = build.records.nextScene();
                long start = System.nanoTime();
                Minecraft client = Minecraft.getInstance();
                PoseStack matrices = new PoseStack();
                CameraRenderState cameraState = new CameraRenderState();
                scene.blockEntities().forEach((pos, entity) -> {
                    matrices.pushPose();
                    try {
                        matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                        renderBlockEntity(client, entity, matrices, build.nodes, cameraState);
                    } catch (RuntimeException failure) {
                        QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=prepareDynamicFrame，继续原有跳过/回退", failure);
                    } finally { matrices.popPose(); }
                });
                scene.entities().forEach(entity -> {
                    try {
                        EntityRenderState state = client.getEntityRenderDispatcher().extractEntity(entity.entity(), 0.0F);
                        state.lightCoords = entity.light();
                        state.distanceToCameraSq = 0.0D;
                        client.getEntityRenderDispatcher().submit(state, cameraState, entity.x(), entity.y(), entity.z(), matrices, build.nodes);
                    } catch (RuntimeException failure) {
                        QuickLitematicaPreviewLog.LOGGER.warn("原生预览局部失败：入口=prepareDynamicFrame，继续原有跳过/回退", failure);
                    }
                });
                build.submitted = build.records.finishCollected(System.nanoTime() - start);
                this.dynamicProgress = build.records.progress() * 0.4F;
                // 阶段切换也留给下一帧，避免最后一批提交叠加原生全局分组。
                return;
            }
            if (build.geometry == null) {
                build.geometry = new FullGeometryBuild(build.nodes);
                this.dynamicProgress = 0.4F;
                return;
            }
            if (!build.geometry.advance()) {
                this.dynamicProgress = 0.4F + build.geometry.work.progress() * 0.59F;
                return;
            }
            DynamicFrameBatch batch = build.geometry.takeBatch();
            build.close();
            this.fullDynamicBuild = null;
            if (!data.forceRender && batch.bytes() > MAX_DYNAMIC_BUFFER_BYTES) {
                batch.close();
                throw new IllegalStateException("Dynamic preview buffer exceeds limit");
            }
            try { this.dynamicBatches.add(batch); }
            catch (RuntimeException | Error failure) { batch.close(); throw failure; }
            this.dynamicBufferBytes = batch.bytes();
            this.dynamicFrame = batch.frame();
            this.dynamicProgress = 1.0F;
            this.preparedDynamicScene = null;
            data.closeDynamic();
            QuickLitematicaPreviewLog.LOGGER.info("dynamic-frame-sliced-v1：完整动态帧分步准备完成，GPU={} B，原生阶段与组顺序保留", batch.bytes());
        } catch (RuntimeException failure) {
            build.close();
            this.fullDynamicBuild = null;
            this.dynamicBufferFallback = true;
            QuickLitematicaPreviewLog.LOGGER.warn("动态完整缓存帧准备失败：回退即时绘制，方块实体={}，实体={}", data.blockEntities().size(), data.entities().size(), failure);
        } catch (Error failure) {
            build.close();
            this.fullDynamicBuild = null;
            throw failure;
        }
    }

    private static final class FullDynamicBuild implements AutoCloseable {
        final QuickLitematicaPreviewDynamicBatch<Void> records;
        final SubmitNodeStorage nodes = new SubmitNodeStorage();
        boolean submitted;
        @Nullable FullGeometryBuild geometry;

        FullDynamicBuild(QuickLitematicaPreview3D.DynamicScene scene) {
            this.records = new QuickLitematicaPreviewDynamicBatch<>(scene, QuickLitematicaPreviewBatchBudget.MIN_SIZE);
        }

        @Override public void close() { if (this.geometry != null) this.geometry.close(); }
    }

    private static final class FullGeometryBuild implements AutoCloseable {
        final StagedVertexBuffer buffer = new StagedVertexBuffer(() -> "QuickCraft preview complete dynamic frame", 4 * 1024 * 1024);
        final QuickLitematicaPreviewFrameBuild work = new QuickLitematicaPreviewFrameBuild(this.buffer);
        final QuickLitematicaPreviewRenderTypes materials = QuickLitematicaPreviewRenderTypes.beginCapture();
        @Nullable FeatureRenderDispatcher dispatcher;
        @Nullable FeatureRenderDispatcher.PreparedFrame frame;
        private boolean transferred;

        FullGeometryBuild(SubmitNodeStorage nodes) {
            try (var phase = QuickLitematicaPreviewLog.phase("动态完整帧原生全局分组")) {
                Minecraft client = Minecraft.getInstance();
                this.dispatcher = new FeatureRenderDispatcher(client.gameRenderer.renderBuffers(), client.getModelManager(),
                        client.getAtlasManager(), client.font, client.gameRenderer.gameRenderState());
                ((LitematicaFeatureRenderDispatcherAccessor) (Object) this.dispatcher).quickcraft$setStagedVertexBuffer(this.buffer);
                Matrix4fStack stack = RenderSystem.getModelViewStack();
                stack.pushMatrix();
                try {
                    stack.identity();
                    this.work.capture(() -> this.frame = this.dispatcher.prepareFrame(nodes));
                    this.work.seal();
                } finally { stack.popMatrix(); }
            } catch (RuntimeException | Error failure) {
                this.close();
                throw failure;
            } finally { this.materials.close(); }
        }

        boolean advance() {
            boolean[] done = {false};
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            try (var phase = QuickLitematicaPreviewLog.phase("动态完整帧几何切片/最终上传")) {
                stack.identity();
                this.materials.record(() -> done[0] = this.work.advance(QuickLitematicaPreviewBatchBudget.TARGET_NANOS));
                return done[0];
            } finally { stack.popMatrix(); }
        }

        DynamicFrameBatch takeBatch() {
            var accessor = (LitematicaStagedVertexBufferAccessor) (Object) this.buffer;
            long vertices = accessor.quickcraft$getCurrentVertexBuffer() == null ? 0L : accessor.quickcraft$getCurrentVertexBuffer().size();
            long indices = accessor.quickcraft$getCurrentIndexBuffer() == null ? 0L : accessor.quickcraft$getCurrentIndexBuffer().size();
            accessor.quickcraft$getStagingBuffer().close();
            QuickLitematicaPreviewLog.LOGGER.info("dynamic-material-cache-v1：材质状态={}，复用={} 次，原生绘制段={}，GPU={} B",
                    this.materials.size(), this.materials.reused(), accessor.quickcraft$getDraws().size(), vertices + indices);
            var batch = new DynamicFrameBatch(java.util.Objects.requireNonNull(this.frame), java.util.Objects.requireNonNull(this.dispatcher),
                    this.buffer, this.materials, vertices + indices);
            this.transferred = true;
            return batch;
        }

        @Override public void close() {
            this.work.close();
            if (!this.transferred) {
                this.materials.release();
                closeQuietly(this.frame);
                closeQuietly(this.dispatcher);
                closeQuietly(this.buffer);
                this.frame = null;
                this.dispatcher = null;
            }
        }
    }

    private static DynamicFrameBatch createDynamicFrame(SubmitNodeStorage submitNodes, int initialBytes) {
        StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(() -> "QuickCraft preview dynamic batch", initialBytes);
        FeatureRenderDispatcher dispatcher = null;
        FeatureRenderDispatcher.PreparedFrame frame = null;
        QuickLitematicaPreviewRenderTypes renderTypes = QuickLitematicaPreviewRenderTypes.beginCapture();
        try (renderTypes) {
            Minecraft client = Minecraft.getInstance();
            dispatcher = new FeatureRenderDispatcher(client.gameRenderer.renderBuffers(), client.getModelManager(),
                    client.getAtlasManager(), client.font, client.gameRenderer.gameRenderState());
            ((LitematicaFeatureRenderDispatcherAccessor) (Object) dispatcher).quickcraft$setStagedVertexBuffer(stagedBuffer);
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            try {
                stack.identity();
                frame = dispatcher.prepareFrame(submitNodes);
            } finally {
                stack.popMatrix();
            }
            var accessor = (LitematicaStagedVertexBufferAccessor) (Object) stagedBuffer;
            long vertexBytes = accessor.quickcraft$getCurrentVertexBuffer() == null ? 0L : accessor.quickcraft$getCurrentVertexBuffer().size();
            long indexBytes = accessor.quickcraft$getCurrentIndexBuffer() == null ? 0L : accessor.quickcraft$getCurrentIndexBuffer().size();
            accessor.quickcraft$getStagingBuffer().close();
            QuickLitematicaPreviewLog.LOGGER.info("dynamic-material-cache-v1：材质状态={}，复用={} 次，原生绘制段={}，GPU={} B",
                    renderTypes.size(), renderTypes.reused(), accessor.quickcraft$getDraws().size(), vertexBytes + indexBytes);
            return new DynamicFrameBatch(frame, dispatcher, stagedBuffer, renderTypes, vertexBytes + indexBytes);
        } catch (RuntimeException | Error failure) {
            renderTypes.release();
            closeQuietly(frame);
            closeQuietly(dispatcher);
            closeQuietly(stagedBuffer);
            throw failure;
        }
    }

    static final class BatchCompatibility {
        @Nullable private RenderType renderType;

        boolean accepts(SubmitNodeStorage nodes) {
            if (SharedConstants.DEBUG_SHUFFLE_MODELS) return false;
            boolean[] valid = {true};
            nodes.getSubmitsPerOrder().int2ObjectEntrySet().forEach(entry -> {
                var collection = entry.getValue();
                for (var phase : collection.allPhases()) {
                    if (phase != collection.solid && !phase.isEmpty()) valid[0] = false;
                }
                if (!collection.solid.isEmpty() && entry.getIntKey() != 0) valid[0] = false;
                List<SubmitNode> inspected = new ArrayList<>();
                // 原生 sortInto 会清空阶段队列；核验后按同序恢复，不能丢掉待构建的模型。
                collection.solid.sortInto((submit, strictlyOrdered) -> {
                    inspected.add(submit);
                    if (strictlyOrdered || !(submit instanceof ModelFeatureRenderer.Submit<?> model)
                            || model.sheetedDecalPose() != null || model.renderType() == null
                            || model.renderType().hasBlending() || model.renderType().sortOnUpload()
                            || !model.renderType().canConsolidateConsecutiveGeometry()) {
                        valid[0] = false;
                        return;
                    }
                    if (this.renderType == null) this.renderType = model.renderType();
                    else if (this.renderType != model.renderType()) valid[0] = false;
                });
                inspected.forEach(collection.solid::submit);
            });
            return valid[0];
        }
    }

    private static final class DynamicStateBuild {
        final QuickLitematicaPreviewDynamicBatch<Void> records;
        final List<PreparedBlockEntity> blockEntities = new ArrayList<>();
        final List<PreparedEntity> entities = new ArrayList<>();

        DynamicStateBuild(QuickLitematicaPreview3D.DynamicScene scene) {
            this.records = new QuickLitematicaPreviewDynamicBatch<>(scene, QuickLitematicaPreviewBatchBudget.MIN_SIZE);
        }
    }

    private static final class DynamicFrameBuild {
        final Iterator<Map.Entry<BlockPos, BlockEntity>> blocks;
        final Iterator<QuickLitematicaPreview3D.RenderedEntity> entities;
        final BatchCompatibility compatibility = new BatchCompatibility();
        final long total;
        long completed;
        int batchSize = QuickLitematicaPreviewBatchBudget.INITIAL_SIZE;

        DynamicFrameBuild(QuickLitematicaPreview3D.DynamicScene scene) {
            this.blocks = scene.blockEntities().entrySet().iterator();
            this.entities = scene.entities().iterator();
            this.total = (long) scene.blockEntities().size() + scene.entities().size();
        }
    }

    private record DynamicFrameBatch(FeatureRenderDispatcher.PreparedFrame frame, FeatureRenderDispatcher dispatcher,
                                     StagedVertexBuffer stagedBuffer, QuickLitematicaPreviewRenderTypes renderTypes,
                                     long bytes) implements AutoCloseable {
        @Override public void close() {
            this.renderTypes.release();
            closeQuietly(this.frame);
            closeQuietly(this.dispatcher);
            closeQuietly(this.stagedBuffer);
        }
    }

    protected record PreparedDynamicScene(List<PreparedBlockEntity> blockEntities, List<PreparedEntity> entities) {
        private static final PreparedDynamicScene EMPTY = new PreparedDynamicScene(List.of(), List.of());

        private boolean isEmpty() {
            return this.blockEntities.isEmpty() && this.entities.isEmpty();
        }
    }

    private record PreparedBlockEntity(
            BlockPos pos,
            BlockEntityRenderer<?, ?> renderer,
            BlockEntityRenderState renderState
    ) {
    }

    private record PreparedEntity(
            EntityRenderState state,
            double x,
            double y,
            double z
    ) {
    }
}
