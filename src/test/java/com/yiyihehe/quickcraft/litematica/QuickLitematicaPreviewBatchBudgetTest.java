package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewBatchBudgetTest {
    @Test
    void adaptsToMeasuredCostWithoutAbruptGrowthOrInvalidBounds() {
        assertThat(QuickLitematicaPreviewBatchBudget.nextSize(256, 1)).isEqualTo(512);
        assertThat(QuickLitematicaPreviewBatchBudget.nextSize(256, 32_000_000L)).isEqualTo(128);
        assertThat(QuickLitematicaPreviewBatchBudget.nextSize(256, QuickLitematicaPreviewBatchBudget.TARGET_NANOS)).isEqualTo(256);
        int size = QuickLitematicaPreviewBatchBudget.INITIAL_SIZE;
        for (int i = 0; i < 100; i++) size = QuickLitematicaPreviewBatchBudget.nextSize(size, Long.MAX_VALUE);
        assertThat(size).isEqualTo(QuickLitematicaPreviewBatchBudget.MIN_SIZE);
        for (int i = 0; i < 100; i++) size = QuickLitematicaPreviewBatchBudget.nextSize(size, 0);
        assertThat(size).isEqualTo(QuickLitematicaPreviewBatchBudget.MAX_SIZE);
    }
}
