package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.renderer.MultiBufferSource;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 保留原生阶段的先后顺序，只在预览独立收集器上续跑。 */
public final class QuickLitematicaPreviewQueuedBuild implements AutoCloseable {
    private static final ThreadLocal<QuickLitematicaPreviewQueuedBuild> CURRENT = new ThreadLocal<>();
    private final MultiBufferSource.BufferSource owner;
    private final LongSupplier clock;
    int slices;
    private final ArrayDeque<BooleanSupplier> work = new ArrayDeque<>();
    private final ArrayDeque<BooleanSupplier> deferred = new ArrayDeque<>();

    QuickLitematicaPreviewQueuedBuild(DispatcherBridge dispatcher, MultiBufferSource.BufferSource owner) {
        this(dispatcher, owner, System::nanoTime);
    }

    QuickLitematicaPreviewQueuedBuild(DispatcherBridge dispatcher, MultiBufferSource.BufferSource owner, LongSupplier clock) {
        this.owner = owner;
        this.clock = clock;
        dispatcher.quickcraft$queuePreviewPhases(task -> this.work.addLast(() -> {
            task.run();
            return true;
        }));
    }

    public static boolean defer(MultiBufferSource.BufferSource owner, BooleanSupplier step) {
        QuickLitematicaPreviewQueuedBuild build = CURRENT.get();
        if (build == null || build.owner != owner) return false;
        build.deferred.addLast(step);
        return true;
    }

    public static boolean isCapturing(MultiBufferSource.BufferSource owner) {
        QuickLitematicaPreviewQueuedBuild build = CURRENT.get();
        return build != null && build.owner == owner;
    }

    public static <V> boolean deferList(MultiBufferSource.BufferSource owner, List<V> records, Consumer<V> render) {
        if (!isCapturing(owner)) return false;
        Iterator<V> iterator = records.iterator();
        return defer(owner, () -> {
            if (iterator.hasNext()) render.accept(iterator.next());
            return !iterator.hasNext();
        });
    }

    public static <L, V> boolean deferEntries(MultiBufferSource.BufferSource owner,
            Iterable<Map.Entry<L, List<V>>> groups, Function<L, Consumer<V>> renderer) {
        if (!isCapturing(owner)) return false;
        Iterator<Map.Entry<L, List<V>>> entries = groups.iterator();
        return defer(owner, new BooleanSupplier() {
            private Iterator<V> records = List.<V>of().iterator();
            private Consumer<V> render;

            @Override
            public boolean getAsBoolean() {
                if (!this.records.hasNext()) {
                    if (!entries.hasNext()) return true;
                    var entry = entries.next();
                    // 原生每个材质只取一次 consumer，包括空材质组。
                    this.render = renderer.apply(entry.getKey());
                    this.records = entry.getValue().iterator();
                }
                if (this.records.hasNext()) this.render.accept(this.records.next());
                return !this.records.hasNext() && !entries.hasNext();
            }
        });
    }

    boolean advance() {
        this.slices++;
        long start = this.clock.getAsLong();
        QuickLitematicaPreviewQueuedBuild previous = CURRENT.get();
        CURRENT.set(this);
        try {
            do {
                BooleanSupplier step = this.work.pollFirst();
                if (step == null) return true;
                if (!step.getAsBoolean()) this.work.addFirst(step);
                // 同一原生阶段捕获的 solid/blended 子任务保持原调用次序。
                while (!this.deferred.isEmpty()) this.work.addFirst(this.deferred.removeLast());
            } while (this.clock.getAsLong() - start < QuickLitematicaPreviewBatchBudget.TARGET_NANOS);
            return this.work.isEmpty();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    @Override
    public void close() {
        this.work.clear();
        this.deferred.clear();
    }

    public interface DispatcherBridge {
        void quickcraft$queuePreviewPhases(Consumer<Runnable> submit);
    }
}
