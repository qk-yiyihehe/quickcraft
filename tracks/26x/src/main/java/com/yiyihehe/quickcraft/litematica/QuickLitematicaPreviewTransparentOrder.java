package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class QuickLitematicaPreviewTransparentOrder {
    private final List<CompactVectorArray> centers = new ArrayList<>();
    private QuickLitematicaPreviewQuadDepth depth = new QuickLitematicaPreviewQuadDepth();
    private List<Run> runs = List.of();

    record Run(int buffer, int firstIndex, int indexCount) {}

    void add(CompactVectorArray points) {
        // 与原生 SortState 共用堆上的中心点，不复制顶点或再保留一套中心数组。
        this.centers.add(points);
        this.depth = new QuickLitematicaPreviewQuadDepth();
    }

    List<Run> runs(Matrix4fc modelView) {
        if (!this.depth.update(modelView)) return this.runs;
        if (this.centers.size() == 1) {
            return this.runs = List.of(new Run(0, 0, this.centers.getFirst().size() * 6));
        }
        try (var phase = QuickLitematicaPreviewLog.phase("跨批透明绘制顺序")) {
            List<float[]> sortedDepths = new ArrayList<>(this.centers.size());
            Vector3f point = new Vector3f();
            var queue = new PriorityQueue<Head>(Comparator.comparingDouble((Head h) -> h.value)
                    .reversed().thenComparingInt(h -> h.buffer));
            for (int buffer = 0; buffer < this.centers.size(); buffer++) {
                CompactVectorArray points = this.centers.get(buffer);
                float[] values = new float[points.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = -this.depth.depth(points.get(i, point));
                }
                Arrays.sort(values);
                sortedDepths.add(values);
                // 与批内距离降序使用同一口径，异常 NaN 深度也不能把绘制段对到错误的索引范围。
                if (values.length > 0) queue.add(new Head(buffer, 0, values[values.length - 1]));
            }
            List<Run> result = new ArrayList<>();
            int active = -1, first = 0, count = 0;
            while (!queue.isEmpty()) {
                Head head = queue.remove();
                if (active != head.buffer) {
                    if (count > 0) result.add(new Run(active, first, count));
                    active = head.buffer;
                    first = head.quad * 6;
                    count = 0;
                }
                count += 6;
                float[] values = sortedDepths.get(head.buffer);
                if (++head.quad < values.length) {
                    head.value = values[values.length - 1 - head.quad];
                    queue.add(head);
                }
            }
            if (count > 0) result.add(new Run(active, first, count));
            // ponytail: 合并超出 GPU 预算时保留分批，交错面仍会增加 draw call；扩大 arena 需要另设总显存预算。
            this.runs = List.copyOf(result);
            QuickLitematicaPreviewLog.LOGGER.info("透明全局绘制顺序完成：GPU批次={}，绘制段={}", this.centers.size(), result.size());
            return this.runs;
        }
    }

    void clear() {
        this.centers.clear();
        this.runs = List.of();
        this.depth = new QuickLitematicaPreviewQuadDepth();
    }

    private static final class Head {
        final int buffer;
        int quad;
        float value;
        Head(int buffer, int quad, float value) {
            this.buffer = buffer;
            this.quad = quad;
            this.value = value;
        }
    }
}
