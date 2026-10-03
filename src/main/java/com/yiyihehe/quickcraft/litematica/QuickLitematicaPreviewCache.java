package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.nbt.NbtIo;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;

/** 预览磁盘缓存的索引、失效、原子写入和网格编码；任务与 GPU 生命周期由预览核心维护。 */
final class QuickLitematicaPreviewCache {
    private QuickLitematicaPreviewCache() {
    }

    static final int CACHE_MAGIC = 0x51435033; // QCP3
    static final String CACHE_DIR_NAME = "litematica-preview-cache";
    static final String CACHE_VERSION_FILE_NAME = "cache-version.txt";
    static final String CACHE_INDEX_FILE_NAME = "cache-index.properties";
    static final int CACHE_IO_CHUNK_BYTES = 1024 * 1024;
    private static final AtomicBoolean CACHE_DIRECTORY_READY = new AtomicBoolean();
    private static final Object CACHE_INDEX_LOCK = new Object();
    private static final Properties CACHE_INDEX = new Properties();
    @Nullable
    private static volatile Path currentCacheDirectory;

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

        synchronized (QuickLitematicaPreviewCache.class) {
            cacheDir = currentCacheDirectory;
            if (cacheDir != null) {
                return cacheDir;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            Path runDirectory = client.runDirectory.toPath();
            cacheDir = runDirectory.resolve(CACHE_DIR_NAME);
            currentCacheDirectory = cacheDir;
            if (CACHE_DIRECTORY_READY.compareAndSet(false, true)) {
                prepareCacheDirectory(cacheDir);
            }
            return cacheDir;
        }
    }

