package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreview3DCacheTest {
    @TempDir
    Path temporaryDirectory;

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
}
