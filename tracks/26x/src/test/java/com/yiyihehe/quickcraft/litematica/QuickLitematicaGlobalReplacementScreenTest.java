package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaGlobalReplacementScreenTest {
    @Test
    void partialLastPageDoesNotShiftBackAndRepeatPreviousRows() {
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(7, 8, 7)).isEqualTo(7);
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(14, 15, 7)).isEqualTo(14);
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(7, 7, 7)).isZero();
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(-7, 8, 7)).isZero();
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(14, 0, 7)).isZero();
    }
}
