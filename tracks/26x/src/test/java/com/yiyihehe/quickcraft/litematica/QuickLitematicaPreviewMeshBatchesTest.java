package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewMeshBatchesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesWholeQuadsAndSourceOrderAcrossTransparentBatches() {
        int quads = STATIC_BATCH_TARGET_VERTICES / 4 + 3;
        byte[] vertices = vertices(quads);
        for (LayerKey layer : List.of(LayerKey.FLUID, LayerKey.PORTAL, LayerKey.TRANSLUCENT)) {
            var batches = QuickLitematicaPreviewMeshBatches.split(new LayerMesh(layer, vertices), new AtomicBoolean());
            assertThat(batches).hasSize(2);
            assertThat(batches.getFirst().vertexCount()).isEqualTo(STATIC_BATCH_TARGET_VERTICES);
            assertThat(batches.getLast().vertexCount()).isEqualTo(12);
            BitSet seen = new BitSet(quads);
            int nextId = 0;
            for (LayerMesh batch : batches) {
                assertThat(batch.layer()).isEqualTo(layer);
                assertThat(batch.needsTranslucentSorting()).isTrue();
                ByteBuffer bytes = ByteBuffer.wrap(batch.quantizedVertices());
                for (int offset = 0; offset < bytes.capacity(); offset += QUANTIZED_VERTEX_BYTES * 4) {
                    int id = bytes.getInt(offset + 12);
                    assertThat(id).isEqualTo(nextId++);
                    assertThat(seen.get(id)).isFalse();
                    seen.set(id);
                    assertThat(Arrays.copyOfRange(batch.quantizedVertices(), offset, offset + QUANTIZED_VERTEX_BYTES * 4))
                            .containsExactly(Arrays.copyOfRange(vertices, id * QUANTIZED_VERTEX_BYTES * 4, (id + 1) * QUANTIZED_VERTEX_BYTES * 4));

                }
            }
            assertThat(seen.cardinality()).isEqualTo(quads);
        }
    }

    @Test
    void splitsAnExistingSingleLayerCacheWithoutChangingItsFile() throws Exception {
        Path cache = this.temporaryDirectory.resolve("translucent.qcp3d");
        var original = new LayerMesh(LayerKey.FLUID, vertices(STATIC_BATCH_TARGET_VERTICES / 4 + 1));
        var mesh = new MeshData(List.of(original), List.of(), List.of(), List.of(), 1, 1, 1);
        QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                this.temporaryDirectory.resolve("translucent.tmp"), cache, mesh, mesh.layers(),
                new AtomicBoolean(), progress -> {});
        byte[] file = java.nio.file.Files.readAllBytes(cache);
        var restored = QuickLitematicaPreviewCache.CacheFile.read(cache, new AtomicBoolean());
        assertThat(restored).isNotNull();
        assertThat(restored.layers()).hasSize(2);
        assertThat(restored.layers()).allSatisfy(batch -> assertThat(batch.needsTranslucentSorting()).isTrue());
        assertThat(restored.layers().stream().mapToInt(LayerMesh::vertexCount).sum()).isEqualTo(original.vertexCount());
        assertThat(java.nio.file.Files.readAllBytes(cache)).containsExactly(file);
    }

    @Test
    void keepsSmallMeshesAndHonorsCancellation() {
        var small = new LayerMesh(LayerKey.FLUID, vertices(1));
        assertThat(small.needsTranslucentSorting()).isTrue();
        assertThat(QuickLitematicaPreviewMeshBatches.split(small, new AtomicBoolean())).containsExactly(small);
        var opaque = new LayerMesh(LayerKey.SOLID, small.quantizedVertices());
        assertThat(opaque.needsTranslucentSorting()).isFalse();
        assertThat(QuickLitematicaPreviewMeshBatches.split(opaque, new AtomicBoolean())).containsExactly(opaque);
        assertThatThrownBy(() -> QuickLitematicaPreviewMeshBatches.split(small, new AtomicBoolean(true)))
                .isInstanceOf(CancellationException.class);
    }

    private static byte[] vertices(int quads) {
        ByteBuffer bytes = ByteBuffer.allocate(quads * QUANTIZED_VERTEX_BYTES * 4);
        for (int quad = 0; quad < quads; quad++) {
            float x = ((quad * 17) % quads) / 2;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = (quad * 4 + vertex) * QUANTIZED_VERTEX_BYTES;
                bytes.putFloat(offset, x + (vertex == 0 ? 20 : vertex == 2 ? -20 : 100));
                bytes.putInt(offset + 12, quad);
                bytes.putInt(offset + 16, vertex);
            }
        }
        return bytes.array();
    }
}
