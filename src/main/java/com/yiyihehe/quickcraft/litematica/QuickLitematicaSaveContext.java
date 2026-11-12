package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 保存的单次读取排除全局替换、保留手动替换，仍让其他模组执行调用点过滤。 */
public final class QuickLitematicaSaveContext {
    private static final ThreadLocal<Read> CURRENT = new ThreadLocal<>();

    private QuickLitematicaSaveContext() { }

    public static Read begin(World world, BlockPos pos) {
        Read previous = CURRENT.get();
        CURRENT.set(new Read(world, pos.toImmutable()));
        return previous;
    }

    public static void end(Read previous) {
        if (previous == null) { CURRENT.remove(); }
        else { CURRENT.set(previous); }
    }

    public static boolean contains(World world, BlockPos pos) {
        Read read = CURRENT.get();
        return read != null && read.world() == world && read.pos().equals(pos)
                && isCurrentSchematicWorld(world);
    }

    public static boolean isCurrentSchematicWorld(World world) {
        return world != null && world == SchematicWorldHandler.getSchematicWorld();
    }

    public record Read(World world, BlockPos pos) { }
}
