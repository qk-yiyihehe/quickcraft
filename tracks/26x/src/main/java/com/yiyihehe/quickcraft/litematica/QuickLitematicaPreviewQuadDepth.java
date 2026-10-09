package com.yiyihehe.quickcraft.litematica;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vertex.CompactVectorArray;
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

    int[] sort(CompactVectorArray points) {
        return this.sort(points, new int[points.size()], new int[points.size()]);
    }

    int[] sort(CompactVectorArray points, int[] order, int[] keys) {
        if (order.length != points.size() || keys.length != points.size()) {
            throw new IllegalArgumentException("Sort workspace must match the centroid count");
        }
        for (int i = 0; i < order.length; i++) {
            float distance = -(this.x * points.getX(i) + this.y * points.getY(i) + this.z * points.getZ(i));
            int bits = Float.floatToIntBits(distance);
            // 与原生 Float.compare 距离降序一致，包括 NaN 和有符号零；稳定排序保留等深面的原顺序。
            keys[i] = ~(bits ^ ((bits >> 31) & 0x7FFFFFFF));
            order[i] = i;
        }
        // 小批次使用串行排序，避免争用公共线程池；大网格交给已有 fastutil 并行排序。
        if (order.length >= 262_144) IntArrays.parallelRadixSortIndirect(order, keys, true);
        else IntArrays.radixSortIndirect(order, keys, true);
        return order;
    }
}
