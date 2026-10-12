package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewTransparentMergeTest {
    @Test void mergedCentersSortIndicesAcrossOriginalBatchBoundaries() {
        var a = centers(0, 10);
        var b = centers(5, 15);
        var merged = QuickLitematicaPreviewTransparentMerge.centers(List.of(a, b));
        assertThat(merged.size()).isEqualTo(4);
        assertThat(merged.get(0, new org.joml.Vector3f()).z).isEqualTo(0);
        assertThat(merged.get(2, new org.joml.Vector3f()).z).isEqualTo(5);
        assertThat(VertexSorting.byDistance(point -> -point.z()).sort(merged)).containsExactly(0, 2, 1, 3);
        assertThat(VertexSorting.byDistance(point -> point.z()).sort(merged)).containsExactly(3, 1, 2, 0);
        assertThat(a.get(1, new org.joml.Vector3f()).z).isEqualTo(10);
        assertThat(b.get(1, new org.joml.Vector3f()).z).isEqualTo(15);
    }

    @Test void additionalGpuBudgetIncludesNewIndicesAndRejectsOverflow() {
        long budget = 256L * 1024 * 1024;
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(budget - 24, 1)).isEqualTo(6);
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(budget - 23, 1)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(0, Long.MAX_VALUE)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(-1, 1)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(0, 0)).isZero();
        assertThat(QuickLitematicaPreviewTransparentMerge.indexCountWithinBudget(5_384_956L * 32, 5_384_956L / 4))
                .isEqualTo(8_077_434);
    }

    @Test void rotatingIndexBuffersStayInsideTheExistingGpuBudget() {
        long budget = 256L * 1024 * 1024;
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(budget - 72, 6)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(budget - 71, 6)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(150_778_768, 8_077_434)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(120_741_488, 6_468_294)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(-1, 6)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(0, Integer.MAX_VALUE)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(0, 0)).isFalse();
    }

    private static CompactVectorArray centers(float... depths) {
        var result = new CompactVectorArray(depths.length);
        for (int i = 0; i < depths.length; i++) result.set(i, 0, 0, depths[i]);
        return result;
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

    @Test void streamedIndexRotationBudgetsOnlyTheTwoAdditionalSlots() {
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(32_881_152)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(33_554_432)).isTrue();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(33_554_433)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(Integer.MAX_VALUE)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateStreamedIndices(0)).isFalse();
        assertThat(QuickLitematicaPreviewTransparentMerge.canRotateIndices(613_781_504, 32_881_152)).isFalse();
    }
}
