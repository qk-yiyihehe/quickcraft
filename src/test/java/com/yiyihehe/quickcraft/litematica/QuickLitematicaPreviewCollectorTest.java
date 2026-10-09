package com.yiyihehe.quickcraft.litematica;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.render.VertexConsumer;
import org.junit.jupiter.api.Test;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.STATIC_BATCH_TARGET_VERTICES;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.LayerKey;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewCollectorTest {
    @Test void streamsTransparentQuadsWithoutDiscardingCachedConsumersOrSmallOpaqueLayers() {
        var collector = new QuickLitematicaPreviewAccess.MeshCollector();
        VertexConsumer transparent = collector.consumerFor(LayerKey.FLUID);
        VertexConsumer opaque = collector.consumerFor(LayerKey.SOLID);
        record(opaque, 4);
        record(transparent, STATIC_BATCH_TARGET_VERTICES - 4);
        assertThat(collector.shouldPublishBatch()).isFalse();
        record(transparent, 8);
        assertThat(collector.shouldPublishBatch()).isTrue();

        var ready = collector.drainReadyMeshes();
        assertThat(ready).hasSize(1);
        assertThat(ready.getFirst().layer()).isEqualTo(LayerKey.TRANSLUCENT);
        assertThat(ready.getFirst().vertexCount()).isEqualTo(STATIC_BATCH_TARGET_VERTICES + 4);
        var batches = QuickLitematicaPreviewMeshBatches.split(ready.getFirst(), new AtomicBoolean());
        assertThat(batches).extracting(QuickLitematicaPreview3D.LayerMesh::vertexCount)
                .containsExactly(STATIC_BATCH_TARGET_VERTICES, 4);
        assertThat(collector.consumerFor(LayerKey.TRANSLUCENT)).isSameAs(transparent);
        assertThat(collector.shouldPublishBatch()).isFalse();

        record(transparent, 4);
        var tail = collector.drainAllMeshes();
        assertThat(tail).hasSize(2);
        assertThat(tail).allSatisfy(mesh -> assertThat(mesh.vertexCount()).isEqualTo(4));
        assertThat(collector.vertexCount).isEqualTo(STATIC_BATCH_TARGET_VERTICES + 12);
    }

    @Test void opaqueFlushLeavesAnIncompleteTransparentBatchForLater() {
        var collector = new QuickLitematicaPreviewAccess.MeshCollector();
        record(collector.consumerFor(LayerKey.PORTAL), 4);
        record(collector.consumerFor(LayerKey.SOLID), STATIC_BATCH_TARGET_VERTICES);
        assertThat(collector.shouldPublishBatch()).isTrue();
        assertThat(collector.drainReadyMeshes()).hasSize(1).allSatisfy(mesh -> assertThat(mesh.layer()).isEqualTo(LayerKey.SOLID));
        assertThat(collector.drainAllMeshes()).hasSize(1).allSatisfy(mesh -> {
            assertThat(mesh.layer()).isEqualTo(LayerKey.TRANSLUCENT);
            assertThat(mesh.vertexCount()).isEqualTo(4);
        });
    }

    private static void record(VertexConsumer consumer, int vertices) {
        for (int i = 0; i < vertices; i++) {
            consumer.vertex(i % 4, 0, i / 4, 0xFFFFFFFF, 0, 0, 0, 0, 0, 1, 0);
        }
    }
}
