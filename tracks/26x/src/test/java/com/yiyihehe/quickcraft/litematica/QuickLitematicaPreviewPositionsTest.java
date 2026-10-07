package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewPositionsTest {
    @Test
    @Timeout(10)
    void densePositionsRemainCompleteAndSnapshotIsReadOnlyAndIndependent() {
        Map<BlockPos, Integer> source = new HashMap<>();
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    source.put(new BlockPos(x, y, z), x * 1024 + y * 32 + z);
                }
            }
        }
        Map<BlockPos, Integer> snapshot = QuickLitematicaPreview3D.copyPreviewPositions(source);
        assertThat(snapshot).hasSize(65_536).containsAllEntriesOf(source);
        source.put(new BlockPos(0, 0, 0), -1);
        source.clear();
        assertThat(snapshot.get(new BlockPos(0, 0, 0))).isEqualTo(0);
        assertThat(snapshot).hasSize(65_536);
        assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.entrySet().iterator().next().setValue(-1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
