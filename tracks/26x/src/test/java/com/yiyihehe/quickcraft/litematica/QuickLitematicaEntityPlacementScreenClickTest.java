package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-36：候选格命中允许格子边界内点击，左侧/上方 1~17 像素不得被整数除法截断成第 0 行/列。 */
class QuickLitematicaEntityPlacementScreenClickTest {
    private static final int LEFT = 10;
    private static final int TOP = 20;
    private static final int FIRST_CELL_X = LEFT + 8;
    private static final int FIRST_CELL_Y = TOP + 18;

    @Test
    void clicksOutsideLeftOrAboveFirstCellDoNotHit() {
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X - 1, FIRST_CELL_Y + 5, LEFT, TOP, 3))
                .isEqualTo(-1);
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X + 5, FIRST_CELL_Y - 1, LEFT, TOP, 3))
                .isEqualTo(-1);
    }

    @Test
    void firstCellTopLeftBoundaryHits() {
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X, FIRST_CELL_Y, LEFT, TOP, 1))
                .isZero();
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X + 15, FIRST_CELL_Y + 15, LEFT, TOP, 1))
                .isZero();
    }

    @Test
    void gapBetweenCellsAndRowsDoesNotHit() {
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X + 16, FIRST_CELL_Y + 5, LEFT, TOP, 18))
                .isEqualTo(-1);
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(
                LEFT + 8 + 18 * 9, FIRST_CELL_Y + 5, LEFT, TOP, 54)).isEqualTo(-1);
    }

    @Test
    void innerCellHitsExpectedIndexAndEmptyCandidatesMiss() {
        int secondRowSecondColumnX = LEFT + 8 + 18 + 5;
        int secondRowSecondColumnY = TOP + 18 + 18 + 5;
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(
                secondRowSecondColumnX, secondRowSecondColumnY, LEFT, TOP, 54)).isEqualTo(10);
        assertThat(QuickLitematicaEntityPlacementScreen.cellIndexAt(FIRST_CELL_X + 5, FIRST_CELL_Y + 5, LEFT, TOP, 0))
                .isEqualTo(-1);
    }
}
