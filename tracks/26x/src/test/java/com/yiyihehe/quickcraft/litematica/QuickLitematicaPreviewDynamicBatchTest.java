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
    void fullCollectionKeepsBlockAndEntityOrderAcrossSlices() {
        Map<BlockPos, BlockEntity> source = new LinkedHashMap<>();
        for (int i = 0; i < 35; i++) source.put(new BlockPos(i, 0, 0), null);
        var entities = new java.util.ArrayList<QuickLitematicaPreview3D.RenderedEntity>();
        for (int i = 0; i < 20; i++) entities.add(new QuickLitematicaPreview3D.RenderedEntity(null, i, 0, 0, 0));
        var scene = new QuickLitematicaPreview3D.DynamicScene(null, source, entities);
        var batch = new QuickLitematicaPreviewDynamicBatch<String>(scene, QuickLitematicaPreviewBatchBudget.MIN_SIZE);
        var positions = new java.util.ArrayList<BlockPos>();
        var collectedEntities = new java.util.ArrayList<QuickLitematicaPreview3D.RenderedEntity>();
        int slices = 0;
        boolean done;
        do {
            var current = batch.nextScene();
            if (slices++ == 0) assertThat(current.blockEntities()).hasSize(16);
            if (!current.blockEntities().isEmpty()) assertThat(collectedEntities).isEmpty();
            positions.addAll(current.blockEntities().keySet());
            collectedEntities.addAll(current.entities());
            done = batch.finishCollected(QuickLitematicaPreviewBatchBudget.TARGET_NANOS);
            assertThat(batch.allocatedBytes).isZero();
            assertThat(batch.progress()).isLessThan(1.0F);
        } while (!done);
        assertThat(slices).isEqualTo(4);
        assertThat(positions).containsExactlyElementsOf(source.keySet());
        assertThat(collectedEntities).containsExactlyElementsOf(entities);
        assertThat(source).hasSize(35);
    }

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
