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
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
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
        WorldRenderEvents.LAST.register(QuickLitematicaContainerHighlight::render);
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
                Map<BlockPos, NbtCompound> blockEntities = schematic.getBlockEntityMapForRegion(regionName);
                LitematicaBlockStateContainer blocks = schematic.getSubRegionContainer(regionName);
                BlockPos size = schematic.getAreaSize(regionName);
                if (blockEntities == null || blocks == null || size == null) {
                    continue;
                }

                for (Map.Entry<BlockPos, NbtCompound> entry : blockEntities.entrySet()) {
                    NbtCompound nbt = entry.getValue();
                    if (nbt == null || NON_INVENTORY_BLOCK_ENTITY_IDS.contains(nbt.getString("id").orElse(""))) {
                        continue;
                    }

                    BlockPos localPos = entry.getKey();
                    BlockState state = blocks.get(localPos.getX(), localPos.getY(), localPos.getZ());
                    BlockEntity blockEntity = BlockEntity.createFromNbt(
                            localPos, state, nbt, world.getRegistryManager()
                    );
                    if (!(blockEntity instanceof Inventory inventory)) {
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
        BlockPos end = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
        BlockPos min = PositionUtils.getMinCorner(regionPos, end);
        BlockPos relative = min.subtract(regionPos).add(localPos);
        relative = PositionUtils.getTransformedBlockPos(relative, placement.getMirror(), placement.getRotation());
        relative = PositionUtils.getTransformedBlockPos(relative, region.getMirror(), region.getRotation());
        return placement.getOrigin()
                .add(PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation()))
                .add(relative);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || positions.isEmpty()
                || !QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS.getBooleanValue()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            return;
        }

        Vec3d camera = context.camera().getPos();
        int maxDistance = (client.options.getViewDistance().getValue() + 2) * 16;
        Color4f selectedColor = QuickCraftConfigs.ProjectionTools.PROJECTION_CONTAINER_HIGHLIGHT_COLOR.getColor();
        float verifierAlpha = (float) Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_ALPHA.getDoubleValue();
        Color4f color = new Color4f(
                selectedColor.r, selectedColor.g, selectedColor.b, Math.min(selectedColor.a, verifierAlpha)
        );

        // MaLiLib's no-depth pipeline preserves visibility through walls without changing global render state.
        try (RenderContext render = new RenderContext(
                () -> "QuickCraft projected container highlight",
                MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH
        )) {
            BufferBuilder buffer = render.getBuilder();
            for (ProjectedContainer container : positions) {
                BlockPos pos = container.pos();
                if (Math.abs(pos.getX() - camera.x) > maxDistance
                        || Math.abs(pos.getZ() - camera.z) > maxDistance
                        || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                        || !client.world.isChunkLoaded(pos)
                        || client.world.getBlockState(pos).getBlock() != container.block()
                        || !(client.world.getBlockEntity(pos) instanceof Inventory)) {
                    continue;
                }
                RenderUtils.renderAreaSidesBatched(pos, pos, color, 0.002, buffer);
            }
            BuiltBuffer built = buffer.endNullable();
            if (built != null) {
                try (built) {
                    render.upload(built, false);
                    render.drawPost();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render projected containers", e);
        }
    }

    private record ProjectedContainer(BlockPos pos, Block block) {
    }
}
