package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCompat.ctmRuntimeToken;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;

/** 预览磁盘缓存的索引、失效、原子写入和网格编码；任务与 GPU 生命周期由预览核心维护。 */
final class QuickLitematicaPreviewCache {
    private QuickLitematicaPreviewCache() {
    }

    static final int CACHE_MAGIC = 0x51435033; // QCP3
    static final String CACHE_DIR_NAME = "litematica-preview-cache";
    static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    static final String CACHE_INDEX_FILE_NAME = "cache-index.properties";
    static final String MESH_CACHE_MARKER = CACHE_RENDER_MARKER + "/entity-anchor-v1/light-uv-v1/identity-v2";
    static final int CACHE_IO_CHUNK_BYTES = 1024 * 1024;
    static final long FAST_TEMP_STALE_MILLIS = 60L * 60L * 1000L;
    private static final Object CACHE_DIRECTORY_LOCK = new Object();
    private static final Object CACHE_INDEX_LOCK = new Object();
    @Nullable
    static volatile Runnable indexCommitHook;
    private static final Properties CACHE_INDEX = new Properties();
    private static final ConcurrentHashMap<Path, PendingWrite> CACHE_WRITES = new ConcurrentHashMap<>();
    // 单写线程加一份等待快照；容量在入队前取得，拒绝时不覆盖已有同路径pending记录。
    private static final java.util.concurrent.Semaphore CACHE_WRITE_SLOTS = new java.util.concurrent.Semaphore(2);
    private static final ExecutorService CACHE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "QuickCraft-Preview3D-Cache");
        thread.setDaemon(true);
        return thread;
    });
    @Nullable
    private static volatile Path currentCacheDirectory;

    static CompletableFuture<Void> writeAsync(Path cachePath, Runnable write,
            @Nullable String sourceHash, @Nullable String resourceSignature, @Nullable String structureFingerprint,
            long vertices, @Nullable MeshData mesh, @Nullable QuickLitematicaPreviewMeshStore store) {
        if (!CACHE_WRITE_SLOTS.tryAcquire()) {
            throw new java.util.concurrent.RejectedExecutionException("Preview cache writer is busy");
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            MeshData reusable = mesh == null ? null : copyMesh(mesh);
            PendingWrite pending = new PendingWrite(completion, sourceHash, resourceSignature, structureFingerprint,
                    vertices, reusable, store);
            CACHE_WRITES.put(cachePath, pending);
            try {
                CACHE_EXECUTOR.execute(() -> {
                    Throwable failure = null;
                    try {
                        write.run();
                    } catch (Throwable e) {
                        failure = e;
                        LOGGER.error("Failed to save 3D preview cache {}", cachePath, e);
                    } finally {
                        CACHE_WRITES.remove(cachePath, pending);
                        CACHE_WRITE_SLOTS.release();
                    }
                    if (failure == null) completion.complete(null);
                    else completion.completeExceptionally(failure);
                });
            } catch (RuntimeException | Error failure) {
                CACHE_WRITES.remove(cachePath, pending);
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            CACHE_WRITE_SLOTS.release();
            completion.completeExceptionally(failure);
            throw failure;
        }
        return completion;
    }

    static void awaitPendingWrite(Path cachePath) {
        awaitPendingWrite(cachePath, null, null);
    }

    static void awaitPendingWrite(Path cachePath, @Nullable String sourceHash, @Nullable String resourceSignature) {
        PendingWrite write = CACHE_WRITES.get(cachePath);
        CompletableFuture<Void> pending = write == null ? null : write.completion();
        if (pending == null) {
            LOGGER.info("没有同投影未提交写入：路径={}", cachePath);
            return;
        }
        if (sourceHash != null && sourceHash.equals(write.sourceHash())
                && resourceSignature != null && resourceSignature.equals(write.resourceSignature())
                && write.vertices() > QuickLitematicaPreviewVertexBudget.vertexLimit()) {
            LOGGER.info("待保存网格已超过当前顶点预算：顶点={}，上限={}，不等待压缩保存，路径={}",
                    write.vertices(), QuickLitematicaPreviewVertexBudget.vertexLimit(), cachePath);
            throw new PreviewTooLargeException(!QuickLitematicaPreviewVertexBudget.isForced());
        }
        LOGGER.info("等待同投影保存：路径={}，已完成={}", cachePath, pending.isDone());
        try {
            // 只等待当前投影的保存；取消读取不会取消独立的写入任务。
            pending.get();
            LOGGER.info("同投影保存等待结束：路径={}", cachePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("缓存读取等待取消：路径={}，独立写入继续={}", cachePath, !pending.isDone());
            throw new CancellationException();
        } catch (ExecutionException ignored) {
            // 写入失败已记录，读取流程继续检查磁盘并按需重建。
        }
    }

    @Nullable
    static MeshLease borrowPendingMesh(Path cachePath, String sourceHash, String resourceSignature) {
        PendingWrite pending = CACHE_WRITES.get(cachePath);
        if (pending == null || !sourceHash.equals(pending.sourceHash())
                || !resourceSignature.equals(pending.resourceSignature())
                || pending.mesh() == null || pending.store() == null) return null;
        if (pending.vertices() > QuickLitematicaPreviewVertexBudget.vertexLimit()) {
            throw new PreviewTooLargeException(!QuickLitematicaPreviewVertexBudget.isForced());
        }
        if (!pending.store().retainForRead()) return null;
        try {
            // 独立列表归新预览所有；共享只读记录，不复制完整顶点，也不复用旧预览的强制预算。
            MeshData mesh = copyMesh(pending.mesh());
            if (!mesh.withinBudget()) throw new PreviewTooLargeException(false);
            LOGGER.info("pending-mesh-reuse-v1：复用待保存网格，跳过等待压缩及缓存解压，顶点={}，路径={}", mesh.vertexCount(), cachePath);
            return new MeshLease(mesh, pending.structureFingerprint(), pending.store());
        } catch (RuntimeException | Error failure) {
            pending.store().releaseCache();
            throw failure;
        }
    }

    private static MeshData copyMesh(MeshData mesh) {
        return new MeshData(List.copyOf(mesh.layers()), mesh.blockStates, mesh.blockEntities, mesh.entities,
                mesh.sizeX(), mesh.sizeY(), mesh.sizeZ());
    }

    static final class MeshLease implements AutoCloseable {
        final MeshData mesh;
        @Nullable final String structureFingerprint;
        private final QuickLitematicaPreviewMeshStore store;
        private final AtomicBoolean closed = new AtomicBoolean();

        private MeshLease(MeshData mesh, @Nullable String structureFingerprint, QuickLitematicaPreviewMeshStore store) {
            this.mesh = mesh;
            this.structureFingerprint = structureFingerprint;
            this.store = store;
        }

        @Override public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.mesh.releaseStaticVertices();
                this.store.releaseCache();
            }
        }
    }

    private record PendingWrite(CompletableFuture<Void> completion, @Nullable String sourceHash,
            @Nullable String resourceSignature, @Nullable String structureFingerprint, long vertices,
            @Nullable MeshData mesh, @Nullable QuickLitematicaPreviewMeshStore store) {}

    static String currentCacheSignature() {
        return cacheSignature(currentResourcePackSignature() + ":" + QuickLitematicaPreviewResources.signature(),
                fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_MODE.getOptionListValue().getStringValue(),
                fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue());
    }

    static String cacheSignature(String resourcePackSignature, String dataFixerMode, int defaultDataVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, resourcePackSignature);
            // 同一投影仍保留一个缓存槽；DFU 设置变化使其中的转换文件和网格失效。
            updateDigest(digest, dataFixerMode);
            updateDigest(digest, Integer.toString(defaultDataVersion));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String cacheKey(Path sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, sourcePath.toAbsolutePath().normalize().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String hashFile(Path path) throws IOException {
        return hashFileCancellable(path, new AtomicBoolean());
    }

    static String hashFileCancellable(Path path, AtomicBoolean cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        byte[] buffer = new byte[CACHE_IO_CHUNK_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException();
                }
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static Path cacheDirectory() {
        Path cacheDir = currentCacheDirectory;
        if (cacheDir != null) {
            return cacheDir;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return cacheDirectory(client.runDirectory.toPath());
    }

    static Path cacheDirectory(Path runDirectory) {
        Path cacheDir = currentCacheDirectory;
        if (cacheDir != null) {
            return cacheDir;
        }

        synchronized (CACHE_DIRECTORY_LOCK) {
            cacheDir = currentCacheDirectory;
            if (cacheDir != null) {
                return cacheDir;
            }

            cacheDir = runDirectory.resolve(CACHE_DIR_NAME);
            // 初始化失败后若已有索引写入，不能在索引修改期间重新加载/清理它。
            if (hasIndexEntries()) {
                return cacheDir;
            }
            if (prepareCacheDirectory(cacheDir)) {
                currentCacheDirectory = cacheDir;
            }
            return cacheDir;
        }
    }

    static boolean ensureCacheDirectoryReady() {
        cacheDirectory();
        return currentCacheDirectory != null;
    }

    static boolean ensureCacheDirectoryReady(Path runDirectory) {
        cacheDirectory(runDirectory);
        return currentCacheDirectory != null;
    }

    private static boolean hasIndexEntries() {
        synchronized (CACHE_INDEX_LOCK) {
            return !CACHE_INDEX.isEmpty();
        }
    }

    static boolean prepareCacheDirectory(Path cacheDir) {
        QuickLitematicaPreviewLog.file("缓存目录初始化", cacheDir);
        try {
            Files.createDirectories(cacheDir);
            Path versionFile = cacheDir.resolve(CACHE_VERSION_FILE_NAME);
            String currentVersion = currentCacheVersionToken();
            String storedVersion = readCacheVersion(versionFile);
            LOGGER.info("缓存目录版本核验：目录={}，保存版本={}，当前版本={}", cacheDir, storedVersion, currentVersion);
            if (!currentVersion.equals(storedVersion)) {
                LOGGER.info("缓存目录整体失效：原因=cache_version_changed，旧={}，新={}", storedVersion, currentVersion);
                clearRenderCacheFiles(cacheDir);
                Files.writeString(versionFile, currentVersion, java.nio.charset.StandardCharsets.UTF_8);
            }
            loadAndCleanCacheIndex(cacheDir);
            return true;
        } catch (IOException failure) {
            LOGGER.warn("预览缓存目录初始化失败，保留可重试状态：目录={}", cacheDir, failure);
            synchronized (CACHE_INDEX_LOCK) {
                CACHE_INDEX.clear();
            }
            return false;
        }
    }

    static void loadAndCleanCacheIndex(Path cacheDir) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
            Properties loaded = new Properties();
            boolean corruptIndex = false;
            if (Files.isRegularFile(indexPath)) {
                try (InputStream input = new BufferedInputStream(Files.newInputStream(indexPath))) {
                    loaded.load(input);
                } catch (IllegalArgumentException failure) {
                    corruptIndex = true;
                    LOGGER.warn("预览缓存索引解析失败，清空索引并重建：目录={}", cacheDir, failure);
                }
            }

            CACHE_INDEX.clear();
            if (corruptIndex) {
                // 损坏索引中的半成品条目不可信；缓存文件没有独立身份时也不能继续复用。
                clearRenderCacheFiles(cacheDir);
                Files.writeString(cacheDir.resolve(CACHE_VERSION_FILE_NAME), currentCacheVersionToken(),
                        java.nio.charset.StandardCharsets.UTF_8);
                writeCacheIndex(cacheDir);
                return;
            }
            CACHE_INDEX.putAll(loaded);

            Set<String> retainedCacheFiles = new java.util.HashSet<>();
            List<String> staleSlots = new ArrayList<>();
            for (String key : CACHE_INDEX.stringPropertyNames()) {
                if (!key.endsWith(".path")) {
                    continue;
                }
                String slot = key.substring(0, key.length() - ".path".length());
                String source = CACHE_INDEX.getProperty(key, "");
                Path cachePath = cacheDir.resolve(slot + ".qcp3d");
                String meshFingerprint = CACHE_INDEX.getProperty(slot + ".structureFingerprint");
                String convertedFingerprint = CACHE_INDEX.getProperty(slot + ".convertedStructureFingerprint");
                String convertedSignature = CACHE_INDEX.getProperty(slot + ".convertedResourceSignature");
                boolean sourceExists;
                try {
                    sourceExists = !source.isBlank() && Files.isRegularFile(Path.of(source));
                } catch (RuntimeException e) {
                    sourceExists = false;
                }
                boolean hasMesh = meshFingerprint != null && !meshFingerprint.isBlank();
                boolean hasConverted = convertedFingerprint != null && !convertedFingerprint.isBlank()
                        && convertedSignature != null && !convertedSignature.isBlank();
                if (!sourceExists || (!hasMesh && !hasConverted)) {
                    LOGGER.info("缓存槽失效：原因=source_missing_invalid_or_legacy_index，槽={}，原路径={}", slot, source);
                    staleSlots.add(slot);
                } else {
                    if (hasMesh && Files.isRegularFile(cachePath)) {
                        retainedCacheFiles.add(cachePath.getFileName().toString());
                    }
                    if (hasConverted) {
                        Path convertedPath = convertedCachePath(cacheDir, slot, convertedFingerprint, convertedSignature);
                        if (Files.isRegularFile(convertedPath)) {
                            retainedCacheFiles.add(convertedPath.getFileName().toString());
                        }
                    }
                }
            }

            staleSlots.forEach(slot -> removeCacheIndexEntry(cacheDir, slot));
            try (var files = Files.list(cacheDir)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".tmp")
                                    || name.endsWith(".converted.tmp.litematic")
                                    || isStaleFastTemporary(path, name)
                                    || (name.endsWith(".qcp3d") || name.endsWith(".converted.litematic"))
                                    && !retainedCacheFiles.contains(name);
                        })
                        .forEach(QuickLitematicaPreviewCache::deleteQuietly);
            }
            writeCacheIndex(cacheDir);
        }
    }

    private static boolean isStaleFastTemporary(Path path, String name) {
        if (!QuickLitematicaPreviewSchematicFiles.isFastTemporaryName(name)) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis() + FAST_TEMP_STALE_MILLIS < System.currentTimeMillis();
        } catch (IOException ignored) {
            // 正在被其他进程使用或无法读取时间时不删除。
            return false;
        }
    }

    @Nullable
    static CacheIndexEntry readCacheIndexEntry(String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            String sourceHash = CACHE_INDEX.getProperty(slot + ".sourceHash");
            String resourceSignature = CACHE_INDEX.getProperty(slot + ".resourceSignature");
            String structureFingerprint = CACHE_INDEX.getProperty(slot + ".structureFingerprint");
            return sourceHash == null || sourceHash.isBlank()
                    || resourceSignature == null || resourceSignature.isBlank()
                    || structureFingerprint == null || structureFingerprint.isBlank()
                    ? null
                    : new CacheIndexEntry(sourceHash, resourceSignature, structureFingerprint);
        }
    }

    @Nullable
    static CacheIndexEntry readConvertedCacheIndexEntry(String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            String sourceHash = CACHE_INDEX.getProperty(slot + ".convertedSourceHash");
            String resourceSignature = CACHE_INDEX.getProperty(slot + ".convertedResourceSignature");
            String structureFingerprint = CACHE_INDEX.getProperty(slot + ".convertedStructureFingerprint");
            return sourceHash == null || sourceHash.isBlank()
                    || resourceSignature == null || resourceSignature.isBlank()
                    || structureFingerprint == null || structureFingerprint.isBlank()
                    ? null
                    : new CacheIndexEntry(sourceHash, resourceSignature, structureFingerprint);
        }
    }

    static boolean convertedCacheMatches(@Nullable CacheIndexEntry converted, String sourceHash, String resourceSignature) {
        return converted != null && sourceHash.equals(converted.sourceHash())
                && resourceSignature.equals(converted.resourcePackSignature());
    }

    static void writeConvertedCacheIndexEntry(String slot, Path sourcePath, String sourceHash,
                                               String resourceSignature, String structureFingerprint) throws IOException {
        if (!ensureCacheDirectoryReady()) {
            throw new IOException("Preview cache directory is not initialized");
        }
        Path cacheDir = currentCacheDirectory;
        synchronized (CACHE_INDEX_LOCK) {
            // 转换结果独立提交；网格超限或仍在压缩时也能复用已经完成的 DFU。
            CACHE_INDEX.setProperty(slot + ".path", sourcePath.toAbsolutePath().normalize().toString());
            CACHE_INDEX.setProperty(slot + ".convertedSourceHash", sourceHash);
            CACHE_INDEX.setProperty(slot + ".convertedResourceSignature", resourceSignature);
            CACHE_INDEX.setProperty(slot + ".convertedStructureFingerprint", structureFingerprint);
            runIndexCommitHook();
            writeCacheIndex(cacheDir);
        }
    }

    static void writeCacheIndexEntry(String slot, Path sourcePath, String sourceHash,
                                     String resourceSignature, String structureFingerprint) throws IOException {
        if (!ensureCacheDirectoryReady()) {
            throw new IOException("Preview cache directory is not initialized");
        }
        Path cacheDir = currentCacheDirectory;
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.setProperty(slot + ".path", sourcePath.toAbsolutePath().normalize().toString());
            CACHE_INDEX.setProperty(slot + ".sourceHash", sourceHash);
            CACHE_INDEX.setProperty(slot + ".resourceSignature", resourceSignature);
            CACHE_INDEX.setProperty(slot + ".structureFingerprint", structureFingerprint);
            LOGGER.info("缓存索引提交：槽={}，源={}，源哈希={}，资源签名={}，结构指纹={}",
                    slot, sourcePath, sourceHash, resourceSignature, structureFingerprint);
            runIndexCommitHook();
            writeCacheIndex(cacheDir);
        }
    }

    static Path convertedCachePath(Path cacheDir, String slot, String structureFingerprint, String resourceSignature) {
        return cacheDir.resolve(slot + "-" + artifactIdentity(structureFingerprint, resourceSignature)
                + ".converted.litematic");
    }

    private static String artifactIdentity(String structureFingerprint, String resourceSignature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, structureFingerprint);
            updateDigest(digest, resourceSignature);
            updateDigest(digest, currentCacheVersionToken());
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static void removeCacheIndexEntry(Path cacheDir, String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.remove(slot + ".path");
            CACHE_INDEX.remove(slot + ".sourceHash");
            CACHE_INDEX.remove(slot + ".resourceSignature");
            CACHE_INDEX.remove(slot + ".structureFingerprint");
            CACHE_INDEX.remove(slot + ".convertedSourceHash");
            CACHE_INDEX.remove(slot + ".convertedResourceSignature");
            CACHE_INDEX.remove(slot + ".convertedStructureFingerprint");
            deleteQuietly(cacheDir.resolve(slot + ".qcp3d"));
            deleteQuietly(cacheDir.resolve(slot + ".qcp3d.tmp"));
            deleteQuietly(cacheDir.resolve(slot + ".converted.litematic"));
            deleteQuietly(cacheDir.resolve(slot + ".converted.tmp.litematic"));
            deleteConvertedCacheVariants(cacheDir, slot);
        }
    }

    static boolean removeConvertedCacheEntry(Path cacheDir, String slot, CacheIndexEntry stale) {
        synchronized (CACHE_INDEX_LOCK) {
            // 调用方持有的是读取时的快照；锁内再次核对，期间已提交的新条目不能被旧清理删除。
            CacheIndexEntry current = readConvertedCacheIndexEntry(slot);
            if (stale == null || !stale.equals(current)) {
                return false;
            }
            CACHE_INDEX.remove(slot + ".convertedSourceHash");
            CACHE_INDEX.remove(slot + ".convertedResourceSignature");
            CACHE_INDEX.remove(slot + ".convertedStructureFingerprint");
            // 只删该过期条目对应的指纹工件与旧名残留；其它变体由启动清理按索引引用回收。
            deleteQuietly(convertedCachePath(cacheDir, slot, stale.structureFingerprint(), stale.resourcePackSignature()));
            deleteQuietly(cacheDir.resolve(slot + ".converted.litematic"));
            deleteQuietly(cacheDir.resolve(slot + ".converted.tmp.litematic"));
            return true;
        }
    }

    private static void deleteConvertedCacheVariants(Path cacheDir, String slot) {
        try (var paths = Files.list(cacheDir)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(slot + "-")
                                && (name.endsWith(".converted.litematic")
                                || name.endsWith(".converted.tmp.litematic"));
                    })
                    .forEach(QuickLitematicaPreviewCache::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void runIndexCommitHook() {
        Runnable hook = indexCommitHook;
        if (hook != null) hook.run();
    }

    static void writeCacheIndex(Path cacheDir) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
            Path temporaryPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME + ".tmp");
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryPath))) {
                CACHE_INDEX.store(output, "QuickCraft Litematica 3D preview cache index");
            }
            moveCacheFile(temporaryPath, indexPath);
        }
    }

    record CacheIndexEntry(String sourceHash, String resourcePackSignature, String structureFingerprint) {
    }

    static String currentCacheVersionToken() {
        // 格式、渲染和预处理语义共同决定失效；发行版本号不影响缓存复用。
        return CACHE_FORMAT_VERSION + "|" + MESH_CACHE_MARKER + "|nbt-verified-inventory-v7-beacon-transparent"
                + "|ctm:" + ctmRuntimeToken();
    }

    @Nullable
    static String readCacheVersion(Path versionFile) {
        if (!Files.isRegularFile(versionFile)) {
            return null;
        }

        try {
            return Files.readString(versionFile, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void clearRenderCacheFiles(Path cacheDir) {
        try (var paths = Files.list(cacheDir)) {
            paths.forEach(QuickLitematicaPreviewCache::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
        }
    }

    static void deleteRecursivelyQuietly(Path path) {
        if (Files.isDirectory(path)) {
            try (var paths = Files.walk(path)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(QuickLitematicaPreviewCache::deleteQuietly);
            } catch (IOException ignored) {
            }
            return;
        }

        deleteQuietly(path);
    }

    static void deleteQuietly(Path path) {
        try {
            boolean deleted = Files.deleteIfExists(path);
            LOGGER.info("文件清理：路径={}，已删除={}", path, deleted);
        } catch (IOException failure) {
            LOGGER.warn("文件清理失败：路径={}，保留原有回退", path, failure);
        }
    }

    static void deleteTmpQuietly(Path path) {
        if (path.getFileName() != null && path.getFileName().toString().endsWith(".tmp")) {
            deleteQuietly(path);
        }
    }

    static void moveCacheFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static class CacheFile {
        // 与源文件预处理同为 256 MiB，所有动态记录共用原版 NBT 分配估算预算。
        private static final long TOTAL_NBT_READ_LIMIT_BYTES = 256L * 1024 * 1024;
        static MeshData read(Path path, AtomicBoolean cancelled, @Nullable QuickLitematicaPreviewMeshStore meshStore,
                             String expectedStructureFingerprint, String expectedResourceSignature) {
            try (var phase = QuickLitematicaPreviewLog.phase("网格缓存解压/解码")) {
                if (!Files.isRegularFile(path)) {
                    LOGGER.info("网格缓存未命中：原因=cache_file_missing，路径={}", path);
                    return null;
                }

                try (DataInputStream input = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
                    QuickLitematicaPreviewLog.file("网格缓存读取", path);
                    int magic = input.readInt();
                    int version = input.readInt();
                    String marker = input.readUTF();
                    String structureFingerprint = input.readUTF();
                    String resourceSignature = input.readUTF();
                    if (magic != CACHE_MAGIC || version != CACHE_FORMAT_VERSION || !MESH_CACHE_MARKER.equals(marker)
                            || !expectedStructureFingerprint.equals(structureFingerprint)
                            || !expectedResourceSignature.equals(resourceSignature)) {
                            LOGGER.warn("缓存读取失效：原因=cache_header_or_identity_mismatch：magic={}/{}，format={}/{}，marker={}/{}，结构={}/{}，资源={}/{}，文件={}",
                                    magic, CACHE_MAGIC, version, CACHE_FORMAT_VERSION, marker, MESH_CACHE_MARKER,
                                    structureFingerprint, expectedStructureFingerprint,
                                    resourceSignature, expectedResourceSignature, path);
                        deleteQuietly(path);
                        return null;
                    }

                    int sizeX = input.readInt();
                    int sizeY = input.readInt();
                    int sizeZ = input.readInt();
                    int layerCount = input.readInt();
                    if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || layerCount < 0 || layerCount > LayerKey.values().length) {
                            LOGGER.warn("缓存读取失效：原因=cache_dimensions_or_layers_invalid：尺寸={}x{}x{}，层数={}，文件={}", sizeX, sizeY, sizeZ, layerCount, path);
                        deleteQuietly(path);
                        return null;
                    }

                    List<LayerMesh> layers = new ArrayList<>(layerCount);
                    long totalVertices = 0L;
                    for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
                        if (isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        LayerKey layer = LayerKey.byId(input.readInt());
                        int vertexCount = input.readInt();
                        LOGGER.info("缓存层读取：层={}，顶点={}，量化字节={}", layer, vertexCount, (long) vertexCount * QUANTIZED_VERTEX_BYTES);
                        totalVertices += Math.max(vertexCount, 0);
                        // GZIP 压缩后无法用文件大小校验顶点数，先检查类型，再核验当前任务预算；
                        // 损坏文件会在 readFully 抛 EOFException 被外层 catch 删除。
                        if (layer == null || vertexCount < 0) {
                            LOGGER.warn("缓存读取失效：原因=cache_layer_or_vertex_invalid：层={}，顶点={}，累计={}/{}，文件={}", layer, vertexCount, totalVertices, MAX_UPLOAD_VERTICES, path);
                            deleteQuietly(path);
                            return null;
                        }

                        if (totalVertices > QuickLitematicaPreviewVertexBudget.vertexLimit()) {
                            LOGGER.info("缓存顶点超过当前保护预算：累计={}，上限={}，强制={}，保留缓存等待用户选择",
                                    totalVertices, QuickLitematicaPreviewVertexBudget.vertexLimit(), QuickLitematicaPreviewVertexBudget.isForced());
                            throw new PreviewTooLargeException(!QuickLitematicaPreviewVertexBudget.isForced());
                        }

                        // 批量读取量化顶点字节，直接存进 LayerMesh，渲染线程再解码进 BufferBuilder。
                        // 直接读取 packed 顶点字节，大文件读取避免逐顶点对象分配。
                        long quantizedBytes = (long) vertexCount * QUANTIZED_VERTEX_BYTES;
                        if (layer.isTranslucent() && quantizedBytes > QuickLitematicaPreviewVertexBudget.layerByteLimit()) {
                            throw new PreviewTooLargeException(!QuickLitematicaPreviewVertexBudget.isForced());
                        }

                        if (layer.isTranslucent() && vertexCount % 4 != 0) {
                            throw new IOException("Translucent cache contains an incomplete quad");
                        }
                        int remainingVertices = vertexCount;
                        while (remainingVertices > 0) {
                            int batchVertices = Math.min(remainingVertices, STATIC_BATCH_TARGET_VERTICES);
                            byte[] quantizedVertices = new byte[batchVertices * QUANTIZED_VERTEX_BYTES];
                            readFullyCancellable(input, quantizedVertices, cancelled);
                            List<LayerMesh> batch = QuickLitematicaPreviewMeshBatches.split(new LayerMesh(layer, quantizedVertices), cancelled);
                            if (meshStore == null) layers.addAll(batch);
                            else meshStore.addBatch(layers, batch, totalVertices * QUANTIZED_VERTEX_BYTES, cancelled);
                            remainingVertices -= batchVertices;
                        }
                    }

                    int blockStateCount = input.readInt();
                    if (blockStateCount < 0) {
                            LOGGER.warn("缓存读取失效：原因=cache_block_state_count_invalid：数量={}/{}，文件={}", blockStateCount, MAX_DYNAMIC_BLOCK_STATES, path);
                        deleteQuietly(path);
                        return null;
                    }

                    if (blockStateCount > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_STATES)) {
                        LOGGER.info("缓存动态数量超过当前保护预算：数量={}，默认上限={}，保留缓存等待强制读取", blockStateCount, MAX_DYNAMIC_BLOCK_STATES);
                        throw new PreviewTooLargeException();
                    }

                    NbtSizeTracker retainedNbt = NbtSizeTracker.of(
                            QuickLitematicaPreviewVertexBudget.nbtByteLimit(TOTAL_NBT_READ_LIMIT_BYTES));
                    List<BlockStateData> blockStates = new ArrayList<>(blockStateCount);
                    for (int i = 0; i < blockStateCount; i++) {
                        if ((i & 0x7FF) == 0 && isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        blockStates.add(new BlockStateData(
                                input.readInt(),
                                input.readInt(),
                                input.readInt(),
                                readCacheNbt(input, retainedNbt)
                        ));
                    }

                    int blockEntityCount = input.readInt();
                    if (blockEntityCount < 0) {
                            LOGGER.warn("缓存读取失效：原因=cache_block_entity_count_invalid：数量={}/{}，文件={}", blockEntityCount, MAX_DYNAMIC_BLOCK_ENTITIES, path);
                        deleteQuietly(path);
                        return null;
                    }

                    if (blockEntityCount > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_BLOCK_ENTITIES)) {
                        LOGGER.info("缓存动态数量超过当前保护预算：数量={}，默认上限={}，保留缓存等待强制读取", blockEntityCount, MAX_DYNAMIC_BLOCK_ENTITIES);
                        throw new PreviewTooLargeException();
                    }

                    List<BlockEntityData> blockEntities = new ArrayList<>(blockEntityCount);
                    for (int i = 0; i < blockEntityCount; i++) {
                        if ((i & 0xFF) == 0 && isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        blockEntities.add(new BlockEntityData(
                                input.readInt(),
                                input.readInt(),
                                input.readInt(),
                                readCacheNbt(input, retainedNbt),
                                readCacheNbt(input, retainedNbt)
                        ));
                    }

                    int entityCount = input.readInt();
                    if (entityCount < 0) {
                            LOGGER.warn("缓存读取失效：原因=cache_entity_count_invalid：数量={}/{}，文件={}", entityCount, MAX_DYNAMIC_ENTITIES, path);
                        deleteQuietly(path);
                        return null;
                    }

                    if (entityCount > QuickLitematicaPreviewVertexBudget.countLimit(MAX_DYNAMIC_ENTITIES)) {
                        LOGGER.info("缓存动态数量超过当前保护预算：数量={}，默认上限={}，保留缓存等待强制读取", entityCount, MAX_DYNAMIC_ENTITIES);
                        throw new PreviewTooLargeException();
                    }

                    List<EntityData> entities = new ArrayList<>(entityCount);
                    for (int i = 0; i < entityCount; i++) {
                        if ((i & 0xFF) == 0 && isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        entities.add(new EntityData(
                                input.readDouble(),
                                input.readDouble(),
                                input.readDouble(),
                                readCacheNbt(input, retainedNbt)
                        ));
                    }

                    return new MeshData(List.copyOf(layers), blockStates, blockEntities, entities, sizeX, sizeY, sizeZ);
                } catch (CancellationException | PreviewTooLargeException e) {
                    throw e;
                } catch (net.minecraft.nbt.NbtSizeValidationException e) {
                    LOGGER.info("缓存 NBT 超过当前分配预算，保留缓存等待强制读取：路径={}，原因={}", path, e.getMessage());
                    throw new PreviewTooLargeException();
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("网格缓存读取失败：原因=cache_decode_or_io_failed，路径={}，删除后重建", path, e);
                    deleteQuietly(path);
                    return null;
                }
            }
        }

        private static NbtCompound readCacheNbt(DataInputStream input, NbtSizeTracker retained) throws IOException {
            long remaining = QuickLitematicaPreviewVertexBudget.nbtByteLimit(TOTAL_NBT_READ_LIMIT_BYTES)
                    - retained.getAllocatedBytes();
            NbtSizeTracker entry = NbtSizeTracker.of(Math.min(remaining,
                    QuickLitematicaPreviewVertexBudget.nbtByteLimit(NBT_READ_LIMIT_BYTES)));
            NbtCompound nbt = NbtIo.readCompound(input, entry);
            retained.add(entry.getAllocatedBytes());
            return nbt;
        }

        static void readFullyCancellable(DataInputStream input, byte[] bytes, AtomicBoolean cancelled) throws IOException {
            int offset = 0;
            while (offset < bytes.length) {
                if (isCancelled(cancelled)) {
                    throw new CancellationException();
                }

                int length = Math.min(CACHE_IO_CHUNK_BYTES, bytes.length - offset);
                input.readFully(bytes, offset, length);
                offset += length;
            }
        }

        static void writeAtomically(
                Path tmpPath,
                Path finalPath,
                MeshData data,
                List<LayerMesh> layers,
                String structureFingerprint,
                String resourceSignature,
                AtomicBoolean cancelled,
                ProgressSink progressSink
        ) throws IOException {
            try (var phase = QuickLitematicaPreviewLog.phase("网格缓存压缩/文件替换")) {
                LOGGER.info("网格缓存压缩写入开始：临时={}，最终={}，层批次={}，尺寸={}x{}x{}", tmpPath, finalPath, layers.size(), data.sizeX(), data.sizeY(), data.sizeZ());
                deleteTmpQuietly(tmpPath);
                try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath))))) {
                    progressSink.set(PROGRESS_CACHE_WRITE);
                    output.writeInt(CACHE_MAGIC);
                    output.writeInt(CACHE_FORMAT_VERSION);
                    output.writeUTF(MESH_CACHE_MARKER);
                    output.writeUTF(structureFingerprint);
                    output.writeUTF(resourceSignature);
                    output.writeInt(data.sizeX());
                    output.writeInt(data.sizeY());
                    output.writeInt(data.sizeZ());
                    List<LayerKey> storedLayers = new ArrayList<>();
                    for (LayerKey layer : LayerKey.DRAW_ORDER) {
                        if (layers.stream().anyMatch(mesh -> mesh.layer() == layer && mesh.vertexCount() > 0)) {
                            storedLayers.add(layer);
                        }
                    }
                    output.writeInt(storedLayers.size());

                    long totalStaticBytes = 0L;
                    for (LayerMesh layer : layers) {
                        totalStaticBytes += layer.quantizedByteCount();
                    }

                    long staticBytesWritten = 0L;
                    for (LayerKey storedLayer : storedLayers) {
                        int layerVertexCount = 0;
                        for (LayerMesh mesh : layers) {
                            if (mesh.layer() == storedLayer) {
                                layerVertexCount += mesh.vertexCount();
                            }
                        }
                        LOGGER.info("缓存层写入：层={}，顶点={}，量化字节={}", storedLayer, layerVertexCount, (long) layerVertexCount * QUANTIZED_VERTEX_BYTES);
                        output.writeInt(storedLayer.id);
                        output.writeInt(layerVertexCount);
                        for (LayerMesh mesh : layers) {
                            if (mesh.layer() != storedLayer) {
                                continue;
                            }
                            byte[] quantized = mesh.quantizedVertices();
                            for (int offset = 0; offset < quantized.length; offset += CACHE_IO_CHUNK_BYTES) {
                                if (isCancelled(cancelled)) {
                                    throw new CancellationException();
                                }

                                int length = Math.min(CACHE_IO_CHUNK_BYTES, quantized.length - offset);
                                output.write(quantized, offset, length);
                                staticBytesWritten += length;
                                progressSink.set(progress(PROGRESS_CACHE_WRITE, PROGRESS_STATIC_CACHE_END, staticBytesWritten, totalStaticBytes));
                            }
                        }
                    }
                    progressSink.set(PROGRESS_STATIC_CACHE_END);

                    LOGGER.info("缓存动态数据写入：方块状态={}，方块实体={}，实体={}", data.blockStates.size(), data.blockEntities.size(), data.entities.size());
                    output.writeInt(data.blockStates.size());
                    for (int index = 0; index < data.blockStates.size(); index++) {
                        if (isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        BlockStateData blockState = data.blockStates.get(index);
                        output.writeInt(blockState.x());
                        output.writeInt(blockState.y());
                        output.writeInt(blockState.z());
                        NbtIo.writeCompound(blockState.stateNbt(), output);
                        if ((index & 0x7F) == 0 || index + 1 == data.blockStates.size()) {
                            progressSink.set(progress(PROGRESS_STATIC_CACHE_END, PROGRESS_BLOCK_STATES_CACHE_END, index + 1L, data.blockStates.size()));
                        }
                    }
                    progressSink.set(PROGRESS_BLOCK_STATES_CACHE_END);

                    output.writeInt(data.blockEntities.size());
                    for (int index = 0; index < data.blockEntities.size(); index++) {
                        if (isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        BlockEntityData blockEntity = data.blockEntities.get(index);
                        output.writeInt(blockEntity.x());
                        output.writeInt(blockEntity.y());
                        output.writeInt(blockEntity.z());
                        NbtIo.writeCompound(blockEntity.stateNbt(), output);
                        NbtIo.writeCompound(blockEntity.entityNbt(), output);
                        if ((index & 0x3F) == 0 || index + 1 == data.blockEntities.size()) {
                            progressSink.set(progress(PROGRESS_BLOCK_STATES_CACHE_END, PROGRESS_BLOCK_ENTITIES_CACHE_END, index + 1L, data.blockEntities.size()));
                        }
                    }
                    progressSink.set(PROGRESS_BLOCK_ENTITIES_CACHE_END);

                    output.writeInt(data.entities.size());
                    for (int index = 0; index < data.entities.size(); index++) {
                        if (isCancelled(cancelled)) {
                            throw new CancellationException();
                        }

                        EntityData entity = data.entities.get(index);
                        output.writeDouble(entity.x());
                        output.writeDouble(entity.y());
                        output.writeDouble(entity.z());
                        NbtIo.writeCompound(entity.entityNbt(), output);
                    }
                }

                QuickLitematicaPreviewLog.file("压缩流关闭", tmpPath);
                if (isCancelled(cancelled)) {
                    throw new CancellationException();
                }

                LOGGER.info("缓存最终文件替换开始：临时={}，最终={}", tmpPath, finalPath);
                try {
                    Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    LOGGER.info("缓存文件替换回退：原因=atomic_move_unsupported，目标={}", finalPath);
                    Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        static boolean isCancelled(AtomicBoolean cancelled) {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }

        static float progress(float start, float end, long completed, long total) {
            if (total <= 0L) {
                return end;
            }
            return start + (end - start) * Math.min(1.0F, completed / (float) total);
        }

        // ---- 静态顶点解码与 octahedral 8-bit 法线编码 ----

        static short encodeOverlay(int overlay) {
            return (short) ((overlay & 0xFF) | (((overlay >>> 16) & 0xFF) << 8));
        }

        static int decodeOverlay(short packed) {
            return (packed & 0xFF) | (((packed >>> 8) & 0xFF) << 16);
        }

        static int readInt(byte[] bytes, int offset) {
            return (bytes[offset] & 0xFF) << 24
                    | (bytes[offset + 1] & 0xFF) << 16
                    | (bytes[offset + 2] & 0xFF) << 8
                    | (bytes[offset + 3] & 0xFF);
        }

        static short readShort(byte[] bytes, int offset) {
            return (short) ((bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF));
        }

        // 法线 (float x3) -> 2 字节，八面体编码 8-bit/分量。方向光照肉眼不可察觉差异。
        static short encodeNormal(float nx, float ny, float nz) {
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6F) {
                return 0;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            float denom = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
            float pu = nx / denom;
            float pv = ny / denom;
            if (nz < 0.0F) {
                float newU = (1.0F - Math.abs(pv)) * (pu >= 0.0F ? 1.0F : -1.0F);
                float newV = (1.0F - Math.abs(pu)) * (pv >= 0.0F ? 1.0F : -1.0F);
                pu = newU;
                pv = newV;
            }
            int iu = Math.round(pu * 127.0F);
            int iv = Math.round(pv * 127.0F);
            return (short) ((iu & 0xFF) << 8 | (iv & 0xFF));
        }

        // 2 字节八面体编码 -> 法线，填入复用数组避免分配。
        static void decodeNormal(short packed, float[] out) {
            int iu = (packed >> 8) & 0xFF;
            int iv = packed & 0xFF;
            int su = iu > 127 ? iu - 256 : iu;
            int sv = iv > 127 ? iv - 256 : iv;
            float pu = su / 127.0F;
            float pv = sv / 127.0F;
            float pz = 1.0F - Math.abs(pu) - Math.abs(pv);
            float nx;
            float ny;
            float nz;
            if (pz < 0.0F) {
                nx = (1.0F - Math.abs(pv)) * (pu >= 0.0F ? 1.0F : -1.0F);
                ny = (1.0F - Math.abs(pu)) * (pv >= 0.0F ? 1.0F : -1.0F);
                nz = pz;
            } else {
                nx = pu;
                ny = pv;
                nz = pz;
            }
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6F) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            out[0] = nx;
            out[1] = ny;
            out[2] = nz;
        }

        static void decodeQuantizedToBuilder(byte[] bytes, BufferBuilder builder) {
            QuickLitematicaPreviewAccess.decodeQuantizedToBuilder(bytes, builder);
        }
    }
}
