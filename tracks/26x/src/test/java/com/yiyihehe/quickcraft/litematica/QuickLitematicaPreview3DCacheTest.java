package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

class QuickLitematicaPreview3DCacheTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
    }

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
    void recreatesPreviewWhenLauncherRecordedTheSamePathWithoutCreatingOne() {
        Path path = this.temporaryDirectory.resolve("same-path.litematic");
        assertThat(QuickLitematicaPreview3D.shouldSwitchPreviewSource(path, path, false)).isTrue();
        assertThat(QuickLitematicaPreview3D.shouldSwitchPreviewSource(path, path, true)).isFalse();
        assertThat(QuickLitematicaPreview3D.shouldSwitchPreviewSource(
                path, this.temporaryDirectory.resolve("other.litematic"), true)).isTrue();
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
                "struct-test", "pack-test", new AtomicBoolean(), progress -> {});
        byte[] original = Files.readAllBytes(cache);

        assertThatThrownBy(() -> QuickLitematicaPreviewCache.CacheFile.read(
                cache, new AtomicBoolean(), null, "struct-test", "pack-test"))
                .isInstanceOf(QuickLitematicaPreview3D.PreviewTooLargeException.class);
        assertThat(Files.readAllBytes(cache)).containsExactly(original);

        AtomicReference<QuickLitematicaPreview3D.MeshData> restored = new AtomicReference<>();
        QuickLitematicaPreviewVertexBudget.run(true, () -> {
            var loaded = QuickLitematicaPreviewCache.CacheFile.read(
                    cache, new AtomicBoolean(), null, "struct-test", "pack-test");
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
        }, "source", "pack", null, (long) QuickLitematicaPreviewVertexBudget.DEFAULT_VERTEX_LIMIT + 1, null, null);
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
        }, null, null, null, 0L, null, null);
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
    void dropsStaleConvertedBeforeSchematicReadFailsAfterMeshMiss() throws Exception {
        Path runDir = this.temporaryDirectory.resolve("stale-converted-run");
        Files.createDirectories(runDir);
        setStaticField("currentCacheDirectory", null);
        clearCacheIndex();
        Path cacheDir = runDir.resolve(QuickLitematicaPreviewCache.CACHE_DIR_NAME);
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_VERSION_FILE_NAME),
                QuickLitematicaPreviewCache.currentCacheVersionToken());
        Path source = this.temporaryDirectory.resolve("stale-source.litematic");
        Files.writeString(source, "new-content");
        String slot = QuickLitematicaPreviewCache.cacheKey(source);
        Path mesh = cacheDir.resolve(slot + ".qcp3d");
        Files.write(mesh, new byte[]{1, 2, 3});
        String staleFingerprint = "stale-structure";
        String staleSignature = "stale-pack";
        Path staleConverted = QuickLitematicaPreviewCache.convertedCachePath(
                cacheDir, slot, staleFingerprint, staleSignature);
        Files.writeString(staleConverted, "stale-converted");
        Path legacyConverted = cacheDir.resolve(slot + ".converted.litematic");
        Files.writeString(legacyConverted, "legacy-converted");
        Properties index = new Properties();
        index.setProperty(slot + ".path", source.toString());
        index.setProperty(slot + ".sourceHash", "old-source-hash");
        index.setProperty(slot + ".resourceSignature", "pack");
        index.setProperty(slot + ".structureFingerprint", "old-structure");
        index.setProperty(slot + ".convertedSourceHash", "old-source-hash");
        index.setProperty(slot + ".convertedResourceSignature", staleSignature);
        index.setProperty(slot + ".convertedStructureFingerprint", staleFingerprint);
        try (var output = Files.newOutputStream(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME))) {
            index.store(output, null);
        }

        QuickLitematicaPreview3D.Preview preview = mock(QuickLitematicaPreview3D.Preview.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        setField(preview, "sourcePath", source);
        setField(preview, "cachePath", mesh);
        setField(preview, "tmpPath", cacheDir.resolve(slot + ".qcp3d.tmp"));
        setField(preview, "cacheSlot", slot);
        setField(preview, "resourcePackSignature", "pack");
        setField(preview, "trace", new QuickLitematicaPreviewLog.Trace(source, 0L));
        setField(preview, "cancelled", new AtomicBoolean());
        setField(preview, "staticUploads", new QuickLitematicaPreviewUploadQueue());
        setField(preview, "staticMeshStore", new QuickLitematicaPreviewMeshStore());
        setField(preview, "backend", mock(QuickLitematicaPreviewBackend.class));

        try (var minecraft = mockStatic(net.minecraft.client.Minecraft.class);
             var schematicFiles = mockStatic(QuickLitematicaPreviewSchematicFiles.class);
             var render = mockStatic(com.mojang.blaze3d.systems.RenderSystem.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance)
                    .thenReturn(mock(net.minecraft.client.Minecraft.class));
            schematicFiles.when(() -> QuickLitematicaPreviewSchematicFiles.readSchematic(any(), any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("force-build-failure"));
            render.when(com.mojang.blaze3d.systems.RenderSystem::isOnRenderThread).thenReturn(false);

            var loadOrBuild = QuickLitematicaPreview3D.Preview.class.getDeclaredMethod("loadOrBuild");
            loadOrBuild.setAccessible(true);
            loadOrBuild.invoke(preview);

            assertThat(getField(preview, "state").toString()).isEqualTo("FAILED");
            assertThat(QuickLitematicaPreviewCache.readConvertedCacheIndexEntry(slot)).isNull();
            assertThat(staleConverted).doesNotExist();
            assertThat(legacyConverted).doesNotExist();
            // 网格索引和文件不属于转换清理范围，必须保留。
            assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry(slot)).isNotNull();
            assertThat(mesh).exists();
        } finally {
            setStaticField("currentCacheDirectory", null);
            clearCacheIndex();
        }
    }


    @Test
    void conversionSignatureRequiresFullIdentity() {
        var current = new QuickLitematicaPreviewCache.CacheIndexEntry("source", "pack", "structure");
        var stale = new QuickLitematicaPreviewCache.CacheIndexEntry("source", "old-pack", "structure");
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(current, "source", "pack")).isTrue();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(null, "source", "pack")).isFalse();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(stale, "source", "pack")).isFalse();
        assertThat(QuickLitematicaPreviewCache.convertedCacheMatches(current, "changed-source", "pack")).isFalse();
    }

    @Test
    void cacheFileHeaderRejectsAFileReplacedForAnotherStructure() throws Exception {
        Path cache = this.temporaryDirectory.resolve("identity.qcp3d");
        var mesh = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), List.of(), List.of(), 1, 1, 1);

        QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                this.temporaryDirectory.resolve("identity-a.tmp"), cache, mesh, mesh.layers(),
                "structure-a", "pack", new AtomicBoolean(), progress -> {});
        assertThat(QuickLitematicaPreviewCache.CacheFile.read(
                cache, new AtomicBoolean(), null, "structure-a", "pack")).isNotNull();

        QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                this.temporaryDirectory.resolve("identity-b.tmp"), cache, mesh, mesh.layers(),
                "structure-b", "pack", new AtomicBoolean(), progress -> {});
        assertThat(QuickLitematicaPreviewCache.CacheFile.read(
                cache, new AtomicBoolean(), null, "structure-a", "pack")).isNull();
        assertThat(cache).doesNotExist();
    }

    @Test
    void validCacheIndexKeepsItsEntriesAndArtifact() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("valid-cache");
        Files.createDirectories(cacheDir);
        Path source = this.temporaryDirectory.resolve("valid-source.litematic");
        Files.writeString(source, "source");
        Path cacheFile = cacheDir.resolve("slot1.qcp3d");
        Files.write(cacheFile, new byte[]{1, 2, 3});
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME),
                "slot1.path=" + source.toString().replace("\\", "\\\\") + "\n"
                        + "slot1.sourceHash=source\n"
                        + "slot1.resourceSignature=pack\n"
                        + "slot1.structureFingerprint=structure\n",
                java.nio.charset.StandardCharsets.UTF_8);

        QuickLitematicaPreviewCache.loadAndCleanCacheIndex(cacheDir);
        assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot1")).isNotNull();
        assertThat(cacheFile).exists();
    }

    @Test
    void initializationFailureRecoversWithoutClearingIndex() throws Exception {
        Path runDir = this.temporaryDirectory.resolve("run-dir");
        Files.createDirectories(runDir);
        Path blocked = runDir.resolve(QuickLitematicaPreviewCache.CACHE_DIR_NAME);
        Files.writeString(blocked, "not-a-directory");
        setStaticField("currentCacheDirectory", null);
        clearCacheIndex();

        assertThat(QuickLitematicaPreviewCache.cacheDirectory(runDir)).isEqualTo(blocked);
        assertThat((Path) getStaticField("currentCacheDirectory")).isNull();

        Files.delete(blocked);
        Path cacheDir = QuickLitematicaPreviewCache.cacheDirectory(runDir);
        assertThat(cacheDir).isDirectory();
        clearCacheIndex();

        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch allowHook = new CountDownLatch(1);
        QuickLitematicaPreviewCache.indexCommitHook = () -> {
            hookEntered.countDown();
            try {
                if (!allowHook.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("hook timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            try {
                setStaticField("currentCacheDirectory", null);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        Path source = this.temporaryDirectory.resolve("init-source.litematic");
        Files.writeString(source, "source");
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                QuickLitematicaPreviewCache.writeCacheIndexEntry(
                        "slot", source, "hash", "pack", "structure");
            } catch (Throwable failure) {
                writerFailure.set(failure);
            }
        });
        Thread probe = new Thread(() -> {
            try {
                QuickLitematicaPreviewCache.cacheDirectory(runDir);
            } catch (Throwable failure) {
                probeFailure.set(failure);
            }
        });
        try {
            writer.start();
            assertThat(hookEntered.await(5, TimeUnit.SECONDS)).isTrue();
            probe.start();
            allowHook.countDown();
            writer.join(5_000);
            probe.join(5_000);
            assertThat(writer.isAlive()).isFalse();
            assertThat(probe.isAlive()).isFalse();
            assertThat(writerFailure.get()).isNull();
            assertThat(probeFailure.get()).isNull();
            assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot")).isNotNull();
        } finally {
            QuickLitematicaPreviewCache.indexCommitHook = null;
            allowHook.countDown();
            writer.interrupt();
            probe.interrupt();
            setStaticField("currentCacheDirectory", null);
            clearCacheIndex();
        }
    }

    @Test
    void artifactWrittenAfterRecoveredInitializationSurvivesIndexCommit() throws Exception {
        Path runDir = this.temporaryDirectory.resolve("artifact-run");
        Files.createDirectories(runDir);
        Path cacheDir = runDir.resolve(QuickLitematicaPreviewCache.CACHE_DIR_NAME);
        Files.writeString(cacheDir, "blocked");
        setStaticField("currentCacheDirectory", null);
        clearCacheIndex();
        try {
            assertThat(QuickLitematicaPreviewCache.cacheDirectory(runDir)).isEqualTo(cacheDir);
            Files.delete(cacheDir);
            Files.createDirectories(cacheDir);
            assertThat(QuickLitematicaPreviewCache.ensureCacheDirectoryReady(runDir)).isTrue();

            Path source = this.temporaryDirectory.resolve("artifact-source.litematic");
            Files.writeString(source, "source");
            Path cacheFile = cacheDir.resolve("slot.qcp3d");
            var mesh = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), List.of(), List.of(), 1, 1, 1);
            QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                    cacheDir.resolve("slot.qcp3d.tmp"), cacheFile, mesh, mesh.layers(),
                    "structure", "pack", new AtomicBoolean(), progress -> {});
            QuickLitematicaPreviewCache.writeCacheIndexEntry(
                    "slot", source, "hash", "pack", "structure");

            assertThat(cacheFile).exists();
            assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot")).isNotNull();
            assertThat(QuickLitematicaPreviewCache.CacheFile.read(
                    cacheFile, new AtomicBoolean(), null, "structure", "pack")).isNotNull();
        } finally {
            setStaticField("currentCacheDirectory", null);
            clearCacheIndex();
        }
    }

    @Test
    void cacheVersionChangeClearsIndexAndArtifacts() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("versioned-cache");
        Files.createDirectories(cacheDir);
        Path source = this.temporaryDirectory.resolve("versioned-source.litematic");
        Files.writeString(source, "source");
        Path cacheFile = cacheDir.resolve("slot1.qcp3d");
        Files.write(cacheFile, new byte[]{1, 2, 3});
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_VERSION_FILE_NAME), "old-version");
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME),
                "slot1.path=" + source.toString().replace("\\", "\\\\") + "\n"
                        + "slot1.sourceHash=source\n"
                        + "slot1.resourceSignature=pack\n"
                        + "slot1.structureFingerprint=structure\n",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(QuickLitematicaPreviewCache.prepareCacheDirectory(cacheDir)).isTrue();
        assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot1")).isNull();
        assertThat(cacheFile).doesNotExist();
    }

    @Test
    void corruptCacheIndexDiscardsPartialEntriesAndUnboundCacheFiles() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("corrupt-cache");
        Files.createDirectories(cacheDir);
        Path source = this.temporaryDirectory.resolve("source.litematic");
        Files.writeString(source, "source");
        Path cacheFile = cacheDir.resolve("slot1.qcp3d");
        Path convertedFile = cacheDir.resolve("slot1-deadbeef.converted.litematic");
        Files.write(cacheFile, new byte[]{1, 2, 3});
        Files.write(convertedFile, new byte[]{4, 5, 6});
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_VERSION_FILE_NAME),
                QuickLitematicaPreviewCache.currentCacheVersionToken());
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME),
                "slot1.path=" + source.toString().replace("\\", "\\\\") + "\n"
                        + "slot1.sourceHash=source\n"
                        + "slot1.resourceSignature=pack\n"
                        + "slot1.structureFingerprint=structure\n"
                        + "slot1.convertedSourceHash=source\n"
                        + "slot1.convertedResourceSignature=pack\n"
                        + "slot1.convertedStructureFingerprint=structure\n"
                        + "broken=\\u12x4\n",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(QuickLitematicaPreviewCache.prepareCacheDirectory(cacheDir)).isTrue();
        assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot1")).isNull();
        assertThat(cacheFile).doesNotExist();
        assertThat(convertedFile).doesNotExist();

        Path laterSource = this.temporaryDirectory.resolve("later-source.litematic");
        Files.writeString(laterSource, "later");
        Path laterCache = cacheDir.resolve("slot2.qcp3d");
        Files.write(laterCache, new byte[]{7, 8, 9});
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME),
                "slot2.path=" + laterSource.toString().replace("\\", "\\\\") + "\n"
                        + "slot2.sourceHash=later\n"
                        + "slot2.resourceSignature=pack\n"
                        + "slot2.structureFingerprint=structure\n",
                java.nio.charset.StandardCharsets.UTF_8);
        QuickLitematicaPreviewCache.loadAndCleanCacheIndex(cacheDir);
        assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot2")).isNotNull();
        assertThat(laterCache).exists();
    }

    @Test
    void refreshRejectsSourceChangedWhileIndexIsBeingCommitted() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("refresh-cache");
        Files.createDirectories(cacheDir);
        Path source = this.temporaryDirectory.resolve("refresh-source.litematic");
        Files.writeString(source, "content-a");
        QuickLitematicaPreview3D.Preview preview = mock(QuickLitematicaPreview3D.Preview.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        setField(preview, "sourcePath", source);
        Path cachePath = cacheDir.resolve("slot.qcp3d");
        setField(preview, "cachePath", cachePath);
        setField(preview, "tmpPath", cacheDir.resolve("slot.qcp3d.tmp"));
        setField(preview, "cacheSlot", "slot");
        setField(preview, "resourcePackSignature", "pack");
        setField(preview, "trace", new QuickLitematicaPreviewLog.Trace(source, 0L));
        setField(preview, "structureFingerprint", "structure");

        setStaticField("currentCacheDirectory", cacheDir);
        QuickLitematicaPreviewCache.indexCommitHook = () -> {
            try {
                Files.writeString(source, "content-b");
            } catch (java.io.IOException failure) {
                throw new RuntimeException(failure);
            }
            QuickLitematicaPreviewCache.indexCommitHook = null;
        };
        try {
            String contentHash = QuickLitematicaPreviewCache.hashFile(source);
            boolean changed = preview.refreshCacheSourceHash(
                    new QuickLitematicaPreviewImageWriter.PreviewWriteResult("structure", contentHash));

            assertThat(changed).isTrue();
            assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry("slot")).isNull();
            assertThat((long) getField(preview, "sourceSize")).isEqualTo(-1L);
        } finally {
            QuickLitematicaPreviewCache.indexCommitHook = null;
            setStaticField("currentCacheDirectory", null);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getSuperclass() == QuickLitematicaPreview3D.Preview.class
                ? QuickLitematicaPreview3D.Preview.class.getDeclaredField(name)
                : target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        var field = QuickLitematicaPreview3D.Preview.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        var field = QuickLitematicaPreviewCache.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object getStaticField(String name) throws Exception {
        var field = QuickLitematicaPreviewCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void clearCacheIndex() throws Exception {
        var field = QuickLitematicaPreviewCache.class.getDeclaredField("CACHE_INDEX");
        field.setAccessible(true);
        ((java.util.Properties) field.get(null)).clear();
    }

    @Test
    void startupCleanupRemovesOnlyExactFastPreparationTempFilesAndKeepsIndexedCache() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("cache-cleanup");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("cache-version.txt"), QuickLitematicaPreviewCache.currentCacheVersionToken());

        Path source = this.temporaryDirectory.resolve("source.litematic");
        Files.writeString(source, "source");
        Path retained = cacheDir.resolve("kept.qcp3d");
        Files.writeString(retained, "mesh");
        String convertedFingerprint = "converted-structure";
        String convertedSignature = "converted-signature";
        Path retainedConverted = QuickLitematicaPreviewCache.convertedCachePath(
                cacheDir, "kept", convertedFingerprint, convertedSignature);
        Files.writeString(retainedConverted, "converted");
        java.util.Properties index = new java.util.Properties();
        index.setProperty("kept.path", source.toString());
        index.setProperty("kept.sourceHash", "source-hash");
        index.setProperty("kept.resourceSignature", "resource-signature");
        index.setProperty("kept.structureFingerprint", "structure-fingerprint");
        index.setProperty("kept.convertedSourceHash", "source-hash");
        index.setProperty("kept.convertedResourceSignature", convertedSignature);
        index.setProperty("kept.convertedStructureFingerprint", convertedFingerprint);
        try (var output = Files.newOutputStream(cacheDir.resolve("cache-index.properties"))) {
            index.store(output, null);
        }

        Path fastTemp = cacheDir.resolve("fast-123456.fast.tmp.litematic");
        Path freshFastTemp = cacheDir.resolve("fast-654321.fast.tmp.litematic");
        Path foreignFastName = cacheDir.resolve("fast-not-a-number.fast.tmp.litematic");
        Path foreignTmpName = cacheDir.resolve("my.fast.tmp.litematic");
        Path schematic = cacheDir.resolve("author-schematic.litematic");
        Files.writeString(fastTemp, "stale");
        Files.setLastModifiedTime(fastTemp, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - QuickLitematicaPreviewCache.FAST_TEMP_STALE_MILLIS - 60_000L
        ));
        Files.writeString(freshFastTemp, "in-use");
        Files.writeString(foreignFastName, "foreign");
        Files.writeString(foreignTmpName, "foreign");
        Files.writeString(schematic, "source");

        try {
            assertThat(QuickLitematicaPreviewCache.prepareCacheDirectory(cacheDir)).isTrue();
            assertThat(fastTemp).doesNotExist();
            assertThat(freshFastTemp).exists();
            assertThat(foreignFastName).exists();
            assertThat(foreignTmpName).exists();
            assertThat(schematic).exists();
            assertThat(retained).exists();
            assertThat(retainedConverted).exists();
        } finally {
            clearCacheIndex();
        }
    }

    @Test
    void stalePreviewImageWriteCallbackCannotClearNewerOperation() {
        var manager = new QuickLitematicaPreview3D.Manager(null);
        long first = manager.beginPreviewImageWrite();
        assertThat(first).isNotZero();
        manager.completePreviewImageWrite(first);
        long second = manager.beginPreviewImageWrite();
        assertThat(second).isNotZero();

        manager.completePreviewImageWrite(first);
        assertThat(manager.previewImageWriteInProgress()).isTrue();
        manager.completePreviewImageWrite(second);
        assertThat(manager.previewImageWriteInProgress()).isFalse();
    }

    @Test
    void guardedReplacePreservesExternalContentWhenTargetChanges() throws Exception {
        Path target = this.temporaryDirectory.resolve("target.litematic");
        Path temporary = this.temporaryDirectory.resolve("temporary.litematic");
        Files.writeString(target, "old");
        var snapshot = QuickLitematicaPreviewImageWriter.FileSnapshot.capture(target);
        Files.writeString(temporary, "preview");
        Files.writeString(target, "external-save");

        assertThatThrownBy(() -> QuickLitematicaPreviewImageWriter.replaceIfUnchanged(
                temporary, target, snapshot))
                .isInstanceOf(QuickLitematicaPreviewImageWriter.TargetChangedException.class);
        assertThat(Files.readString(target)).isEqualTo("external-save");
        assertThat(QuickLitematicaPreviewImageWriter.failureTranslationKey(
                new QuickLitematicaPreviewImageWriter.TargetChangedException()))
                .isEqualTo("quickcraft.litematica.preview_3d.preview_file_changed");
    }

    @Test
    void structureFingerprintIgnoresPreviewMetadataButTracksRegions() {
        CompoundTag metadataA = new CompoundTag();
        metadataA.putIntArray("PreviewImageData", new int[]{1, 2, 3});
        metadataA.putLong("TimeModified", 1L);
        CompoundTag metadataB = new CompoundTag();
        metadataB.putIntArray("PreviewImageData", new int[]{4, 5, 6});
        metadataB.putLong("TimeModified", 2L);
        CompoundTag regionsA = new CompoundTag();
        CompoundTag regionA = new CompoundTag();
        regionA.putString("Marker", "A");
        regionsA.put("region", regionA);
        CompoundTag regionsB = new CompoundTag();
        CompoundTag regionB = new CompoundTag();
        regionB.putString("Marker", "B");
        regionsB.put("region", regionB);

        String unchanged = this.structureFingerprint(metadataA, regionsA);
        assertThat(this.structureFingerprint(metadataB, regionsA)).isEqualTo(unchanged);
        assertThat(this.structureFingerprint(metadataA, regionsB)).isNotEqualTo(unchanged);
    }

    private String structureFingerprint(CompoundTag metadata, CompoundTag regions) {
        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", 3955);
        root.putInt("Version", 6);
        root.put("Metadata", metadata);
        root.put("Regions", regions);
        return QuickLitematicaPreviewSchematicFiles.structureFingerprint(root);
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
        }, "source", "pack", null, snapshot.vertexCount(), snapshot, store);
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
        }, "source", "pack", null, snapshot.vertexCount(), snapshot, store);
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


    @Test
    void validMeshHitAlsoDropsStaleConvertedIndexAndArtifact() throws Exception {
        Path runDir = this.temporaryDirectory.resolve("mesh-hit-run");
        Files.createDirectories(runDir);
        setStaticField("currentCacheDirectory", null);
        clearCacheIndex();
        Path cacheDir = runDir.resolve(QuickLitematicaPreviewCache.CACHE_DIR_NAME);
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_VERSION_FILE_NAME),
                QuickLitematicaPreviewCache.currentCacheVersionToken());
        Path source = this.temporaryDirectory.resolve("mesh-hit-source.litematic");
        Files.writeString(source, "mesh-hit-content");
        String slot = QuickLitematicaPreviewCache.cacheKey(source);
        String sourceHash = QuickLitematicaPreviewCache.hashFile(source);
        String meshFingerprint = "mesh-structure";
        Path mesh = cacheDir.resolve(slot + ".qcp3d");
        var meshData = new QuickLitematicaPreview3D.MeshData(List.of(), List.of(), List.of(), List.of(), 1, 1, 1);
        QuickLitematicaPreviewCache.CacheFile.writeAtomically(
                cacheDir.resolve(slot + ".qcp3d.tmp"), mesh, meshData, meshData.layers(),
                meshFingerprint, "pack", new AtomicBoolean(), progress -> {});
        String staleFingerprint = "stale-structure";
        String staleSignature = "stale-pack";
        Path staleConverted = QuickLitematicaPreviewCache.convertedCachePath(
                cacheDir, slot, staleFingerprint, staleSignature);
        Files.writeString(staleConverted, "stale-converted");
        Path legacyConverted = cacheDir.resolve(slot + ".converted.litematic");
        Files.writeString(legacyConverted, "legacy-converted");
        Properties index = new Properties();
        index.setProperty(slot + ".path", source.toString());
        index.setProperty(slot + ".sourceHash", sourceHash);
        index.setProperty(slot + ".resourceSignature", "pack");
        index.setProperty(slot + ".structureFingerprint", meshFingerprint);
        index.setProperty(slot + ".convertedSourceHash", "old-source-hash");
        index.setProperty(slot + ".convertedResourceSignature", staleSignature);
        index.setProperty(slot + ".convertedStructureFingerprint", staleFingerprint);
        try (var output = Files.newOutputStream(cacheDir.resolve(QuickLitematicaPreviewCache.CACHE_INDEX_FILE_NAME))) {
            index.store(output, null);
        }

        QuickLitematicaPreview3D.Preview preview = mock(QuickLitematicaPreview3D.Preview.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        setField(preview, "sourcePath", source);
        setField(preview, "cachePath", mesh);
        setField(preview, "tmpPath", cacheDir.resolve(slot + ".qcp3d.tmp"));
        setField(preview, "cacheSlot", slot);
        setField(preview, "resourcePackSignature", "pack");
        setField(preview, "trace", new QuickLitematicaPreviewLog.Trace(source, 0L));
        setField(preview, "cancelled", new AtomicBoolean());
        setField(preview, "staticUploads", new QuickLitematicaPreviewUploadQueue());
        setField(preview, "staticMeshStore", new QuickLitematicaPreviewMeshStore());
        setField(preview, "backend", mock(QuickLitematicaPreviewBackend.class));

        try (var minecraft = mockStatic(net.minecraft.client.Minecraft.class);
             var schematicFiles = mockStatic(QuickLitematicaPreviewSchematicFiles.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance)
                    .thenReturn(mock(net.minecraft.client.Minecraft.class));
            schematicFiles.when(() -> QuickLitematicaPreviewSchematicFiles.readSchematic(any(), any(), anyBoolean()))
                    .thenThrow(new AssertionError("mesh hit must not read schematic"));

            var loadOrBuild = QuickLitematicaPreview3D.Preview.class.getDeclaredMethod("loadOrBuild");
            loadOrBuild.setAccessible(true);
            loadOrBuild.invoke(preview);

            assertThat(getField(preview, "state").toString()).isEqualTo("READY");
            // 网格命中路径不能触发原理图读取；过期转换引用与工件仍应被清掉。
            schematicFiles.verify(() -> QuickLitematicaPreviewSchematicFiles.readSchematic(any(), any(), anyBoolean()),
                    org.mockito.Mockito.never());
            assertThat(QuickLitematicaPreviewCache.readConvertedCacheIndexEntry(slot)).isNull();
            assertThat(staleConverted).doesNotExist();
            assertThat(legacyConverted).doesNotExist();
            assertThat(QuickLitematicaPreviewCache.readCacheIndexEntry(slot)).isNotNull();
            assertThat(mesh).exists();
            assertThat(QuickLitematicaPreviewCache.CacheFile.read(
                    mesh, new AtomicBoolean(), null, meshFingerprint, "pack")).isNotNull();
        } finally {
            setStaticField("currentCacheDirectory", null);
            clearCacheIndex();
        }
    }

    @Test
    void staleConvertedSnapshotCleanupKeepsNewerEntryAndArtifact() throws Exception {
        Path cacheDir = this.temporaryDirectory.resolve("guard-cache");
        Files.createDirectories(cacheDir);
        setStaticField("currentCacheDirectory", cacheDir);
        clearCacheIndex();
        try {
            Path source = this.temporaryDirectory.resolve("guard-source.litematic");
            Files.writeString(source, "source");
            String slot = "slot-guard";
            QuickLitematicaPreviewCache.CacheIndexEntry stale = new QuickLitematicaPreviewCache.CacheIndexEntry(
                    "old-source", "old-signature", "old-fingerprint");
            Path oldArtifact = QuickLitematicaPreviewCache.convertedCachePath(
                    cacheDir, slot, stale.structureFingerprint(), stale.resourcePackSignature());
            Files.writeString(oldArtifact, "old-artifact");
            Path newArtifact = QuickLitematicaPreviewCache.convertedCachePath(
                    cacheDir, slot, "new-fingerprint", "new-signature");
            Files.writeString(newArtifact, "new-artifact");

            QuickLitematicaPreviewCache.writeConvertedCacheIndexEntry(
                    slot, source, "new-source", "new-signature", "new-fingerprint");
            QuickLitematicaPreviewCache.CacheIndexEntry newer = QuickLitematicaPreviewCache.readConvertedCacheIndexEntry(slot);
            assertThat(newer).isEqualTo(new QuickLitematicaPreviewCache.CacheIndexEntry(
                    "new-source", "new-signature", "new-fingerprint"));

            // 旧快照清理必须让位：新索引与该新工件保留，旧快照工件也不能误删。
            assertThat(QuickLitematicaPreviewCache.removeConvertedCacheEntry(cacheDir, slot, stale)).isFalse();
            assertThat(QuickLitematicaPreviewCache.readConvertedCacheIndexEntry(slot)).isEqualTo(newer);
            assertThat(newArtifact).exists();
            assertThat(oldArtifact).exists();

            assertThat(QuickLitematicaPreviewCache.removeConvertedCacheEntry(cacheDir, slot, newer)).isTrue();
            QuickLitematicaPreviewCache.writeCacheIndex(cacheDir);
            assertThat(QuickLitematicaPreviewCache.readConvertedCacheIndexEntry(slot)).isNull();
            assertThat(newArtifact).doesNotExist();
        } finally {
            setStaticField("currentCacheDirectory", null);
            clearCacheIndex();
        }
    }

}