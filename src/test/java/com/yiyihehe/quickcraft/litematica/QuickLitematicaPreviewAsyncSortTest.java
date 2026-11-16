package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewAsyncSortTest {
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable work) { this.tasks.add(work); }
        void run() { this.tasks.remove().run(); }
    }

    @Test void closingRunningSortReleasesTheWorkerForTheNextPreview() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reads = new AtomicInteger();
        var initial = new QuickLitematicaPreviewQuadDepth();
        initial.update(new Matrix4f());
        try (var old = new QuickLitematicaPreviewAsyncSort(8192, 4, initial, (depth, order, keys) -> {
                depth.sort(8192, (index, target) -> {
                    reads.incrementAndGet();
                    if (index == 0) {
                        entered.countDown();
                        try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("not released"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    }
                    return target.set(index, 0, 0);
                }, order, keys);
            }, new int[8192], new int[8192], executor);
             var next = new QuickLitematicaPreviewAsyncSort(1, 4, initial,
                     (depth, order, keys) -> order[0] = 0, new int[1], new int[1], executor)) {
            old.request(new Matrix4f().rotateY(1));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            old.close();
            next.request(new Matrix4f().rotateY(1));
            release.countDown();
            executor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reads.get()).isPositive().isLessThanOrEqualTo(2048);
            assertThat(old.poll()).isNull();
            try (var result = next.poll()) { assertThat(result).isNotNull(); }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void draggingCoalescesWorkAndSettlesOnTheFinalView() {
        var executor = new ManualExecutor();
        var initial = new QuickLitematicaPreviewQuadDepth();
        initial.update(new Matrix4f());
        try (var sorter = new QuickLitematicaPreviewAsyncSort(2, 4, initial, (depth, order, keys) -> {
            order[0] = 1;
            order[1] = 0;
        }, new int[0], new int[0], executor)) {
            sorter.request(new Matrix4f());
            assertThat(executor.tasks).isEmpty();
            Matrix4f first = new Matrix4f().rotateY(0.1F);
            sorter.request(first);
            for (int i = 1; i < 100; i++) sorter.request(new Matrix4f().rotateY(i / 10.0F));
            assertThat(executor.tasks).hasSize(1);
            assertThat(sorter.poll()).isNull();
            executor.run();
            try (var result = sorter.poll()) {
                assertThat(result.view).isEqualTo(first);
                assertThat(result.indices().getInt(0)).isEqualTo(4);
                assertThat(result.indices().getInt(24)).isZero();
            }
            Matrix4f finalView = new Matrix4f().rotateY(1.3F);
            sorter.request(finalView);
            assertThat(executor.tasks).hasSize(1);
            executor.run();
            try (var result = sorter.poll()) { assertThat(result.view).isEqualTo(finalView); }
            sorter.request(finalView);
            assertThat(executor.tasks).isEmpty();
        }
    }

    @Test void closingDropsQueuedWorkAndUnconsumedResults() {
        var executor = new ManualExecutor();
        var count = new AtomicInteger();
        var initial = new QuickLitematicaPreviewQuadDepth();
        initial.update(new Matrix4f());
        var cancelled = new QuickLitematicaPreviewAsyncSort(1, 4, initial,
                (depth, order, keys) -> count.incrementAndGet(), new int[1], new int[1], executor);
        cancelled.request(new Matrix4f().rotateY(1));
        var pending = (Future<?>) executor.tasks.getFirst();
        assertThat(pending.isDone()).isFalse();
        cancelled.close();
        assertThat(pending.isCancelled()).isTrue();
        // 不靠执行器运行一次来丢弃关闭投影的任务载荷。
        assertThat(count.get()).isZero();
        executor.run();
        assertThat(count.get()).isZero();
        assertThat(cancelled.poll()).isNull();

        var completed = new QuickLitematicaPreviewAsyncSort(1, 4, initial,
                (depth, order, keys) -> count.incrementAndGet(), new int[1], new int[1], executor);
        completed.request(new Matrix4f().rotateY(1));
        executor.run();
        completed.close();
        completed.close();
        assertThat(completed.poll()).isNull();
        completed.request(new Matrix4f().rotateY(2));
        assertThat(executor.tasks).isEmpty();
    }
}
