package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.core.BlockPos;

/** 26.1.2 的 Litematica 放置部件包围盒位置判定：使用 containsPos。 */
final class QuickLitematicaPlacementAccess {
    private QuickLitematicaPlacementAccess() {
    }

    static boolean contains(SchematicPlacementManager.PlacementPart part, BlockPos pos) {
        return part.getBox().containsPos(pos);
    }
}
