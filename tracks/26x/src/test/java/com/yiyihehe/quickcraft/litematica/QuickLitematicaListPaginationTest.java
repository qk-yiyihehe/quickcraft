package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaListPaginationTest {
    @Test
    void partialLastPageKeepsItsOwnStart() {
        var starts = QuickLitematicaListPagination.pageStarts(66, new int[]{22, 22, 22, 22});
        assertThat(starts).containsExactly(0, 3);
        assertThat(QuickLitematicaListPagination.pageFor(starts, 3)).isEqualTo(1);
        assertThat(QuickLitematicaListPagination.pageFor(starts, 99)).isEqualTo(1);
    }

    @Test
    void containerRowsUseTheirActualHeightsWithoutSkippingEntries() {
        assertThat(QuickLitematicaListPagination.pageStarts(70, new int[]{40, 30, 30, 40, 40}))
                .containsExactly(0, 2, 4);
        assertThat(QuickLitematicaListPagination.pageStarts(70, new int[]{30, 30}))
                .containsExactly(0);
    }

    @Test
    void shrinkingResultsAndTinyWindowsKeepAValidPage() {
        assertThat(QuickLitematicaListPagination.pageStarts(0, new int[]{40, 30})).containsExactly(0, 1);
        assertThat(QuickLitematicaListPagination.pageStarts(70, new int[]{})).containsExactly(0);
        assertThat(QuickLitematicaListPagination.pageFor(List.of(0), 99)).isZero();
        assertThat(QuickLitematicaListPagination.pageFor(List.of(0, 2, 4), -1)).isZero();
        assertThat(QuickLitematicaListPagination.pageFor(List.of(0, 2, 4), 3)).isEqualTo(1);
    }
}
