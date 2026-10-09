package com.yiyihehe.quickcraft.litematica;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QuickLitematicaPreviewQuadDepthTest {
    @Test
    void radixOrderingMatchesStableFloatComparisonForSerialAndParallelInputs() {
        Random random = new Random(31);
        for (int count : new int[]{0, 4096, 262_144}) {
            Vector3f[] points = new Vector3f[count];
            for (int i = 0; i < count; i++) {
                points[i] = new Vector3f(random.nextInt(64) - 32, random.nextInt(64) - 32, random.nextInt(64) - 32);
            }
            if (count > 0) {
                points[0].set(Float.NaN, 0, 0);
                points[1].set(Float.POSITIVE_INFINITY, 0, 0);
                points[2].set(Float.NEGATIVE_INFINITY, 0, 0);
                points[3].set(-0.0F, 0, -0.0F);
                points[4].set(0.0F, 0, 0.0F);
            }
            var depth = new QuickLitematicaPreviewQuadDepth();
            depth.update(new Matrix4f().rotateX(0.37F).rotateY(0.83F));
            Integer[] expected = IntStream.range(0, count).boxed().toArray(Integer[]::new);
            Arrays.sort(expected, (a, b) -> Float.compare(-depth.depth(points[b]), -depth.depth(points[a])));
            assertThat(depth.sort(points)).containsExactly(Arrays.stream(expected).mapToInt(Integer::intValue).toArray());
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
