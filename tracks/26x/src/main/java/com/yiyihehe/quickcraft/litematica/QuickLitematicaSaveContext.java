package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** 保存的单次读取排除全局替换、保留手动替换，仍让其他模组执行调用点过滤。 */
public final class QuickLitematicaSaveContext {
    private static final ThreadLocal<Read> CURRENT = new ThreadLocal<>();

    private QuickLitematicaSaveContext() { }

    public static Read begin(Level world, BlockPos pos) {
        Read previous = CURRENT.get();
        CURRENT.set(new Read(world, pos.immutable()));
        return previous;
    }

    public static void end(Read previous) {
        if (previous == null) { CURRENT.remove(); }
        else { CURRENT.set(previous); }
    }

    public static boolean contains(Level world, BlockPos pos) {
        Read read = CURRENT.get();
        return read != null && read.world() == world && read.pos().equals(pos);
    }

    public record Read(Level world, BlockPos pos) { }
}
