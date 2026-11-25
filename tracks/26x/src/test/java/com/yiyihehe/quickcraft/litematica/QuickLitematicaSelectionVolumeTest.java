package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuickLitematicaSelectionVolumeTest {
    @Test
    void singleRegionMultiplicationOverflowIsRejectedBeforeCapture() {
        AreaSelection selection = selection(List.of(box(0, 0, 0, 4_194_303, 4_194_303, 1_048_575)));

        assertThat(QuickLitematicaSelectionPreview.selectionVolume(selection)).isEqualTo(8_000_001L);
    }

    @Test
    void accumulatedRegionsCannotExceedTheCaptureLimit() {
        AreaSelection selection = selection(
                List.of(
                        box(0, 0, 0, 1_999, 999, 0),
                        box(0, 0, 2, 1_999, 999, 2),
                        box(0, 0, 4, 1_999, 999, 4),
                        box(0, 0, 6, 1_999, 999, 6),
                        box(0, 0, 8, 1_999, 999, 8)
                )
        );

        assertThat(QuickLitematicaSelectionPreview.selectionVolume(selection)).isEqualTo(8_000_001L);
    }

    @Test
    void reversedCornersUseTheSameAbsoluteSpan() {
        AreaSelection selection = selection(List.of(box(10, 20, 30, 0, 0, 0)));

        assertThat(QuickLitematicaSelectionPreview.selectionVolume(selection)).isEqualTo(7_161L);
    }

    @Test
    void exactLimitIsAccepted() {
        AreaSelection selection = selection(List.of(box(0, 0, 0, 1_999, 1_999, 1)));

        assertThat(QuickLitematicaSelectionPreview.selectionVolume(selection)).isEqualTo(8_000_000L);
    }

    @Test
    void smallSelectionIsStillCounted() {
        AreaSelection selection = selection(List.of(box(3, 4, 5, 4, 6, 8)));

        assertThat(QuickLitematicaSelectionPreview.selectionVolume(selection)).isEqualTo(24L);
    }

    private static AreaSelection selection(List<Box> boxes) {
        AreaSelection selection = mock(AreaSelection.class);
        when(selection.getAllSubRegionBoxes()).thenReturn(boxes);
        return selection;
    }

    private static Box box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), "test");
    }
}
