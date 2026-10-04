package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yiyihehe.quickcraft.mixin.LitematicaFeatureRenderDispatcherAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaStagedVertexBufferAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
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

/** 26.2 起的动态预览准备与资源生命周期；各版本保留自己的帧执行和投影调用。 */
abstract class QuickLitematicaPreviewDynamicBackend {
    private static final long MAX_DYNAMIC_BUFFER_BYTES = 128L * 1024L * 1024L;

    @Nullable
    protected PreparedDynamicScene preparedDynamicScene;

    @Nullable
    private StagedVertexBuffer dynamicStagedVertexBuffer;

    @Nullable
    private FeatureRenderDispatcher dynamicDispatcher;

    @Nullable
    protected FeatureRenderDispatcher.PreparedFrame dynamicFrame;

    private boolean dynamicBufferFallback;

    private boolean dynamicStateFallback;

    protected void closeDynamicFrame() {
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

    protected void prepareDynamicStates(QuickLitematicaPreview3D.MeshData data) {
        if (this.preparedDynamicScene != null || this.dynamicStateFallback || !data.hasDynamicContent()) {
            return;
        }

        QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
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
                    if (prepared != null) {
                        blockEntities.add(prepared);
                    }
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
            if (culler.isOutside(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F)) {
                return;
            }
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
            } catch (Throwable ignored) {
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
        QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
        if (scene.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        QuickLitematicaPreview3D.ViewportCuller culler = QuickLitematicaPreview3D.ViewportCuller.forPip(modelView, viewSize);
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
        renderer.submit(renderState, matrices, submitNodes, cameraState);
    }

    protected void prepareDynamicFrame(QuickLitematicaPreview3D.MeshData data) {
        if (this.dynamicFrame != null || this.dynamicBufferFallback || !data.hasDynamicContent()) {
            return;
        }

        QuickLitematicaPreview3D.DynamicScene scene = data.dynamicScene();
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

            stagedAccessor.quickcraft$getStagingBuffer().close();
            this.closeDynamicFrame();
            this.dynamicStagedVertexBuffer = stagedBuffer;
            this.dynamicDispatcher = dispatcher;
            this.dynamicFrame = frame;
            data.closeDynamic();
        } catch (Throwable e) {
            this.dynamicBufferFallback = true;
            closeQuietly(frame);
            closeQuietly(dispatcher);
            closeQuietly(stagedBuffer);
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
