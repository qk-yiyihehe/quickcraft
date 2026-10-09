package com.yiyihehe.quickcraft.litematica;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector3f;
import it.unimi.dsi.fastutil.ints.IntArrays;

final class QuickLitematicaPreviewQuadDepth {
    private boolean initialized;
    private float x, y, z;

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

    int[] sort(Vector3f[] points) {
        return this.sort(points.length, (index, target) -> target.set(points[index]));
    }

    int[] sort(int count, QuickLitematicaPreviewTransparentOrder.CenterReader reader) {
        return this.sort(count, reader, new int[count], new int[count]);
    }

    int[] sort(Vector3f[] points, int[] order, int[] keys) {
        return this.sort(points.length, (index, target) -> target.set(points[index]), order, keys);
    }

    int[] sort(int count, QuickLitematicaPreviewTransparentOrder.CenterReader reader, int[] order, int[] keys) {
        if (order.length != count || keys.length != count) {
            throw new IllegalArgumentException("Sort workspace must match the centroid count");
        }
        Vector3f point = new Vector3f();
        for (int i = 0; i < count; i++) {
            int bits = Float.floatToIntBits(-this.depth(reader.get(i, point)));
            // 与原生 Float.compare 距离降序一致，包括 NaN 和有符号零；稳定排序保留等深面的原顺序。
            keys[i] = ~(bits ^ ((bits >> 31) & 0x7FFFFFFF));
            order[i] = i;
        }
        // 小批次使用串行排序，避免争用公共线程池；大网格交给已有 fastutil 并行排序。
        if (count >= 262_144) IntArrays.parallelRadixSortIndirect(order, keys, true);
        else IntArrays.radixSortIndirect(order, keys, true);
        return order;
    }
}
