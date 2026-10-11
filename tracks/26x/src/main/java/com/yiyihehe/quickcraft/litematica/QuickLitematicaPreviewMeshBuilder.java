package com.yiyihehe.quickcraft.litematica;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.FakeLightingProvider;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 网格扫描、动态原始记录及静态批次发布；不持有预览 UI 或 GPU 上传任务。 */
final class QuickLitematicaPreviewMeshBuilder {
    private QuickLitematicaPreviewMeshBuilder() {}

    static MeshData build(
            LitematicaSchematic schematic,
            AtomicBoolean cancelled,
            ProgressSink progressSink,
            DimensionsSink dimensionsSink,
            Consumer<List<LayerMesh>> batchSink,
            QuickLitematicaPreviewMeshStore meshStore
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
            if (QuickLitematicaPreviewLog.enabled()) LOGGER.info("transparent-build-stream-v1：静态网格在完整方块后分批发布，目标单批={} 顶点，透明记录器引用保留", STATIC_BATCH_TARGET_VERTICES);
            @Nullable PreviewCtm.Context ctmContext = PreviewCtm.create(collector, client);
            final List<LayerMesh> layers = new ArrayList<>();
            Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
            List<BlockEntityData> blockEntities = new ArrayList<>();
            List<EntityData> entities = new ArrayList<>();
            Map<BlockState, Boolean> blockEntityRendererCache = new HashMap<>();
            List<RegionBlockView> regionViews = new ArrayList<>();
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
                regionViews.add(view);
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
                            if (regionStats != null) regionStats.record(state, fluidStart - entityStart,
                                    modelStart - fluidStart, System.nanoTime() - modelStart);

                        } catch (RuntimeException | Error failure) {
                            LOGGER.error("方块模型记录失败：区域={}，位置={}，状态={}", regionName, pos, state, failure);
                            throw failure;
                        }
                        if (collector.shouldPublishBatch()) {
                            for (LayerMesh mesh : collector.drainReadyMeshes()) {
                                for (LayerMesh split : QuickLitematicaPreviewMeshBatches.split(mesh, cancelled)) {
                                    List<LayerMesh> batch = List.of(split);
                                    meshStore.addBatch(layers, batch,
                                            (long) collector.vertexCount * QUANTIZED_VERTEX_BYTES, cancelled);
                                    batchSink.accept(batch);
                                }
                            }
                        }
                    });
                } finally {
                    if (regionStats != null) {
                        LOGGER.info("fluid-enclosed-cull-v1：区域={}，流体={}，内部跳过={}，自定义处理器保留={}",
                                regionName, view.fluidVisibility.examined, view.fluidVisibility.skipped, view.fluidVisibility.custom);
                        regionStats.close();
                    }
                }
                scannedVolume += regionVolume;
            }

            recordBeaconBlockStates(blockStates, blockEntities, regionViews, bounds, cancelled);
            progressSink.set(PROGRESS_MESHING_END);
            List<LayerMesh> finalBatch = new ArrayList<>();
            for (LayerMesh mesh : collector.drainAllMeshes()) {
                finalBatch.addAll(QuickLitematicaPreviewMeshBatches.split(mesh, cancelled));
            }
            for (int index = 0; index < finalBatch.size(); index++) {
                LayerMesh mesh = finalBatch.set(index, null);
                List<LayerMesh> batch = List.of(mesh);
                meshStore.addBatch(layers, batch,
                        (long) collector.vertexCount * QUANTIZED_VERTEX_BYTES, cancelled);
                batchSink.accept(batch);
            }
            List<LayerMesh> completeLayers = List.copyOf(layers);
            int vertices = vertexCount(completeLayers);
            if (QuickLitematicaPreviewLog.enabled()) LOGGER.info("模型构建统计：顶点={}/{}，层批次={}，动态状态={}/{}，方块实体={}/{}，实体={}/{}，扫描体积={}",
                    vertices, QuickLitematicaPreviewVertexBudget.vertexLimit(), completeLayers.size(), blockStates.size(), QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES),
                    blockEntities.size(), QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES), entities.size(), QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES), scannedVolume);
            if (vertices > QuickLitematicaPreviewVertexBudget.vertexLimit()
                    || blockStates.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES)
                    || blockEntities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES)
                    || entities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES)) {
                throw new PreviewTooLargeException(vertices > QuickLitematicaPreviewVertexBudget.vertexLimit());
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

    private static void recordBeaconBlockStates(Map<BlockPos, BlockStateData> states,
            List<BlockEntityData> entities, List<RegionBlockView> views, Bounds bounds, AtomicBoolean cancelled) {
        for (BlockEntityData data : entities) {
            throwIfCancelled(cancelled);
            if (!"minecraft:beacon".equals(data.stateNbt().getStringOr("Name", ""))) continue;
            BlockPos beacon = new BlockPos(data.x(), data.y(), data.z()).offset(bounds.min());
            for (int depth = 1; depth <= 4; depth++) {
                for (int x = -depth; x <= depth; x++) {
                    for (int z = -depth; z <= depth; z++) {
                        recordBeaconState(states, views, beacon.offset(x, -depth, z), bounds);
                    }
                }
            }
            for (int y = beacon.getY() + 1; y <= bounds.max().getY(); y++) {
                if ((y & 0xFF) == 0) throwIfCancelled(cancelled);
                recordBeaconState(states, views, new BlockPos(beacon.getX(), y, beacon.getZ()), bounds);
            }
        }
    }

    private static void recordBeaconState(Map<BlockPos, BlockStateData> states,
            List<RegionBlockView> views, BlockPos pos, Bounds bounds) {
        for (int i = views.size() - 1; i >= 0; i--) {
            BlockState state = views.get(i).getBlockState(pos);
            if (!state.isAir()) {
                recordDynamicBlockState(states, state, pos.subtract(bounds.min()));
                return;
            }
        }
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
        if (blockEntities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES)) {
            throw new PreviewTooLargeException();
        }
    }

    static boolean hasPreviewBlockEntityRenderer(EntityBlock provider, BlockState state, BlockPos renderPos) {
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
        if (blockStates.size() >= QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES) && !blockStates.containsKey(renderPos)) {
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
            if (entities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES)) {
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

    static CompoundTag copyEntityNbtAt(CompoundTag source, double x, double y, double z) {
        CompoundTag copy = source.copy();
        if (source.get("Pos") instanceof ListTag oldPos && oldPos.size() == 3
                && oldPos.get(0) instanceof DoubleTag oldX
                && oldPos.get(1) instanceof DoubleTag oldY
                && oldPos.get(2) instanceof DoubleTag oldZ) {
            int dx = (int) Math.floor(x) - (int) Math.floor(oldX.doubleValue());
            int dy = (int) Math.floor(y) - (int) Math.floor(oldY.doubleValue());
            int dz = (int) Math.floor(z) - (int) Math.floor(oldZ.doubleValue());
            // 挂载位置与 Pos 必须处于同一坐标系，否则原版读取会拒绝挂载点。
            if (source.get("block_pos") instanceof IntArrayTag attached && attached.getAsIntArray().length == 3) {
                int[] anchor = attached.getAsIntArray();
                copy.put("block_pos", new IntArrayTag(new int[]{anchor[0] + dx, anchor[1] + dy, anchor[2] + dz}));
            }
            if (source.get("TileX") instanceof IntTag tileX
                    && source.get("TileY") instanceof IntTag tileY
                    && source.get("TileZ") instanceof IntTag tileZ) {
                copy.putInt("TileX", tileX.intValue() + dx);
                copy.putInt("TileY", tileY.intValue() + dy);
                copy.putInt("TileZ", tileZ.intValue() + dz);
            }
        }
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
        if (fluidState.isEmpty() || view.fluidVisibility.skipEnclosed(view, pos, fluidState)) {
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

    private static final class MeshCollector {
        private final EnumMap<LayerKey, RecordingVertexConsumer> consumers = new EnumMap<>(LayerKey.class);
        private final int vertexLimit = QuickLitematicaPreviewVertexBudget.vertexLimit();
        private int vertexCount;

        private VertexConsumer consumerFor(RenderType renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        private VertexConsumer consumerFor(LayerKey layer) {
            // 这些材质共用透明管线，必须一起排序，避免水先写深度后遮掉玻璃。
            layer = layer.isTranslucent() ? LayerKey.TRANSLUCENT : layer;
            return this.consumers.computeIfAbsent(layer, ignored -> new RecordingVertexConsumer(this));
        }

        private VertexConsumer consumerFor(ChunkSectionLayer renderLayer) {
            return this.consumerFor(LayerKey.from(renderLayer));
        }

        private void addVertex(QuantizedVertexBuffer vertices, float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            QuickLitematicaPreviewVertexBudget.checkVertexCount(this.vertexCount, this.vertexLimit);

            this.vertexCount++;
            vertices.add(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
        }

        private boolean shouldPublishBatch() {
            int vertices = 0;
            for (Map.Entry<LayerKey, RecordingVertexConsumer> entry : this.consumers.entrySet()) {
                int count = entry.getValue().vertices.vertexCount();
                if (entry.getKey().isTranslucent()) {
                    if (count >= STATIC_BATCH_TARGET_VERTICES) return true;
                } else {
                    vertices += count;
                }
            }
            return vertices >= STATIC_BATCH_TARGET_VERTICES;
        }

        private List<LayerMesh> drainReadyMeshes() {
            return this.drainMeshes(false);
        }

        private List<LayerMesh> drainAllMeshes() {
            return this.drainMeshes(true);
        }

        private List<LayerMesh> drainMeshes(boolean finalBatch) {
            int opaqueVertices = 0;
            for (var entry : this.consumers.entrySet()) {
                if (!entry.getKey().isTranslucent()) opaqueVertices += entry.getValue().vertices.vertexCount();
            }
            List<LayerMesh> meshes = new ArrayList<>();
            for (LayerKey layer : LayerKey.DRAW_ORDER) {
                RecordingVertexConsumer consumer = this.consumers.get(layer);
                if (consumer == null || consumer.vertices.isEmpty()) continue;
                int vertices = layer.isTranslucent() ? consumer.vertices.vertexCount() : opaqueVertices;
                if (!finalBatch && vertices < STATIC_BATCH_TARGET_VERTICES) continue;
                meshes.add(new LayerMesh(layer, consumer.vertices.takeBytes()));
                // Fabric/CTM 可能保存 consumer 引用；保留记录器，只移交已完成方块的顶点。
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
            this.bytes = Arrays.copyOf(this.bytes,
                    QuickLitematicaPreviewVertexBudget.growCapacity(this.bytes.length, needed));
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
        final QuickLitematicaPreviewFluids fluidVisibility = new QuickLitematicaPreviewFluids();
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
        public int getMinY() {
            return 0;
        }
    }

    private interface NonAirBlockConsumer {
        void accept(BlockPos position, BlockState state);
    }

    interface DimensionsSink {
        void set(int sizeX, int sizeY, int sizeZ);
    }

    static String ctmRuntimeToken() {
        return PreviewCtm.runtimeToken();
    }
}
