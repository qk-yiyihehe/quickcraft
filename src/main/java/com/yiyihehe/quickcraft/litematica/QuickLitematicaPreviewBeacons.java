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
        new Initializer(world, states, entities).advance(System.nanoTime(), Long.MAX_VALUE);
    }

    /** 列表、列高和每十层的原生 tick 都可在同一预算下续跑；不在暂停期间保留 ThreadLocal。 */
    static final class Initializer {
        private final World world;
        private final java.util.function.LongSupplier clock;
        private final java.util.Iterator<BlockEntity> candidates;
        private final java.util.Iterator<BlockPos> positions;
        private final java.util.List<BeaconBlockEntity> beacons = new java.util.ArrayList<>();
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private int stage;
        private int index;
        private int ticksRemaining;
        private int top;

        private boolean done;

        Initializer(World world, Map<BlockPos, BlockState> states, Map<BlockPos, BlockEntity> entities) {
            this(world, states, entities, System::nanoTime);
        }

        Initializer(World world, Map<BlockPos, BlockState> states, Map<BlockPos, BlockEntity> entities,
                    java.util.function.LongSupplier clock) {
            this.world = world;
            this.clock = clock;
            this.candidates = entities.values().iterator();
            this.positions = states.keySet().iterator();
            this.tops.defaultReturnValue(Integer.MIN_VALUE);
        }

        boolean advance(long start, long budgetNanos) {
            if (this.done) return true;
            try (var phase = QuickLitematicaPreviewLog.phase("预览信标光柱初始化")) {
                while (this.clock.getAsLong() - start < budgetNanos) {
                    if (this.stage == 0) {
                        if (this.candidates.hasNext()) {
                            if (this.candidates.next() instanceof BeaconBlockEntity beacon) this.beacons.add(beacon);
                            continue;
                        }
                        if (this.beacons.isEmpty()) {
                            this.done = true;
                            return true;
                        }
                        this.stage++;
                    } else if (this.stage == 1) {
                        if (this.positions.hasNext()) {
                            BlockPos pos = this.positions.next();
                            long column = column(pos);
                            this.tops.put(column, Math.max(this.tops.get(column), pos.getY()));
                            continue;
                        }
                        this.stage++;
                    } else {
                        if (this.index == this.beacons.size()) {
                            this.done = true;

                            return true;
                        }
                        BeaconBlockEntity beacon = this.beacons.get(this.index);
                        BlockPos pos = beacon.getPos();
                        if (this.ticksRemaining == 0) {
                            this.top = Math.max(pos.getY(), this.tops.get(column(pos))) + 1;
                            // 额外 tick 先提交光柱分段，再计算基座等级，与同步初始化的次数一致。
                            this.ticksRemaining = (this.top - pos.getY()) / 10 + 2;
                        }
                        INITIALIZATION_TOP.set(this.top);
                        try {
                            BeaconBlockEntity.tick(this.world, pos, beacon.getCachedState(), beacon);
                        } finally {
                            INITIALIZATION_TOP.remove();
                        }
                        if (--this.ticksRemaining == 0) {

                            this.index++;
                        }
                    }
                }
                return false;
            }
        }
    }

    private static long column(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
    }
}
