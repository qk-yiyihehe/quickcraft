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
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
            return;
        }

        if (client.level != cachedWorld || --ticksUntilRefresh <= 0) {
            cachedWorld = client.level;
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            positions = collectContainerPositions(client);
        }
    }

    private static List<ProjectedContainer> collectContainerPositions(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) {
            return List.of();
        }
        Set<ProjectedContainer> found = new HashSet<>();
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
                Map<BlockPos, ?> blockEntities = schematic.getBlockEntityMapForRegion(regionName);
                LitematicaBlockStateContainer blocks = schematic.getSubRegionContainer(regionName);
                BlockPos size = schematic.getAreaSize(regionName);
                if (blockEntities == null || blocks == null || size == null) {
                    continue;
                }

                for (Map.Entry<BlockPos, ?> entry : blockEntities.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(entry.getValue());
                    if (NON_INVENTORY_BLOCK_ENTITY_IDS.contains(nbt.getString("id").orElse(""))) {
                        continue;
                    }

                    BlockPos localPos = entry.getKey();
                    BlockState state = blocks.get(localPos.getX(), localPos.getY(), localPos.getZ());
                    BlockEntity blockEntity = BlockEntity.loadStatic(
                            localPos, state, nbt, world.registryAccess()
                    );
                    if (!(blockEntity instanceof Container inventory)) {
                        NON_INVENTORY_BLOCK_ENTITY_IDS.add(nbt.getString("id").orElse(""));
                        continue;
                    }
                    if (!inventory.isEmpty()) {
                        found.add(new ProjectedContainer(
                                toWorldPos(localPos, size, placement, region), state.getBlock()
                        ));
                    }
                }
            }
        }
        return new ArrayList<>(found);
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
        int color = Math.round(Math.min(selectedColor.a, verifierAlpha) * 255.0f) << 24
                | Math.round(selectedColor.r * 255.0f) << 16
                | Math.round(selectedColor.g * 255.0f) << 8
                | Math.round(selectedColor.b * 255.0f);

        // 26.2 collects gizmos before executing the world frame; immediate drawing here is cleared by that frame.
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            for (ProjectedContainer container : positions) {
                BlockPos pos = container.pos();
                if (Math.abs(pos.getX() - cameraPos.x) > maxDistance
                        || Math.abs(pos.getZ() - cameraPos.z) > maxDistance
                        || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                        || !client.level.hasChunkAt(pos)
                        || client.level.getBlockState(pos).getBlock() != container.block()
                        || !(client.level.getBlockEntity(pos) instanceof Container)) {
                    continue;
                }
                Gizmos.cuboid(new AABB(pos).inflate(0.002), GizmoStyle.fill(color)).setAlwaysOnTop();
            }
        }
    }

    private record ProjectedContainer(BlockPos pos, Block block) {
    }
}
