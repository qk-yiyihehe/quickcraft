package com.yiyihehe.quickcraft.litematica;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LayerMesh;

final class QuickLitematicaPreviewMeshStore implements AutoCloseable {
    static final long MEMORY_BYTES = 64L * 1024 * 1024;
    private final Path directory;
    private Path path;
    private FileChannel channel;
    private long position;
    private int references = 1;
    private boolean ownerClosed;

    QuickLitematicaPreviewMeshStore() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    QuickLitematicaPreviewMeshStore(Path directory) {
        this.directory = directory;
    }

    void addBatch(List<LayerMesh> retained, List<LayerMesh> meshes, long totalBytes, AtomicBoolean cancelled) {
        if (totalBytes <= MEMORY_BYTES) {
            retained.addAll(meshes);
            return;
        }
        // 跨过阈值后连同此前保留的前缀一起转移，堆中不再保留完整缓存副本。
        if (!retained.isEmpty() && retained.getFirst().store() == null) {
            for (int index = 0; index < retained.size(); index++) {
                checkCancelled(cancelled);
                retained.set(index, this.store(retained.get(index)));
            }
        }
        for (LayerMesh mesh : meshes) {
            checkCancelled(cancelled);
            retained.add(this.store(mesh));
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    synchronized LayerMesh store(LayerMesh mesh) {
        if (mesh.store() != null) return mesh;
        try (var phase = QuickLitematicaPreviewLog.phase("CPU顶点暂存写入")) {
            if (this.ownerClosed) throw new CancellationException();
            if (this.channel == null) {
                this.path = Files.createTempFile(this.directory, "quickcraft-preview-mesh-", ".tmp");
                // Windows 用句柄标记删除，进程退出也能回收未完成任务的暂存文件。
                this.channel = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
                QuickLitematicaPreviewLog.LOGGER.info("CPU 顶点暂存启用：文件={}，驻留阈值={} B", this.path, MEMORY_BYTES);
            }
            long offset = this.position;
            ByteBuffer bytes = ByteBuffer.wrap(mesh.quantizedVertices());
            while (bytes.hasRemaining()) this.position += this.channel.write(bytes, this.position);
            return new LayerMesh(mesh.layer(), null, this, offset, mesh.quantizedByteCount());
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to spill preview vertices", failure);
        }
    }

    byte[] read(long offset, int length) {
        try (var phase = QuickLitematicaPreviewLog.phase("CPU顶点暂存读取")) {
            FileChannel input;
            synchronized (this) {
                if (this.references == 0 || this.channel == null) throw new IOException("Preview mesh store is closed");
                input = this.channel;
            }
            byte[] bytes = new byte[length];
            ByteBuffer target = ByteBuffer.wrap(bytes);
            while (target.hasRemaining()) {
                int count = input.read(target, offset + target.position());
                if (count < 0) throw new IOException("Truncated preview mesh store");
            }
            return bytes;
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read preview vertices", failure);
        }
    }

    synchronized void retainForCache() {
        if (this.ownerClosed) throw new CancellationException();
        this.references++;
    }

    synchronized boolean retainForRead() {
        // 预览已关闭时，正在保存的缓存仍可向新读取者交接暂存文件。
        if (this.references == 0) return false;
        this.references++;
        return true;
    }

    synchronized void releaseCache() {
        this.release();
    }

    private void release() {
        if (--this.references != 0) return;
        try {
            if (this.channel != null) this.channel.close();
        } catch (IOException failure) {
            QuickLitematicaPreviewLog.LOGGER.warn("预览顶点暂存关闭失败：文件={}", this.path, failure);
        } finally {
            if (this.path != null) {
                try {
                    Files.deleteIfExists(this.path);
                    QuickLitematicaPreviewLog.LOGGER.info("CPU 顶点暂存清理完成：文件={}，累计={} B", this.path, this.position);
                }
                catch (IOException failure) {
                    QuickLitematicaPreviewLog.LOGGER.warn("预览顶点暂存清理失败：文件={}", this.path, failure);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (this.ownerClosed) return;
        this.ownerClosed = true;
        this.release();
    }
}
