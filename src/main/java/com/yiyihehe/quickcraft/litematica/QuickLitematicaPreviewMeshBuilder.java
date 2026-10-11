package com.yiyihehe.quickcraft.litematica;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;
import java.util.Collection;
import net.minecraft.util.math.random.Random;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.block.BlockEntityProvider;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;

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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                throw new IllegalStateException("Litematica preview needs a loaded client world");
            }
            progressSink.set(PROGRESS_MESHING_START);

            Bounds bounds = Bounds.from(schematic.getAreas().values());
            dimensionsSink.set(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            MeshCollector collector = new MeshCollector();
            if (QuickLitematicaPreviewLog.enabled()) LOGGER.info("transparent-build-stream-v1：静态网格在完整方块后分批发布，目标单批={} 顶点，透明记录器引用保留", STATIC_BATCH_TARGET_VERTICES);
            final List<LayerMesh> layers = new ArrayList<>();
            Map<BlockPos, BlockStateData> blockStates = new HashMap<>();
            List<BlockEntityData> blockEntities = new ArrayList<>();
            List<EntityData> entities = new ArrayList<>();
            Map<BlockState, Boolean> blockEntityRendererCache = new HashMap<>();
            List<RegionBlockView> regionViews = new ArrayList<>();
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
                    LOGGER.warn("跳过区域：名称={}，原因=missing_container_or_area，容器存在={}，区域存在={}", regionName, container != null, area != null);
                    continue;
                }

                RegionBlockView view = new RegionBlockView(container, area);
                regionViews.add(view);
                RegionBounds regionBounds = RegionBounds.from(area);
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
                            recordBlockEntity(blockStates, blockEntities, blockEntityRendererCache, view, state, schematic, regionName, pos, renderPos, bounds);
                            long fluidStart = regionStats == null ? 0L : System.nanoTime();
                            renderer.renderFluid(view, state, pos, renderPos);
                            long modelStart = regionStats == null ? 0L : System.nanoTime();
                            renderer.renderBlock(view, state, pos, renderPos);
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

    private static void recordBeaconBlockStates(Map<BlockPos, BlockStateData> states,
            List<BlockEntityData> entities, List<RegionBlockView> views, Bounds bounds, AtomicBoolean cancelled) {
        for (BlockEntityData data : entities) {
            throwIfCancelled(cancelled);
            if (!"minecraft:beacon".equals(readString(data.stateNbt(), "Name"))) continue;
            BlockPos beacon = new BlockPos(data.x(), data.y(), data.z()).add(bounds.min());
            for (int depth = 1; depth <= 4; depth++) {
                for (int x = -depth; x <= depth; x++) {
                    for (int z = -depth; z <= depth; z++) {
                        recordBeaconState(states, views, beacon.add(x, -depth, z), bounds);
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
        NbtCompound blockStateNbt = NbtHelper.fromBlockState(state);
        NbtCompound entityNbt = sanitizeBlockEntityNbt(nbt, readString(blockStateNbt, "Name"));
        entityNbt.putInt("x", renderPos.getX());
        entityNbt.putInt("y", renderPos.getY());
        entityNbt.putInt("z", renderPos.getZ());
        blockEntities.add(new BlockEntityData(renderPos.getX(), renderPos.getY(), renderPos.getZ(), blockStateNbt, entityNbt));
        if (blockEntities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES)) {
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

    static NbtCompound sanitizeBlockEntityNbt(NbtCompound nbt, String blockId) {
        NbtCompound sanitized = nbt.copy();
        // 火堆食物等可见物品必须保留，只有已知普通容器可以删减库存。
        String id = readString(sanitized, "id");
        if ((sanitized.contains("Items") || sanitized.contains("Inventory"))
                && isNonVisualInventory(id.isEmpty() ? blockId : id) && isPreviewInventoryHidden(blockId)) {
            sanitized.remove("Items");
            sanitized.remove("Inventory");
        }
        return sanitized;
    }

    static void recordDynamicBlockState(Map<BlockPos, BlockStateData> blockStates, BlockState state, BlockPos renderPos) {
        if (blockStates.size() >= QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES) && !blockStates.containsKey(renderPos)) {
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
            if (entities.size() > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES)) {
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
        if (source.get("Pos") instanceof NbtList oldPos && oldPos.size() == 3
                && oldPos.get(0) instanceof NbtDouble oldX
                && oldPos.get(1) instanceof NbtDouble oldY
                && oldPos.get(2) instanceof NbtDouble oldZ) {
            int dx = (int) Math.floor(x) - (int) Math.floor(oldX.doubleValue());
            int dy = (int) Math.floor(y) - (int) Math.floor(oldY.doubleValue());
            int dz = (int) Math.floor(z) - (int) Math.floor(oldZ.doubleValue());
            // 挂载位置与 Pos 必须处于同一坐标系，否则原版读取会拒绝挂载点。
            if (source.get("block_pos") instanceof NbtIntArray attached && attached.getIntArray().length == 3) {
                int[] anchor = attached.getIntArray();
                copy.put("block_pos", new NbtIntArray(new int[]{anchor[0] + dx, anchor[1] + dy, anchor[2] + dz}));
            }
            if (source.get("TileX") instanceof NbtInt tileX
                    && source.get("TileY") instanceof NbtInt tileY
                    && source.get("TileZ") instanceof NbtInt tileZ) {
                copy.putInt("TileX", tileX.intValue() + dx);
                copy.putInt("TileY", tileY.intValue() + dy);
                copy.putInt("TileZ", tileZ.intValue() + dz);
            }
        }
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(x));
        pos.add(NbtDouble.of(y));
        pos.add(NbtDouble.of(z));
        copy.put("Pos", pos);
        return copy;
    }
}
