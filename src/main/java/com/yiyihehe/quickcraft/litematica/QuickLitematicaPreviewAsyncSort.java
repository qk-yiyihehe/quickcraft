package com.yiyihehe.quickcraft.litematica;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

final class QuickLitematicaPreviewAsyncSort implements AutoCloseable {
    // 一份预览最多一个任务和一个结果；不把拖动中的每个角度排进后台队列。
    private static final Executor WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "QuickCraft transparent sort");
        thread.setDaemon(true);
        return thread;
    });
    // 约25万面的全物品投影仍有约9ms串行排序，不能以并行排序门槛决定是否阻塞渲染线程。
    static final int MIN_QUADS = 65_536;

    @FunctionalInterface
    interface Sorter {
        void sort(QuickLitematicaPreviewQuadDepth depth, int[] order, int[] keys);
    }

    private final int bytes, indexBytes;
    private final int[] order, keys;
    private final Sorter sorter;
    private final Executor executor;
    private final QuickLitematicaPreviewQuadDepth requestedDepth;
    private volatile boolean closed;
    private boolean running;
    private FutureTask<Void> task;
    private ByteBuffer spare;
    private Result ready;
    private Throwable failure;

    QuickLitematicaPreviewAsyncSort(int count, int indexBytes, QuickLitematicaPreviewQuadDepth completedDepth,
                                   Sorter sorter, int[] order, int[] keys) {
        this(count, indexBytes, completedDepth, sorter, order, keys, WORKER);
        QuickLitematicaPreviewLog.LOGGER.info("transparent-async-v1：启用后台透明排序与索引生成，四边形={}，索引字节={}，仅保留最新待处理视角", count, this.bytes);
    }

    QuickLitematicaPreviewAsyncSort(int count, int indexBytes, QuickLitematicaPreviewQuadDepth completedDepth,
                                   Sorter sorter, int[] order, int[] keys, Executor executor) {
        if (count < 0 || (indexBytes != 2 && indexBytes != 4))
            throw new IllegalArgumentException("Invalid async transparent sort workspace");
        this.bytes = Math.multiplyExact(count, 6 * indexBytes);
        this.indexBytes = indexBytes;
        this.requestedDepth = completedDepth.copy();
        this.sorter = sorter;
        this.order = order.length == count ? order : new int[count];
        this.keys = keys.length == count ? keys : new int[count];
        this.executor = executor;
    }

    synchronized Result poll() {
        if (this.failure != null) {
            Throwable failure = this.failure;
            this.failure = null;
            this.close();
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Background transparent sort failed", failure);
        }
        Result result = this.ready;
        this.ready = null;
        return result;
    }

    synchronized void request(Matrix4fc view) {
        if (this.closed || this.running || this.ready != null || !this.requestedDepth.update(view)) return;
        Matrix4f captured = new Matrix4f(view);
        this.running = true;
        Runnable work = () -> this.compute(captured);
        var trace = QuickLitematicaPreviewLog.currentTrace();
        try {
            this.task = new FutureTask<>(trace == null ? work : trace.wrap(work), null);
            this.executor.execute(this.task);
        } catch (RuntimeException | Error failure) {
            this.running = false;
            this.close();
            throw failure;
        }
    }

    private void compute(Matrix4f view) {
        ByteBuffer indices = null;
        try {
            if (this.closed) return;
            var depth = new QuickLitematicaPreviewQuadDepth();
            depth.update(view);
            try (var phase = QuickLitematicaPreviewLog.phase("后台透明面中心排序")) {
                this.sorter.sort(depth, this.order, this.keys);
            }
            if (this.closed) return;
            synchronized (this) {
                indices = this.spare;
                this.spare = null;
            }
            if (indices == null) indices = MemoryUtil.memAlloc(this.bytes);
            indices.clear();
            try (var phase = QuickLitematicaPreviewLog.phase("后台透明三角形索引生成")) {
                QuickLitematicaPreviewIndices.write(MemoryUtil.memAddress(indices), this.order, this.indexBytes, () -> this.closed);
            }
            synchronized (this) {
                if (!this.closed) {
                    this.ready = new Result(this, view, indices);
                    indices = null;
                }
            }
        } catch (CancellationException ignored) {
        } catch (RuntimeException | Error failure) {
            synchronized (this) { if (!this.closed) this.failure = failure; }
        } finally {
            if (indices != null) MemoryUtil.memFree(indices);
            synchronized (this) { this.running = false; this.task = null; }
        }
    }

    private synchronized void recycle(ByteBuffer indices) {
        if (this.closed || this.spare != null) MemoryUtil.memFree(indices);
        else this.spare = indices;
    }

    @Override public synchronized void close() {
        this.closed = true;
        // 取消未开始的 callable，执行器尚未出队时也能释放其持有的大型排序工作区。
        if (this.task != null) { this.task.cancel(false); this.task = null; }
        if (this.ready != null) { this.ready.close(); this.ready = null; }
        if (this.spare != null) { MemoryUtil.memFree(this.spare); this.spare = null; }
        // 正在运行的任务拥有其暂存地址；由任务退出时释放，渲染线程不等待后台排序。
    }

    static final class Result implements AutoCloseable {
        private final QuickLitematicaPreviewAsyncSort owner;
        final Matrix4f view;
        private ByteBuffer indices;

        private Result(QuickLitematicaPreviewAsyncSort owner, Matrix4f view, ByteBuffer indices) {
            this.owner = owner;
            this.view = view;
            this.indices = indices;
        }

        ByteBuffer indices() { return this.indices; }

        @Override public void close() {
            if (this.indices != null) {
                this.owner.recycle(this.indices);
                this.indices = null;
            }
        }
    }
}
