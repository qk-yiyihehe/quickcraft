package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper.HitType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.HitResult;

public final class QuickLitematicaTargetReplacement {
    private QuickLitematicaTargetReplacement() {}

    public static void bindHotkey() {
        QuickCraftConfigs.Hotkeys.OPEN_TARGET_MATERIAL_REPLACEMENT.getKeybind().setCallback(
                (action, key) -> action == KeyAction.PRESS && open(Minecraft.getInstance())
        );
    }

    private static boolean open(Minecraft client) {
        if (client.player == null
                || client.level == null
                || QuickClientScreenAccess.currentScreen(client) != null
                || !QuickLitematicaContainerReplacements.enabled()
                || !Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            return false;
        }

        var world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) {
            return false;
        }

        var camera = QuickFreeCameraInteractions.getEasyPlaceTraceEntity(client, client.player);
        double range = WorldUtils.getValidBlockRange(client);
        boolean fluids = Configs.InfoOverlays.INFO_OVERLAYS_TARGET_FLUIDS.getBooleanValue();
        var trace = RayTraceUtils.getGenericTrace(client.level, camera, range, true, fluids, false);
        if (trace == null || trace.getBlockHitResult() == null) {
            return false;
        }

        BlockPos pos = trace.getBlockHitResult().getBlockPos();
        if (trace.getHitType() != HitType.SCHEMATIC_BLOCK) {
            // 已放置方块的碰撞形状可能比同坐标虚影更早命中。
            var projectedHit = RayTraceUtils.traceToSchematicWorld(camera, range, true, fluids);
            if (projectedHit == null
                    || projectedHit.getType() != HitResult.Type.BLOCK
                    || !projectedHit.getBlockPos().equals(pos)) {
                return false;
            }
        }

        var projectedState = world.getBlockState(pos);
        var location = QuickLitematicaGlobalReplacements.projectedLocation(pos, projectedState);
        if (location == null) {
            return false;
        }

        var placement = location.placement();
        var schematic = placement.getSchematic();
        var original = QuickLitematicaContainerMemory.original(schematic);
        var local = location.local();
        var region = location.region();
        var originalState = original.getSubRegionContainer(region).get(local.getX(), local.getY(), local.getZ());
        var context = QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement);
        var container = QuickLitematicaContainerMaterials.readBlockContainer(original, region, local);
        var group = container == null ? null : container.editableGroup(region);

        if (group != null || QuickLitematicaContainerMaterials.containerSize(originalState, local) > 0) {
            boolean placed = client.level.getBlockState(pos).getBlock() == projectedState.getBlock();
            if (!placed && container != null && container.second() != null) {
                var other = local.equals(container.first()) ? container.second() : container.first();
                var sub = placement.getRelativeSubRegionPlacement(region);
                var offset = PositionUtils.getTransformedPlacementPosition(other.subtract(local), placement, sub);
                var otherPos = pos.offset(offset);
                var otherState = world.getBlockState(otherPos);
                placed = !otherState.isAir()
                        && client.level.getBlockState(otherPos).getBlock() == otherState.getBlock();
            }

            if (placed) {
                InfoUtils.printActionbarMessage("quickcraft.litematica.message.target_container_placed");
                return true;
            }
            if (group == null) {
                InfoUtils.printActionbarMessage("quickcraft.litematica.message.target_container_unavailable");
                return true;
            }

            QuickLitematicaContainerReplacements.migrateContainerContext(
                    QuickLitematicaMaterialLists.getReplacementContextKey(placement),
                    context
            );
            GuiBase.openGui(new QuickLitematicaTargetContainerScreen(schematic, context, group));
            return true;
        }

        var source = QuickLitematicaProjectionMaterials.replacementSource(originalState);
        if (!(source.getItem() instanceof BlockItem)) {
            return false;
        }
        QuickLitematicaContainerReplacements.migrateProjectionContext(
                QuickLitematicaMaterialLists.getReplacementContextKey(placement),
                context
        );
        QuickLitematicaMaterialReplacementScreen.openFromWorld(
                source,
                QuickLitematicaProjectionMaterials.binding(placement, source),
                () -> QuickLitematicaContainerMaterials.saveCopy(schematic, context),
                () -> {
                    var list = DataManager.getMaterialList();
                    if (QuickLitematicaProjectionMaterials.schematic(list) == schematic) {
                        list.reCreateMaterialList();
                    }
                }
        );
        return true;
    }
}
