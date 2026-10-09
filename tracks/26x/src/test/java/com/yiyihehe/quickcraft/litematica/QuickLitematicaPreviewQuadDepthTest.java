package com.yiyihehe.quickcraft.litematica;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Random;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QuickLitematicaPreviewQuadDepthTest {
    @Test
    void radixOrderingMatchesNativeSortForSerialAndParallelInputs() {
        Random random = new Random(31);
        for (int count : new int[]{0, 4096, 262_144}) {
            var points = new CompactVectorArray(count);
            for (int i = 0; i < count; i++) {
                points.set(i, random.nextInt(64) - 32, random.nextInt(64) - 32, random.nextInt(64) - 32);
            }
            if (count > 0) {
                points.set(0, Float.NaN, 0, 0);
                points.set(1, Float.POSITIVE_INFINITY, 0, 0);
                points.set(2, Float.NEGATIVE_INFINITY, 0, 0);
                points.set(3, -0.0F, 0, -0.0F);
                points.set(4, 0.0F, 0, 0.0F);
            }
            var depth = new QuickLitematicaPreviewQuadDepth();
            depth.update(new Matrix4f().rotateX(0.37F).rotateY(0.83F));
            assertThat(depth.sort(points)).containsExactly(VertexSorting.byDistance(p -> -depth.depth(p)).sort(points));
            int[] order = new int[count], keys = new int[count];
            for (float angle : new float[]{0.83F, -0.43F, 2.1F}) {
                depth.update(new Matrix4f().rotateX(0.37F).rotateY(angle));
                assertThat(depth.sort(points, order, keys)).isSameAs(order)
                        .containsExactly(VertexSorting.byDistance(p -> -depth.depth(p)).sort(points));
            }
        }
    }

    @Test
    void reversesOrderingWhenRotatedAndSkipsStationaryAndPannedViews() {
        var depth = new QuickLitematicaPreviewQuadDepth();
        Matrix4f view = new Matrix4f();
        Vector3f front = new Vector3f(0, 0, 10), back = new Vector3f(0, 0, -10);
        assertThat(depth.update(view)).isTrue();
        assertThat(depth.depth(back)).isLessThan(depth.depth(front));
        assertThat(depth.update(view)).isFalse();
        assertThat(depth.update(new Matrix4f(view).translate(30, 20, 40))).isFalse();
        view.rotateY((float) Math.PI);
        assertThat(depth.update(view)).isTrue();
        assertThat(depth.depth(back)).isGreaterThan(depth.depth(front));
        assertThat(depth.update(view)).isFalse();
    }

    @Test
    void matchesActualModelViewDepthForTiltedAndInvertedPipAndSnapshotViews() {
        for (float scaleZ : new float[]{1, -1}) {
            Matrix4f view = new Matrix4f().translate(7, 13, 23).scale(2, 2, scaleZ)
                    .rotateX(0.37F).rotateY(0.83F).translate(-30, -40, -50);
            var depth = new QuickLitematicaPreviewQuadDepth();
            depth.update(view);
            Vector3f point = new Vector3f(3, 5, 7);
            float actualZ = view.transformPosition(new Vector3f(point)).z - view.m32();
            assertThat(depth.depth(point)).isCloseTo(actualZ, within(0.0001F));
        }
    }
}
