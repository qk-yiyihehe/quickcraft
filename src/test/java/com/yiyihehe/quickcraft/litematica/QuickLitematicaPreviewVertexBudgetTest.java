package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewVertexBudgetTest {
    @Test
    void forcedBudgetIsScopedAndArrayGrowthDoesNotOverflow() {
        assertThat(QuickLitematicaPreviewVertexBudget.vertexLimit()).isEqualTo(16_000_000);
        assertThatThrownBy(() -> QuickLitematicaPreviewVertexBudget.checkVertexCount(16_000_000))
                .isInstanceOf(QuickLitematicaPreview3D.PreviewTooLargeException.class);
        assertThatThrownBy(() -> QuickLitematicaPreviewVertexBudget.run(true, () -> {
            QuickLitematicaPreviewVertexBudget.checkVertexCount(32_000_000);
            assertThat(QuickLitematicaPreviewVertexBudget.vertexLimit()).isEqualTo(Integer.MAX_VALUE);
            int grown = QuickLitematicaPreviewVertexBudget.growCapacity(1 << 30, (1 << 30) + 32);
            assertThat(grown).isEqualTo(QuickLitematicaPreviewVertexBudget.layerByteLimit()).isPositive();
            assertThat((long) grown / 32 * 44).isLessThan(Integer.MAX_VALUE);
            QuickLitematicaPreviewVertexBudget.run(false, () ->
                    assertThat(QuickLitematicaPreviewVertexBudget.vertexLimit()).isEqualTo(16_000_000));
            assertThat(QuickLitematicaPreviewVertexBudget.vertexLimit()).isEqualTo(Integer.MAX_VALUE);
            throw new IllegalStateException("restore even on failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(QuickLitematicaPreviewVertexBudget.isForced()).isFalse();
        assertThat(QuickLitematicaPreviewVertexBudget.vertexLimit()).isEqualTo(16_000_000);
    }
}
