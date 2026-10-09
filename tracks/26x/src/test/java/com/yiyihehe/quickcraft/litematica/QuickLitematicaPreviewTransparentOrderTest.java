package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import static org.assertj.core.api.Assertions.assertThat;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewTransparentOrder.Run;
class QuickLitematicaPreviewTransparentOrderTest {
    @Test
    void keepsNativeNanFirstOrderingAcrossBatchBoundaries() {
        var order = new QuickLitematicaPreviewTransparentOrder();
        order.add(centers(Float.NaN, 4));
        order.add(centers(2, Float.NaN));
        assertThat(order.runs(new Matrix4f())).containsExactly(new Run(0, 0, 6), new Run(1, 0, 12), new Run(0, 6, 6));
    }

    @Test
    void interleavesQuadsAcrossBuffersAndReversesOnRotation() {
        var order = new QuickLitematicaPreviewTransparentOrder();
        order.add(centers(0, 10));
        order.add(centers(5, 15));
        Matrix4f view = new Matrix4f();
        var first = order.runs(view);
        assertThat(first).containsExactly(new Run(0, 0, 6), new Run(1, 0, 6),
                new Run(0, 6, 6), new Run(1, 6, 6));
        assertThat(order.runs(new Matrix4f(view).translate(20, 30, 40))).isSameAs(first);
        view.rotateY((float) Math.PI);
        assertThat(order.runs(view)).containsExactly(new Run(1, 0, 6), new Run(0, 0, 6),
                new Run(1, 6, 6), new Run(0, 6, 6));
    }

    @Test
    void coalescesAdjacentFacesAndInvalidatesOnUploadAndRelease() {
        var order = new QuickLitematicaPreviewTransparentOrder();
        Matrix4f view = new Matrix4f();
        order.add(centers(2, 0, 1));
        assertThat(order.runs(view)).containsExactly(new Run(0, 0, 18));
        order.add(centers(6, 5));
        assertThat(order.runs(view)).containsExactly(new Run(0, 0, 18), new Run(1, 0, 12));
        order.clear();
        assertThat(order.runs(view)).isEmpty();
        order.add(centers(-10));
        assertThat(order.runs(view)).containsExactly(new Run(0, 0, 6));
    }

    private static com.mojang.blaze3d.vertex.CompactVectorArray centers(float... depths) {
        var points = new com.mojang.blaze3d.vertex.CompactVectorArray(depths.length);
        for (int i = 0; i < depths.length; i++) points.set(i, 0, 0, depths[i]);
        return points;
    }
}
