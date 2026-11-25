package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 高亮投影中已经放置且记录了内容物的容器，省去逐槽比对。 */
public final class QuickLitematicaContainerHighlight {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final Set<String> NON_INVENTORY_BLOCK_ENTITY_IDS = new HashSet<>();
    private static final Set<FailedBlockEntity> FAILED_BLOCK_ENTITIES = new HashSet<>();
    private static List<ProjectedContainer> positions = List.of();
    private static World cachedWorld;
    private static int ticksUntilRefresh;

    private QuickLitematicaContainerHighlight() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaContainerHighlight::onClientTick);
        QuickLitematicaContainerHighlightAccess.registerWorldRenderer();
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.world == null
                || SchematicWorldHandler.getSchematicWorld() == null
                || !QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            positions = List.of();
            cachedWorld = client.world;
            ticksUntilRefresh = 0;
            FAILED_BLOCK_ENTITIES.clear();
            QuickLitematicaContainerFillState.clear();
            return;
        }

        if (client.world != cachedWorld) {
            cachedWorld = client.world;
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            FAILED_BLOCK_ENTITIES.clear();
            positions = collectContainerPositions(client);
        } else if (--ticksUntilRefresh <= 0) {
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            positions = collectContainerPositions(client);
        }
        QuickLitematicaContainerFillState.tick(client, positions);
    }

    private static List<ProjectedContainer> collectContainerPositions(MinecraftClient client) {
        World world = client.world;
        if (world == null) {
            return List.of();
        }
        Set<ProjectedContainer> found = new HashSet<>();
        Set<FailedBlockEntity> seenFailures = new HashSet<>();
        // 只在本轮刷新内复用同一原理图区域的解析；下一轮仍读取最新库存和失败指纹。
        var parsed = new java.util.IdentityHashMap<LitematicaSchematic, Map<String, List<ProjectedContainer>>>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isRenderingEnabled()) {
                continue;
            }
            LitematicaSchematic schematic = placement.getSchematic();
            for (SubRegionPlacement region : placement.getAllSubRegionsPlacements()) {
                if (!region.isEnabled() || !region.isRenderingEnabled()) {
                    continue;
                }
                String regionName = region.getName();
                BlockPos size = schematic.getAreaSize(regionName);
                if (size == null) {
                    continue;
                }
                var regions = parsed.computeIfAbsent(schematic, key -> new java.util.HashMap<>());
                var local = regions.computeIfAbsent(
                        regionName,
                        key -> scanRegion(schematic, regionName, world, seenFailures)
                );
                for (ProjectedContainer container : local) {
                    found.add(
                            new ProjectedContainer(
                                    toWorldPos(container.pos(), size, placement, region),
                                    container.block()
                            )
                    );
                }
            }
        }
        FAILED_BLOCK_ENTITIES.retainAll(seenFailures);
        return new ArrayList<>(found);
    }

    private static List<ProjectedContainer> scanRegion(
            LitematicaSchematic schematic,
            String regionName,
            World world,
            Set<FailedBlockEntity> seenFailures
    ) {
        List<ProjectedContainer> found = new ArrayList<>();
        Map<BlockPos, NbtCompound> blockEntities = QuickLitematicaContainerHighlightAccess.getBlockEntities(
                schematic,
                regionName
        );
        LitematicaBlockStateContainer blocks = schematic.getSubRegionContainer(regionName);
        if (blockEntities == null || blocks == null) {
            return List.of();
        }

        for (Map.Entry<BlockPos, NbtCompound> entry : blockEntities.entrySet()) {
            NbtCompound nbt = entry.getValue();
            if (nbt == null) {
                continue;
            }
            BlockPos localPos = entry.getKey();
            BlockState state = blocks.get(localPos.getX(), localPos.getY(), localPos.getZ());
            nbt = QuickLitematicaSchematicData.prepareBlockEntity(nbt, state, localPos);
            if (nbt.contains("Items") && QuickLitematicaContainerMaterials.readItems(
                    nbt,
                    world.getRegistryManager()
            ) == null) {
                continue;
            }
            String blockEntityId = QuickLitematicaContainerHighlightAccess.getBlockEntityId(nbt);
            if (NON_INVENTORY_BLOCK_ENTITY_IDS.contains(blockEntityId)) {
                continue;
            }
            FailedBlockEntity failed = new FailedBlockEntity(localPos, state, nbt);
            if (FAILED_BLOCK_ENTITIES.contains(failed)) {
                seenFailures.add(failed);
                continue;
            }

            BlockEntity blockEntity = BlockEntity.createFromNbt(localPos, state, nbt, world.getRegistryManager());
            if (blockEntity == null) {
                // 单条 NBT 解析失败不能标记整个方块实体类型；后续正常条目仍需高亮。
                FAILED_BLOCK_ENTITIES.add(new FailedBlockEntity(localPos, state, nbt.copy()));
                seenFailures.add(failed);
                continue;
            }
            if (isConfirmedNonInventory(blockEntity)) {
                NON_INVENTORY_BLOCK_ENTITY_IDS.add(blockEntityId);
                continue;
            }
            Inventory inventory = (Inventory) blockEntity;
            if (!inventory.isEmpty()) {
                found.add(new ProjectedContainer(localPos, state.getBlock()));
            }
        }
        return found;
    }

    static boolean isConfirmedNonInventory(BlockEntity blockEntity) {
        return blockEntity != null && !(blockEntity instanceof Inventory);
    }

    private static BlockPos toWorldPos(
            BlockPos localPos,
            BlockPos regionSize,
            SchematicPlacement placement,
            SubRegionPlacement region
    ) {
        BlockPos regionPos = region.getPos();
        BlockPos end = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
        BlockPos min = PositionUtils.getMinCorner(regionPos, end);
        BlockPos relative = min.subtract(regionPos).add(localPos);
        relative = PositionUtils.getTransformedBlockPos(relative, placement.getMirror(), placement.getRotation());
        relative = PositionUtils.getTransformedBlockPos(relative, region.getMirror(), region.getRotation());
        return placement.getOrigin().add(
                PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation())
        ).add(relative);
    }

    public static void renderWorld(MinecraftClient client, Camera camera) {
        if (!shouldRender(client)) {
            return;
        }
        renderWorld(client, QuickLitematicaContainerHighlightAccess.getCameraPos(camera));
    }

    public static void renderWorld(MinecraftClient client, Vec3d cameraPos) {
        if (!shouldRender(client)) {
            return;
        }
        int maxDistance = (client.options.getViewDistance().getValue() + 2) * 16;
        QuickLitematicaContainerHighlightAccess.renderHighlights(client, positions, cameraPos, maxDistance);
    }

    private static boolean shouldRender(MinecraftClient client) {
        return client.world != null && !positions.isEmpty()
                && QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                && Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && Configs.Visuals.ENABLE_SCHEMATIC_RENDERING
                        .getBooleanValue() && Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue();
    }

    static boolean shouldRenderContainer(
            MinecraftClient client,
            ProjectedContainer container,
            Vec3d camera,
            int maxDistance
    ) {
        BlockPos pos = container.pos();
        return Math.abs(pos.getX() - camera.x) <= maxDistance && Math.abs(pos.getZ() - camera.z) <= maxDistance
                && DataManager.getRenderLayerRange().isPositionWithinRange(pos) && client.world.isChunkLoaded(pos)
                && client.world.getBlockState(pos).getBlock() == container.block() && client.world.getBlockEntity(
                        pos
                ) instanceof Inventory && !QuickLitematicaContainerFillState.isFilled(pos);
    }

    record ProjectedContainer(BlockPos pos, Block block) {
    }

    private record FailedBlockEntity(BlockPos pos, BlockState state, NbtCompound nbt) {
    }
}
