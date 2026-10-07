package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewDynamicBatchTest {
    @Test
    void batchesVisitEveryPositionOnceAndKeepLateMaterialFailureVisible() {
        Map<BlockPos, BlockEntity> source = new LinkedHashMap<>();
        for (int i = 0; i < 1200; i++) source.put(new BlockPos(i, 0, 0), null);
        var scene = new QuickLitematicaPreview3D.DynamicScene(null, source, List.of());
        var batch = new QuickLitematicaPreviewDynamicBatch<String>(scene);
        var visited = new HashSet<BlockPos>();
        String layer = "same native layer";
        boolean done;
        do {
            var current = batch.nextScene();
            assertThat(current.blockEntities()).isNotEmpty();
            current.blockEntities().keySet().forEach(pos -> assertThat(visited.add(pos)).isTrue());
            batch.checkLayer(layer, true);
            done = batch.finish(visited.size() * 44L, QuickLitematicaPreviewBatchBudget.TARGET_NANOS);
            if (!done) assertThat(batch.progress()).isLessThan(1.0F);
        } while (!done);
        assertThat(visited).containsExactlyInAnyOrderElementsOf(source.keySet());
        assertThat(source).hasSize(1200);
        assertThatThrownBy(() -> batch.checkLayer(new String(layer), true))
                .isInstanceOf(QuickLitematicaPreviewDynamicBatch.IncompatibleException.class);
        assertThatThrownBy(() -> batch.checkLayer(layer, false))
                .isInstanceOf(QuickLitematicaPreviewDynamicBatch.IncompatibleException.class);
    }
}
