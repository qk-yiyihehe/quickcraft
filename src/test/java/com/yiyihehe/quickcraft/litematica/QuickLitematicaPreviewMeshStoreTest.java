package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.QUANTIZED_VERTEX_BYTES;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.LayerKey;
class QuickLitematicaPreviewMeshStoreTest {
    @Test
    void releasesRawVerticesAndKeepsTheCacheLeaseAfterClosingPreview(@TempDir Path directory) throws Exception {
        byte[] first = new byte[QUANTIZED_VERTEX_BYTES * 4];
        first[3] = 23;
        byte[] second = first.clone();
        second[3] = 91;
        LayerMesh a = new LayerMesh(LayerKey.FLUID, first);
        LayerMesh b = new LayerMesh(LayerKey.SOLID, second);
        List<LayerMesh> retained = new ArrayList<>();
        QuickLitematicaPreviewMeshStore store = new QuickLitematicaPreviewMeshStore(directory);
        try {
            store.addBatch(retained, List.of(a), first.length, new AtomicBoolean());
            assertThat(retained.getFirst()).isSameAs(a);
            store.addBatch(retained, List.of(b), QuickLitematicaPreviewMeshStore.MEMORY_BYTES + 1, new AtomicBoolean());
            assertThat(retained).allSatisfy(mesh -> assertThat(mesh.vertices()).isNull());
            assertThat(retained.getFirst().vertexCount()).isEqualTo(4);
            assertThat(retained.getFirst().needsTranslucentSorting()).isTrue();
            assertThat(retained.getFirst().quantizedVertices()).isEqualTo(first);
            assertThat(retained.getLast().quantizedVertices()).isEqualTo(second);
            store.retainForCache();
            store.close();
            store.close();
            assertThat(retained.getFirst().quantizedVertices()).isEqualTo(first);
            assertThat(retained.getLast().quantizedVertices()).isEqualTo(second);
            assertThatThrownBy(store::retainForCache).isInstanceOf(CancellationException.class);
            store.releaseCache();
            try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
            assertThatThrownBy(() -> retained.getFirst().quantizedVertices()).isInstanceOf(java.io.UncheckedIOException.class);
        } finally { store.close(); }
    }

    @Test
    void readsLargeCachesIntoSpillStorageAndWritesTheSameVertexBytes(@TempDir Path directory) throws Exception {
        int quadBytes = QUANTIZED_VERTEX_BYTES * 4;
        byte[] vertices = new byte[((int) (QuickLitematicaPreviewMeshStore.MEMORY_BYTES / quadBytes) + 1) * quadBytes];
        vertices[vertices.length - 1] = 71;
        var original = new MeshData(List.of(new LayerMesh(LayerKey.SOLID, vertices)), List.of(), List.of(), List.of(), 1, 1, 1);
        Path cache = directory.resolve("source.qcp3d");
        QuickLitematicaPreviewCache.CacheFile.writeAtomically(directory.resolve("source.tmp"), cache,
                original, original.layers(), new AtomicBoolean(), value -> {});
        Path spillDirectory = Files.createDirectory(directory.resolve("spill"));
        try (var store = new QuickLitematicaPreviewMeshStore(spillDirectory)) {
            MeshData read = QuickLitematicaPreviewCache.CacheFile.read(cache, new AtomicBoolean(), store);
            assertThat(read).isNotNull();
            assertThat(read.vertexCount()).isEqualTo(vertices.length / QUANTIZED_VERTEX_BYTES);
            assertThat(read.layers()).allSatisfy(mesh -> assertThat(mesh.vertices()).isNull());
            int offset = 0;
            for (LayerMesh mesh : read.layers()) {
                byte[] bytes = mesh.quantizedVertices();
                assertThat(bytes).isEqualTo(java.util.Arrays.copyOfRange(vertices, offset, offset + bytes.length));
                offset += bytes.length;
            }
            Path rewritten = directory.resolve("rewritten.qcp3d");
            QuickLitematicaPreviewCache.CacheFile.writeAtomically(directory.resolve("rewritten.tmp"), rewritten,
                    read, read.layers(), new AtomicBoolean(), value -> {});
            assertThat(Files.readAllBytes(rewritten)).isEqualTo(Files.readAllBytes(cache));
        }
        try (var files = Files.list(spillDirectory)) { assertThat(files.count()).isZero(); }
    }

    @Test
    void cancellationDoesNotLeaveATemporaryFile(@TempDir Path directory) throws Exception {
        try (QuickLitematicaPreviewMeshStore store = new QuickLitematicaPreviewMeshStore(directory)) {
            LayerMesh mesh = new LayerMesh(LayerKey.SOLID, new byte[QUANTIZED_VERTEX_BYTES * 4]);
            assertThatThrownBy(() -> store.addBatch(new ArrayList<>(), List.of(mesh),
                    QuickLitematicaPreviewMeshStore.MEMORY_BYTES + 1, new AtomicBoolean(true)))
                    .isInstanceOf(CancellationException.class);
            try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
        }
    }
}
