package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

final class QuickLitematicaMaterialCountAccess {
    // 0.29.1 删除了四参数重载；同一声明范围仍支持 0.29.0，按实际可选依赖绑定一次。
    private static final MethodHandle BLOCK_COUNTS = findBlockCounts();
    private QuickLitematicaMaterialCountAccess() { }

    private static MethodHandle findBlockCounts() {
        var map = Object2IntOpenHashMap.class;
        var lookup = MethodHandles.publicLookup();
        try {
            try {
                var method = lookup.findStatic(MaterialListUtils.class, "getMaterialList",
                        MethodType.methodType(List.class, map, map, map, map, map, map, Player.class));
                return MethodHandles.insertArguments(method, 3,
                        new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>());
            } catch (NoSuchMethodException legacy) {
                return lookup.findStatic(MaterialListUtils.class, "getMaterialList",
                        MethodType.methodType(List.class, map, map, map, Player.class));
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Litematica material count API", exception);
        }
    }

    @SuppressWarnings("unchecked")
    static List<MaterialListEntry> blocks(Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch, Player player) {
        try {
            return (List<MaterialListEntry>) BLOCK_COUNTS.invokeExact(total, missing, mismatch, player);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to count Litematica block materials", exception);
        }
    }
}
