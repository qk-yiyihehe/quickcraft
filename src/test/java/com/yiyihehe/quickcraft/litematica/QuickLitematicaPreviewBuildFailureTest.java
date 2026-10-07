package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewBuildFailureTest {
    @Test
    void reportsHeapFailureBeforeSubmittedTaskFinishes() throws Exception {
        OutOfMemoryError expected = new OutOfMemoryError("simulated preview mesh allocation failure");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicBoolean partialMeshPresent = new AtomicBoolean(true);
        FutureTask<Void> task = new FutureTask<>(() -> QuickLitematicaPreview3D.runBuildTask(
                () -> { throw expected; },
                failure -> {
                    partialMeshPresent.set(false);
                    reported.set(failure);
                }), null);
        task.run();
        task.get();
        assertThat(reported.get()).isSameAs(expected);
        assertThat(partialMeshPresent).isFalse();
    }
}
