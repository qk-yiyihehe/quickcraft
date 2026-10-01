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

/** Highlights already placed schematic containers with saved contents without comparing inventories. */
public final class QuickLitematicaContainerHighlight {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final Set<String> NON_INVENTORY_BLOCK_ENTITY_IDS = new HashSet<>();
    private static List<ProjectedContainer> positions = List.of();
    private static World cachedWorld;
    private static int ticksUntilRefresh;

    private QuickLitematicaContainerHighlight() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaContainerHighlight::onClientTick);
        QuickLitematicaContainerHighlightAccess.registerWorldRenderer();
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.world == null || SchematicWorldHandler.getSchematicWorld() == null
                || !QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            positions = List.of();
            cachedWorld = client.world;
            ticksUntilRefresh = 0;
            return;
        }

        if (client.world != cachedWorld || --ticksUntilRefresh <= 0) {
            cachedWorld = client.world;
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
            positions = collectContainerPositions(client);
        }
    }

    private static List<ProjectedContainer> collectContainerPositions(MinecraftClient client) {
        World world = client.world;
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
                Map<BlockPos, NbtCompound> blockEntities = QuickLitematicaContainerHighlightAccess.getBlockEntities(schematic, regionName);
                LitematicaBlockStateContainer blocks = schematic.getSubRegionContainer(regionName);
                BlockPos size = schematic.getAreaSize(regionName);
                if (blockEntities == null || blocks == null || size == null) {
                    continue;
                }

                for (Map.Entry<BlockPos, NbtCompound> entry : blockEntities.entrySet()) {
                    NbtCompound nbt = entry.getValue();
                    if (nbt == null) {
                        continue;
                    }
                    String blockEntityId = QuickLitematicaContainerHighlightAccess.getBlockEntityId(nbt);
                    if (NON_INVENTORY_BLOCK_ENTITY_IDS.contains(blockEntityId)) {
                        continue;
                    }

                    BlockPos localPos = entry.getKey();
                    BlockState state = blocks.get(localPos.getX(), localPos.getY(), localPos.getZ());
                    BlockEntity blockEntity = BlockEntity.createFromNbt(
                            localPos, state, nbt, world.getRegistryManager()
                    );
                    if (!(blockEntity instanceof Inventory inventory)) {
                        NON_INVENTORY_BLOCK_ENTITY_IDS.add(blockEntityId);
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
        BlockPos end = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
        BlockPos min = PositionUtils.getMinCorner(regionPos, end);
        BlockPos relative = min.subtract(regionPos).add(localPos);
        relative = PositionUtils.getTransformedBlockPos(relative, placement.getMirror(), placement.getRotation());
        relative = PositionUtils.getTransformedBlockPos(relative, region.getMirror(), region.getRotation());
        return placement.getOrigin()
                .add(PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation()))
                .add(relative);
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
                && Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                && Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                && Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue();
    }

    static boolean shouldRenderContainer(
            MinecraftClient client, ProjectedContainer container, Vec3d camera, int maxDistance
    ) {
        BlockPos pos = container.pos();
        return Math.abs(pos.getX() - camera.x) <= maxDistance
                && Math.abs(pos.getZ() - camera.z) <= maxDistance
                && DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                && client.world.isChunkLoaded(pos)
                && client.world.getBlockState(pos).getBlock() == container.block()
                && client.world.getBlockEntity(pos) instanceof Inventory;
    }

    record ProjectedContainer(BlockPos pos, Block block) {
    }
}
