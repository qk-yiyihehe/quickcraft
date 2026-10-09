package com.yiyihehe.quickcraft.litematica;

import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;
import java.util.Map;

final class QuickLitematicaPreviewBeacons {
    // 原版 tick 每次扫描十层；只在独立假世界中初始化，时间固定为 80 的倍数以重算基座。
    private static final ThreadLocal<Integer> INITIALIZATION_TOP = new ThreadLocal<>();

    private QuickLitematicaPreviewBeacons() {
    }

    static boolean isInitializing() {
        return INITIALIZATION_TOP.get() != null;
    }

    static int initializationTop() {
        return INITIALIZATION_TOP.get();
    }

    static void initialize(World world, Map<BlockPos, BlockState> states, Map<BlockPos, BlockEntity> entities) {
        if (entities.values().stream().noneMatch(BeaconBlockEntity.class::isInstance)) return;
        var tops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        tops.defaultReturnValue(Integer.MIN_VALUE);
        states.forEach((pos, state) -> {
            long column = column(pos);
            tops.put(column, Math.max(tops.get(column), pos.getY()));
        });
        try (var phase = QuickLitematicaPreviewLog.phase("预览信标光柱初始化")) {
            for (BlockEntity entity : entities.values()) {
                if (!(entity instanceof BeaconBlockEntity beacon)) continue;
                BlockPos pos = beacon.getPos();
                int top = Math.max(pos.getY(), tops.get(column(pos))) + 1;
                INITIALIZATION_TOP.set(top);
                try {
                    // 完成扫描后额外 tick 一次：原版先提交光柱分段，再据分段计算基座等级。
                    int ticks = (top - pos.getY()) / 10 + 2;
                    for (int i = 0; i < ticks; i++) {
                        BeaconBlockEntity.tick(world, pos, beacon.getCachedState(), beacon);
                    }
                } finally {
                    INITIALIZATION_TOP.remove();
                }
            }
        }
    }

    private static long column(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
    }
}
