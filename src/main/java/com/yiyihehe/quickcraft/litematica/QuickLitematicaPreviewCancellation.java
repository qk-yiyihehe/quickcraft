package com.yiyihehe.quickcraft.litematica;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 上游 DFU 的取消范围；独立于日志开关，仅作用于当前 QC 预览任务。 */
public final class QuickLitematicaPreviewCancellation {
    private static final ThreadLocal<AtomicBoolean> CURRENT = new ThreadLocal<>();

    private QuickLitematicaPreviewCancellation() {}

    public static void check() {
        AtomicBoolean cancelled = CURRENT.get();
        if (cancelled != null && (cancelled.get() || Thread.currentThread().isInterrupted())) {
            throw new CancellationException();
        }
    }

    static Scope bind(AtomicBoolean cancelled) { return new Scope(cancelled); }

    static final class Scope implements AutoCloseable {
        private final AtomicBoolean previous;

        private Scope(AtomicBoolean cancelled) {
            previous = CURRENT.get();
            CURRENT.set(cancelled);
        }

        @Override public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
