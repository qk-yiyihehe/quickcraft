package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreview3DCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidatesBothCacheKindsWhenDfuSettingsChange() {
        String signature = QuickLitematicaPreviewCache.cacheSignature("pack", "always", 3700);
        assertThat(QuickLitematicaPreviewCache.cacheSignature("pack", "always", 3700)).isEqualTo(signature);
        assertThat(QuickLitematicaPreviewCache.cacheSignature("pack", "never", 3700)).isNotEqualTo(signature);
        assertThat(QuickLitematicaPreviewCache.cacheSignature("pack", "always", 4000)).isNotEqualTo(signature);
        assertThat(QuickLitematicaPreviewCache.cacheSignature("changed-pack", "always", 3700)).isNotEqualTo(signature);
    }

    @Test
    void preservesOversizedDynamicCacheAndReadsItWhenForced() throws Exception {
        Path cache = this.temporaryDirectory.resolve("forced.qcp3d");
        int count = QuickLitematicaPreview3D.MAX_DYNAMIC_ENTITIES + 1;
        var entity = new QuickLitematicaPreview3D.EntityData(0, 0, 0, new CompoundTag());
        var mesh = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), List.of(),
                Collections.nCopies(count, entity), 1, 1, 1);
        QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                this.temporaryDirectory.resolve("forced.tmp"), cache, mesh, mesh.layers(),
                new AtomicBoolean(), progress -> {});
        byte[] original = Files.readAllBytes(cache);

        assertThatThrownBy(() -> QuickLitematicaPreviewCache.CacheFile.read(cache, new AtomicBoolean()))
                .isInstanceOf(QuickLitematicaPreview3D.PreviewTooLargeException.class);
        assertThat(Files.readAllBytes(cache)).containsExactly(original);

        AtomicReference<QuickLitematicaPreview3D.MeshData> restored = new AtomicReference<>();
        QuickLitematicaPreviewVertexBudget.run(true, () -> {
            var loaded = QuickLitematicaPreviewCache.CacheFile.read(cache, new AtomicBoolean());
            assertThat(loaded).isNotNull();
            assertThat(loaded.withinBudget()).isTrue();
            restored.set(loaded);
        });
        assertThat(restored.get().forceRender).isTrue();
        assertThat(restored.get().entities()).hasSize(count);
        assertThat(QuickLitematicaPreviewVertexBudget.isForced()).isFalse();
    }

    @Test
    void hashesFileContentsInsteadOfFileMetadata() throws Exception {
        Path first = this.temporaryDirectory.resolve("first.litematic");
        Path second = this.temporaryDirectory.resolve("second.litematic");
        Files.write(first, new byte[]{1, 2, 3, 4});
        Files.write(second, new byte[]{1, 2, 3, 5});
        Files.setLastModifiedTime(second, Files.getLastModifiedTime(first));

        String firstHash = QuickLitematicaPreviewCache.hashFile(first);
        String secondHash = QuickLitematicaPreviewCache.hashFile(second);

        assertThat(firstHash).hasSize(64);
        assertThat(secondHash).hasSize(64).isNotEqualTo(firstHash);
    }

    @Test
    void keepsOneCacheSlotWhenTheSameProjectionChanges() throws Exception {
        Path schematic = this.temporaryDirectory.resolve("same-name.litematic");
        Files.write(schematic, new byte[]{1, 2, 3});
        String initialSlot = QuickLitematicaPreviewCache.cacheKey(schematic);

        Files.write(schematic, new byte[]{4, 5, 6, 7});

        assertThat(QuickLitematicaPreviewCache.cacheKey(schematic)).isEqualTo(initialSlot);
    }

    @Test
    @Timeout(10)
    void rejectsMatchingOversizedPendingMeshWithoutWaitingOrCancellingWrite() throws Exception {
        Path cache = this.temporaryDirectory.resolve("oversized-pending.qcp3d");
        CountDownLatch allowWrite = new CountDownLatch(1);
        var write = QuickLitematicaPreviewCache.writeAsync(cache, () -> {
            try {
                if (!allowWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Write timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }, "source", "pack", (long) QuickLitematicaPreviewVertexBudget.DEFAULT_VERTEX_LIMIT + 1);
        try {
            assertThatThrownBy(() -> QuickLitematicaPreviewCache.awaitPendingWrite(cache, "source", "pack"))
                    .isInstanceOf(QuickLitematicaPreview3D.PreviewTooLargeException.class);
            assertThat(write).isNotDone();
        } finally {
            allowWrite.countDown();
        }
        write.get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void cancellingACacheReaderKeepsThePendingWriteRunning() throws Exception {
        Path cache = this.temporaryDirectory.resolve("projection.qcp3d");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        var write = QuickLitematicaPreviewCache.writeAsync(cache, () -> {
            writeStarted.countDown();
            try {
                if (!allowWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Write timed out");
                Files.write(cache, new byte[]{1, 2, 3});
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CountDownLatch readerStarted = new CountDownLatch(1);
        FutureTask<Boolean> reader = new FutureTask<>(() -> {
            readerStarted.countDown();
            try {
                QuickLitematicaPreviewCache.awaitPendingWrite(cache);
                return false;
            } catch (CancellationException expected) {
                return Thread.currentThread().isInterrupted();
            }
        });
        Thread readerThread = new Thread(reader, "preview-cache-test-reader");
        try {
            assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 另一投影的读取无需等待当前投影的压缩。
            QuickLitematicaPreviewCache.awaitPendingWrite(this.temporaryDirectory.resolve("other.qcp3d"));
            readerThread.start();
            assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            readerThread.interrupt();
            assertThat(reader.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(write).isNotDone();
        } finally {
            allowWrite.countDown();
            readerThread.interrupt();
        }
        write.get(5, TimeUnit.SECONDS);
        QuickLitematicaPreviewCache.awaitPendingWrite(cache);
        assertThat(Files.readAllBytes(cache)).containsExactly(1, 2, 3);
    }
    @Test
    void conversionSignatureIsIndependentAndLegacyFallbackDoesNotOverrideIt() {
        var current = new QuickLitematicaPreviewCache.CacheIndexEntry("source", "pack");
        var stale = new QuickLitematicaPreviewCache.CacheIndexEntry("source", "old-pack");
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(current, null, "source", "pack")).isTrue();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(null, current, "source", "pack")).isTrue();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(stale, current, "source", "pack")).isFalse();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(current, current, "changed-source", "pack")).isFalse();
    }

    @Test
    @Timeout(10)
    void pendingMeshLoanSurvivesOwnerCloseAndWriterCompletion() throws Exception {
        Path cache = this.temporaryDirectory.resolve("borrowed.qcp3d");
        var store = new QuickLitematicaPreviewMeshStore(this.temporaryDirectory);
        byte[] vertices = new byte[QuickLitematicaPreview3D.QUANTIZED_VERTEX_BYTES * 4];
        vertices[0] = 42;
        var layer = store.store(new QuickLitematicaPreview3D.LayerMesh(QuickLitematicaPreview3D.LayerKey.SOLID, vertices));
        var snapshot = new QuickLitematicaPreview3D.MeshData(List.of(layer), List.of(), List.of(), List.of(), 1, 1, 1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        store.retainForCache();
        var write = QuickLitematicaPreviewCache.writeAsync(cache, () -> {
            try {
                if (!allowWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Write timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                snapshot.releaseStaticVertices();
                store.releaseCache();
            }
        }, "source", "pack", snapshot.vertexCount(), snapshot, store);
        try {
            store.close();
            assertThat(QuickLitematicaPreviewCache.borrowPendingMesh(cache, "other-source", "pack")).isNull();
            assertThat(QuickLitematicaPreviewCache.borrowPendingMesh(cache, "source", "other-pack")).isNull();
            var loan = QuickLitematicaPreviewCache.borrowPendingMesh(cache, "source", "pack");
            assertThat(loan).isNotNull();
            try (loan) {
                assertThat(write).isNotDone();
                allowWrite.countDown();
                write.get(5, TimeUnit.SECONDS);
                assertThat(snapshot.layers()).isEmpty();
                assertThat(loan.mesh.layers().getFirst().quantizedVertices()).containsExactly(vertices);
                loan.close();
            }
            assertThat(store.retainForRead()).isFalse();
        } finally {
            allowWrite.countDown();
            store.close();
        }
        write.get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void pendingMeshLoanUsesReadersDynamicBudgetAndReleasesRejectedLease() throws Exception {
        Path cache = this.temporaryDirectory.resolve("borrowed-budget.qcp3d");
        var store = new QuickLitematicaPreviewMeshStore(this.temporaryDirectory);
        var entity = new QuickLitematicaPreview3D.EntityData(0, 0, 0, new CompoundTag());
        var snapshot = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), List.of(),
                Collections.nCopies(QuickLitematicaPreview3D.MAX_DYNAMIC_ENTITIES + 1, entity), 1, 1, 1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        store.retainForCache();
        var write = QuickLitematicaPreviewCache.writeAsync(cache, () -> {
            try {
                if (!allowWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Write timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                store.releaseCache();
            }
        }, "source", "pack", snapshot.vertexCount(), snapshot, store);
        try {
            store.close();
            assertThatThrownBy(() -> QuickLitematicaPreviewCache.borrowPendingMesh(cache, "source", "pack"))
                    .isInstanceOf(QuickLitematicaPreview3D.PreviewTooLargeException.class)
                    .hasFieldOrPropertyWithValue("vertexLimit", false);
            QuickLitematicaPreviewVertexBudget.run(true, () -> {
                try (var loan = QuickLitematicaPreviewCache.borrowPendingMesh(cache, "source", "pack")) {
                    assertThat(loan).isNotNull();
                    assertThat(loan.mesh.forceRender).isTrue();
                    assertThat(loan.mesh.entities()).hasSize(QuickLitematicaPreview3D.MAX_DYNAMIC_ENTITIES + 1);
                }
            });
            assertThat(write).isNotDone();
        } finally {
            allowWrite.countDown();
            store.close();
        }
        write.get(5, TimeUnit.SECONDS);
        assertThat(store.retainForRead()).isFalse();
    }

}
