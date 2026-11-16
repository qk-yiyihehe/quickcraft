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
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Highlights already placed schematic containers with saved contents without comparing inventories. */
public final class QuickLitematicaContainerHighlight {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final Set<String> NON_INVENTORY_BLOCK_ENTITY_IDS = new HashSet<>();
    private static final Set<FailedBlockEntity> FAILED_BLOCK_ENTITIES = new HashSet<>();
    private static List<ProjectedContainer> positions = List.of();
    private static ClientLevel cachedWorld;
    private static int ticksUntilRefresh;

    private QuickLitematicaContainerHighlight() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaContainerHighlight::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(QuickLitematicaContainerHighlight::renderWorld);
    }

    private static void onClientTick(Minecraft client) {
        if (client.level == null || SchematicWorldHandler.getSchematicWorld() == null
                || !QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            positions = List.of();
            cachedWorld = client.level;
            ticksUntilRefresh = 0;
            FAILED_BLOCK_ENTITIES.clear();
            QuickLitematicaContainerFillState.clear();
            return;
        }

        if (client.level != cachedWorld) {
            cachedWorld = client.level;
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            FAILED_BLOCK_ENTITIES.clear();
            positions = collectContainerPositions(client);
        } else if (--ticksUntilRefresh <= 0) {
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            positions = collectContainerPositions(client);
        }
        QuickLitematicaContainerFillState.tick(client, positions);
    }

    private static List<ProjectedContainer> collectContainerPositions(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) { return List.of(); }
        Set<ProjectedContainer> found = new HashSet<>();
        Set<FailedBlockEntity> seenFailures = new HashSet<>();
        // 只在本轮刷新内复用同一原理图区域的解析；下一轮仍读取最新库存和失败指纹。
        var parsed = new java.util.IdentityHashMap<LitematicaSchematic, Map<String, List<ProjectedContainer>>>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isRenderingEnabled()) { continue; }
            LitematicaSchematic schematic = placement.getSchematic();
            for (SubRegionPlacement region : placement.getAllSubRegionsPlacements()) {
                if (!region.isEnabled() || !region.isRenderingEnabled()) { continue; }
                String regionName = region.getName();
                BlockPos size = schematic.getAreaSize(regionName);
                if (size == null) { continue; }
                var regions = parsed.computeIfAbsent(schematic, key -> new java.util.HashMap<>());
                var local = regions.computeIfAbsent(regionName,
                        key -> scanRegion(schematic, regionName, world, seenFailures));
                for (ProjectedContainer container : local) {
                    found.add(new ProjectedContainer(toWorldPos(container.pos(), size, placement, region), container.block()));
                }
            }
        }
        FAILED_BLOCK_ENTITIES.retainAll(seenFailures);
        return new ArrayList<>(found);
    }

    private static List<ProjectedContainer> scanRegion(LitematicaSchematic schematic, String regionName,
            ClientLevel world, Set<FailedBlockEntity> seenFailures) {
        List<ProjectedContainer> found = new ArrayList<>();
        Map<BlockPos, ?> blockEntities = schematic.getBlockEntityMapForRegion(regionName);
        LitematicaBlockStateContainer blocks = schematic.getSubRegionContainer(regionName);
        if (blockEntities == null || blocks == null) {
            return List.of();
        }

        for (Map.Entry<BlockPos, ?> entry : blockEntities.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(entry.getValue());
            BlockPos localPos = entry.getKey();
            BlockState state = blocks.get(localPos.getX(), localPos.getY(), localPos.getZ());
            nbt = QuickLitematicaSchematicData.prepareBlockEntity(nbt, state, localPos);
            if (nbt.contains("Items") && QuickLitematicaContainerMaterials.readItems(nbt, world.registryAccess()) == null) {
                continue;
            }
            if (NON_INVENTORY_BLOCK_ENTITY_IDS.contains(nbt.getString("id").orElse(""))) {
                continue;
            }
            FailedBlockEntity failed = new FailedBlockEntity(localPos, state, nbt);
            if (FAILED_BLOCK_ENTITIES.contains(failed)) {
                seenFailures.add(failed);
                continue;
            }

            BlockEntity blockEntity = BlockEntity.loadStatic(
                    localPos, state, nbt, world.registryAccess()
            );
            if (blockEntity == null) {
                // 单条 NBT 解析失败不能标记整个方块实体类型；后续正常条目仍需高亮。
                FAILED_BLOCK_ENTITIES.add(new FailedBlockEntity(localPos, state, nbt.copy()));
                seenFailures.add(failed);
                continue;
            }
            if (isConfirmedNonInventory(blockEntity)) {
                NON_INVENTORY_BLOCK_ENTITY_IDS.add(nbt.getString("id").orElse(""));
                continue;
            }
            Container inventory = (Container) blockEntity;
            if (!inventory.isEmpty()) {
                found.add(new ProjectedContainer(
                        localPos, state.getBlock()
                ));
            }
        }
        return found;
    }

    static boolean isConfirmedNonInventory(BlockEntity blockEntity) {
        return blockEntity != null && !(blockEntity instanceof Container);
    }

    private static BlockPos toWorldPos(
            BlockPos localPos, BlockPos regionSize, SchematicPlacement placement, SubRegionPlacement region
    ) {
        BlockPos regionPos = region.getPos();
        BlockPos end = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).offset(regionPos);
        BlockPos min = PositionUtils.getMinCorner(regionPos, end);
        BlockPos relative = min.subtract(regionPos).offset(localPos);
        relative = PositionUtils.getTransformedBlockPos(relative, placement.getMirror(), placement.getRotation());
        relative = PositionUtils.getTransformedBlockPos(relative, region.getMirror(), region.getRotation());
        return placement.getOrigin()
                .offset(PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation()))
                .offset(relative);
    }

    private static void renderWorld(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || positions.isEmpty()
                || !QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            return;
        }

        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        int maxDistance = (client.options.renderDistance().get() + 2) * 16;
        Color4f selectedColor = QuickCraftConfigs.ProjectionTools.PROJECTION_CONTAINER_HIGHLIGHT_COLOR.getColor();
        float verifierAlpha = (float) Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_ALPHA.getDoubleValue();
        QuickLitematicaContainerHighlightAccess.renderHighlights(
                client, positions, cameraPos, maxDistance, selectedColor, verifierAlpha
        );
    }

    record ProjectedContainer(BlockPos pos, Block block) {
    }

    private record FailedBlockEntity(BlockPos pos, BlockState state, CompoundTag nbt) {
    }
}
