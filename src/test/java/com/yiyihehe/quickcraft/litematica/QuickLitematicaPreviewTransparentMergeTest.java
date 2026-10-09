package com.yiyihehe.quickcraft.litematica;

import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewTransparentMergeTest {
    @Test void centersKeepTheirVertexOffsetsAcrossBatches() {
        Vector3f[] first = {new Vector3f(1, 2, 3)}, second = {new Vector3f(4, 5, 6)};
        var merged = QuickLitematicaPreviewTransparentMerge.centers(List.of(first, second));
        assertThat(merged).containsExactly(first[0], second[0]);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
    }

    @Test void newGpuBuffersAndIndexRotationStayInsideTheBudget() {
        long budget = 256L * 1024 * 1024;
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(budget - 24, 1)).isEqualTo(6);
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(budget - 23, 1)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(0, Long.MAX_VALUE)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(-1, 1)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(budget - 72, 6)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(budget - 71, 6)).isFalse();
    }

    @Test void oversizedTransparentLayerStreamsWithoutDuplicatingItsVertices() {
        var stream = QuickLitematicaPreviewTransparentMerge.stream(21_920_768L, 28);
        assertThat(stream).isNotNull();
        assertThat(stream.vertexBytes).isEqualTo(613_781_504);
        assertThat(stream.indexCount).isEqualTo(32_881_152);
        assertThat(stream.byteOffset()).isZero();
        assertThatThrownBy(stream::requireComplete).isInstanceOf(IllegalStateException.class);
        stream.advance(250_000, 7_000_000);
        assertThat(stream.byteOffset()).isEqualTo(7_000_000);
        assertThat(stream.quadOffset()).isEqualTo(62_500);
        assertThatThrownBy(() -> stream.advance(3, 84)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stream.advance(4, 128)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stream.quadOffset()).isEqualTo(62_500);
        stream.advance(21_670_768, 606_781_504);
        stream.requireComplete();
        assertThat(stream.byteOffset()).isEqualTo(stream.vertexBytes);
        assertThatThrownBy(() -> stream.advance(4, 112)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void streamingKeepsSmallMergesAndPreservesExtremeForcedBatching() {
        assertThat(QuickLitematicaPreviewTransparentMerge.stream(0, 28)).isNull();
        assertThat(QuickLitematicaPreviewTransparentMerge.stream(1_000_000, 28)).isNull();
        assertThatThrownBy(() -> QuickLitematicaPreviewTransparentMerge.stream(5, 28))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(QuickLitematicaPreviewTransparentMerge.stream(100_000_000, 28)).isNull();
        assertThat(QuickLitematicaPreviewTransparentMerge.stream(Long.MAX_VALUE - 3, 28)).isNull();
    }
}