    static void prepareCacheDirectory(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            Path versionFile = cacheDir.resolve(CACHE_VERSION_FILE_NAME);
            String currentVersion = currentCacheVersionToken();
            String storedVersion = readCacheVersion(versionFile);
            if (!currentVersion.equals(storedVersion)) {
                clearRenderCacheFiles(cacheDir);
                Files.writeString(versionFile, currentVersion, java.nio.charset.StandardCharsets.UTF_8);
            }
            loadAndCleanCacheIndex(cacheDir);
        } catch (IOException ignored) {
        }
    }

    static void loadAndCleanCacheIndex(Path cacheDir) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.clear();
            Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
            if (Files.isRegularFile(indexPath)) {
                try (InputStream input = new BufferedInputStream(Files.newInputStream(indexPath))) {
                    CACHE_INDEX.load(input);
                }
            }

            Set<String> retainedCacheFiles = new java.util.HashSet<>();
            List<String> staleSlots = new ArrayList<>();
            for (String key : CACHE_INDEX.stringPropertyNames()) {
                if (!key.endsWith(".path")) {
                    continue;
                }
                String slot = key.substring(0, key.length() - ".path".length());
                String source = CACHE_INDEX.getProperty(key, "");
                Path cachePath = cacheDir.resolve(slot + ".qcp3d");
                boolean sourceExists;
                try {
                    sourceExists = !source.isBlank() && Files.isRegularFile(Path.of(source));
                } catch (RuntimeException e) {
                    sourceExists = false;
                }
                if (!sourceExists) {
                    staleSlots.add(slot);
                } else {
                    if (Files.isRegularFile(cachePath)) {
                        retainedCacheFiles.add(cachePath.getFileName().toString());
                    }
                    retainedCacheFiles.add(slot + ".converted.litematic");
                }
            }

            staleSlots.forEach(slot -> removeCacheIndexEntry(cacheDir, slot));
            try (var files = Files.list(cacheDir)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".tmp")
                                    || name.endsWith(".converted.tmp.litematic")
                                    || (name.endsWith(".qcp3d") || name.endsWith(".converted.litematic"))
                                    && !retainedCacheFiles.contains(name);
                        })
                        .forEach(QuickLitematicaPreviewCache::deleteQuietly);
            }
            writeCacheIndex(cacheDir);
        }
    }

    @Nullable
    static CacheIndexEntry readCacheIndexEntry(String slot) {
        synchronized (CACHE_INDEX_LOCK) {
            String sourceHash = CACHE_INDEX.getProperty(slot + ".sourceHash");
            String resourceSignature = CACHE_INDEX.getProperty(slot + ".resourceSignature");
            return sourceHash == null || resourceSignature == null
                    ? null
                    : new CacheIndexEntry(sourceHash, resourceSignature);
        }
    }

    static void writeCacheIndexEntry(String slot, Path sourcePath, String sourceHash, String resourceSignature) throws IOException {
        synchronized (CACHE_INDEX_LOCK) {
            CACHE_INDEX.setProperty(slot + ".path", sourcePath.toAbsolutePath().normalize().toString());
            CACHE_INDEX.setProperty(slot + ".sourceHash", sourceHash);
            CACHE_INDEX.setProperty(slot + ".resourceSignature", resourceSignature);
            writeCacheIndex(cacheDirectory());
        }
    }

    static void removeCacheIndexEntry(Path cacheDir, String slot) {
        CACHE_INDEX.remove(slot + ".path");
        CACHE_INDEX.remove(slot + ".sourceHash");
        CACHE_INDEX.remove(slot + ".resourceSignature");
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d"));
        deleteQuietly(cacheDir.resolve(slot + ".qcp3d.tmp"));
        deleteQuietly(cacheDir.resolve(slot + ".converted.litematic"));
        deleteQuietly(cacheDir.resolve(slot + ".converted.tmp.litematic"));
    }

    static void writeCacheIndex(Path cacheDir) throws IOException {
        Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
        Path temporaryPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME + ".tmp");
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryPath))) {
            CACHE_INDEX.store(output, "QuickCraft Litematica 3D preview cache index");
        }
        moveCacheFile(temporaryPath, indexPath);
    }

    record CacheIndexEntry(String sourceHash, String resourcePackSignature) {
    }

    static String currentCacheVersionToken() {
        // 格式和渲染语义共同决定失效；发行版本号不影响缓存复用。
        return CACHE_FORMAT_VERSION + "|" + CACHE_RENDER_MARKER
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
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return !CACHE_INDEX_FILE_NAME.equals(name);
                    })
                    .forEach(QuickLitematicaPreviewCache::deleteRecursivelyQuietly);
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
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
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
        @Nullable
        static MeshData read(Path path, AtomicBoolean cancelled) {
            if (!Files.isRegularFile(path)) {
                return null;
            }

            try (DataInputStream input = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
                int magic = input.readInt();
                int version = input.readInt();
                String marker = input.readUTF();
                if (magic != CACHE_MAGIC || version != CACHE_FORMAT_VERSION || !CACHE_RENDER_MARKER.equals(marker)) {
                    deleteQuietly(path);
                    return null;
                }

                int sizeX = input.readInt();
                int sizeY = input.readInt();
                int sizeZ = input.readInt();
                int layerCount = input.readInt();
                if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || layerCount < 0 || layerCount > LayerKey.values().length) {
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
                    totalVertices += Math.max(vertexCount, 0);
                    // GZIP 压缩后无法用文件大小校验顶点数，仅用 MAX_UPLOAD_VERTICES 上界；
                    // 损坏文件会在 readFully 抛 EOFException 被外层 catch 删除。
                    if (layer == null || vertexCount < 0 || totalVertices > MAX_UPLOAD_VERTICES) {
                        deleteQuietly(path);
                        return null;
                    }

                    // 批量读取量化顶点字节，直接存进 LayerMesh，渲染线程再解码进 BufferBuilder。
                    // 直接读取 packed 顶点字节，大文件读取避免逐顶点对象分配。
                    long quantizedBytes = (long) vertexCount * QUANTIZED_VERTEX_BYTES;
                    if (quantizedBytes > MAX_QUANTIZED_LAYER_BYTES || quantizedBytes > Integer.MAX_VALUE - 8L) {
                        deleteQuietly(path);
                        return null;
                    }

                    int remainingVertices = vertexCount;
                    while (remainingVertices > 0) {
                        int batchVertices = layer.isTranslucent()
                                ? remainingVertices
                                : Math.min(remainingVertices, STATIC_BATCH_TARGET_VERTICES);
                        byte[] quantizedVertices = new byte[batchVertices * QUANTIZED_VERTEX_BYTES];
                        readFullyCancellable(input, quantizedVertices, cancelled);
                        layers.add(new LayerMesh(layer, quantizedVertices));
                        remainingVertices -= batchVertices;
                    }
                }

                int blockStateCount = input.readInt();
                if (blockStateCount < 0 || blockStateCount > MAX_DYNAMIC_BLOCK_STATES) {
                    deleteQuietly(path);
                    return null;
                }

                List<BlockStateData> blockStates = new ArrayList<>(blockStateCount);
                for (int i = 0; i < blockStateCount; i++) {
                    if ((i & 0x7FF) == 0 && isCancelled(cancelled)) {
                        throw new CancellationException();
                    }

                    blockStates.add(new BlockStateData(
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                int blockEntityCount = input.readInt();
                if (blockEntityCount < 0 || blockEntityCount > MAX_DYNAMIC_BLOCK_ENTITIES) {
                    deleteQuietly(path);
                    return null;
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
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES)),
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                int entityCount = input.readInt();
                if (entityCount < 0 || entityCount > MAX_DYNAMIC_ENTITIES) {
                    deleteQuietly(path);
                    return null;
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
                            NbtIo.readCompound(input, NbtSizeTracker.of(NBT_READ_LIMIT_BYTES))
                    ));
                }

                return new MeshData(List.copyOf(layers), blockStates, blockEntities, entities, sizeX, sizeY, sizeZ);
            } catch (CancellationException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                deleteQuietly(path);
                return null;
            }
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
                AtomicBoolean cancelled,
                ProgressSink progressSink
        ) throws IOException {
            deleteTmpQuietly(tmpPath);
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath))))) {
                progressSink.set(PROGRESS_CACHE_WRITE);
                output.writeInt(CACHE_MAGIC);
                output.writeInt(CACHE_FORMAT_VERSION);
                output.writeUTF(CACHE_RENDER_MARKER);
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
                    totalStaticBytes += layer.quantizedVertices().length;
                }

                long staticBytesWritten = 0L;
                for (LayerKey storedLayer : storedLayers) {
                    int layerVertexCount = 0;
                    for (LayerMesh mesh : layers) {
                        if (mesh.layer() == storedLayer) {
                            layerVertexCount += mesh.vertexCount();
                        }
                    }
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

            if (isCancelled(cancelled)) {
                throw new CancellationException();
            }

            try {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
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
