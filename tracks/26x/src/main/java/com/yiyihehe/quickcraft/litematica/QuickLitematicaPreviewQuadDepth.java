package com.yiyihehe.quickcraft.litematica;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import it.unimi.dsi.fastutil.ints.IntArrays;

final class QuickLitematicaPreviewQuadDepth {
    private final java.util.function.BooleanSupplier cancelled;
    private boolean initialized;
    private float x, y, z;

    QuickLitematicaPreviewQuadDepth() { this(null); }

    QuickLitematicaPreviewQuadDepth(java.util.function.BooleanSupplier cancelled) { this.cancelled = cancelled; }

    private void checkCancelled(int index) {
        if ((index & 0x7FF) == 0 && this.cancelled != null && this.cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    boolean initialized() { return this.initialized; }

    QuickLitematicaPreviewQuadDepth copy() {
        var copy = new QuickLitematicaPreviewQuadDepth();
        copy.initialized = this.initialized;
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        return copy;
    }

    boolean update(Matrix4fc view) {
        float nextX = view.m02(), nextY = view.m12(), nextZ = view.m22();
        if (this.initialized && this.x == nextX && this.y == nextY && this.z == nextZ) return false;
        this.initialized = true;
        this.x = nextX;
        this.y = nextY;
        this.z = nextZ;
        return true;
    }

    // 正交预览按视空间深度排序；平移不改变面片之间的顺序。
    float depth(Vector3fc point) { return this.x * point.x() + this.y * point.y() + this.z * point.z(); }

    int[] sort(CompactVectorArray points) {
        return this.sort(points, new int[points.size()], new int[points.size()]);
    }

    int[] sort(CompactVectorArray points, int[] order, int[] keys) {
        if (order.length != points.size() || keys.length != points.size()) {
            throw new IllegalArgumentException("Sort workspace must match the centroid count");
        }
        // 26.1.2 的 getZ 错读 Y；get(index, scratch) 在所有目标中返回正确坐标。
        var point = new org.joml.Vector3f();
        for (int i = 0; i < order.length; i++) {
            this.checkCancelled(i);
            float distance = -this.depth(points.get(i, point));
            int bits = Float.floatToIntBits(distance);
            // 与原生 Float.compare 距离降序一致，包括 NaN 和有符号零；稳定排序保留等深面的原顺序。
            keys[i] = ~(bits ^ ((bits >> 31) & 0x7FFFFFFF));
            order[i] = i;
        }
        // 小批次使用串行排序，避免争用公共线程池；大网格交给已有 fastutil 并行排序。
        if (this.cancelled != null) this.sortCancellable(order, keys);
        else if (order.length >= 262_144) IntArrays.parallelRadixSortIndirect(order, keys, true);
        else IntArrays.radixSortIndirect(order, keys, true);
        return order;
    }

    // fastutil 不提供运行中取消回调；后台任务使用同样的稳定整数基数排序，在有限分段间检查关闭标志。
    private void sortCancellable(int[] order, int[] keys) {
        int[] from = order, to = new int[order.length];
        int[] offsets = new int[256];
        for (int pass = 0; pass < 4; pass++) {
            java.util.Arrays.fill(offsets, 0);
            int shift = pass * 8, sign = pass == 3 ? 128 : 0;
            for (int i = 0; i < from.length; i++) {
                this.checkCancelled(i);
                offsets[((keys[from[i]] >>> shift) ^ sign) & 255]++;
            }
            int sum = 0;
            for (int i = 0; i < offsets.length; i++) {
                int count = offsets[i];
                offsets[i] = sum;
                sum += count;
            }
            for (int i = 0; i < from.length; i++) {
                this.checkCancelled(i);
                int index = from[i];
                to[offsets[((keys[index] >>> shift) ^ sign) & 255]++] = index;
            }
            int[] previous = from;
            from = to;
            to = previous;
        }
        // 四次交换后结果已回到调用方提供的 order，无需再复制整份索引。
    }
}
