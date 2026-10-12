package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import java.util.List;

final class QuickLitematicaMaterialCountAccess {
    private QuickLitematicaMaterialCountAccess() { }

    static List<MaterialListEntry> blocks(Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch, Player player) {
        return MaterialListUtils.getMaterialList(total, missing, mismatch, player);
    }
}
