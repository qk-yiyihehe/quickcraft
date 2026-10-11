package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LayerMesh;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 待上传原始顶点的有界交接；出队后容量仍归该批所有，直到上传或丢弃完成。 */
final class QuickLitematicaPreviewUploadQueue {
    private final ConcurrentLinkedQueue<Upload> pending = new ConcurrentLinkedQueue<>();
    // 64 KiB 计账单位，队列及正在上传的批次合计 64 MiB；超大单批仍沿用独占预算策略。
    private final Semaphore capacity = new Semaphore(1024);

    void publish(LayerMesh layer, Runnable checkCancelled) {
        checkCancelled.run();
        int permits = (int) Math.min(1024L, (layer.quantizedByteCount() + 65_535L) / 65_536L);
        try (var phase = QuickLitematicaPreviewLog.phase("等待静态上传队列容量")) {
            this.capacity.acquire(permits);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
        boolean queued = false;
        try {
            checkCancelled.run();
            // 暂存读取留在生产线程；取消与入队通过同一锁与 discard 串行化。
            byte[] bytes = layer.quantizedVertices();
            synchronized (this) {
                checkCancelled.run();
                this.pending.add(new Upload(new LayerMesh(layer.layer(), bytes), this.capacity, permits));
                queued = true;
            }
        } finally {
            if (!queued) this.capacity.release(permits);
        }
    }

    @Nullable Upload poll() { return this.pending.poll(); }
    boolean isEmpty() { return this.pending.isEmpty(); }
    int size() { return this.pending.size(); }
    int availableKiB() { return this.capacity.availablePermits() * 64; }

    synchronized void discard() {
        Upload upload;
        while ((upload = this.pending.poll()) != null) upload.close();
    }

    static final class Upload implements AutoCloseable {
        final LayerMesh layer;
        private final Semaphore capacity;
        private final int permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Upload(LayerMesh layer, Semaphore capacity, int permits) {
            this.layer = layer;
            this.capacity = capacity;
            this.permits = permits;
        }

        @Override public void close() {
            if (this.released.compareAndSet(false, true)) this.capacity.release(this.permits);
        }
    }
}
