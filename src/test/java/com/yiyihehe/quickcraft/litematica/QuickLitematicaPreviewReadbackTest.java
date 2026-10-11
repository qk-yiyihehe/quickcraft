package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewReadbackTest {
    @Test
    void callbackReleasesViewThenBufferThenTargetOnce() throws Exception {
        var events = new ArrayList<String>();
        var resources = new QuickLitematicaPreviewReadback(() -> events.add("target"));
        resources.buffer(() -> events.add("buffer"));
        try (resources; AutoCloseable view = () -> events.add("view")) {
            events.add("pixels");
        }
        resources.close();
        assertThat(events).containsExactly("pixels", "view", "buffer", "target");
    }

    @Test
    void allocationFailureBeforeBufferCreationStillReleasesTargetAndReports() {
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("allocation");
        var resources = new QuickLitematicaPreviewReadback(() -> events.add("target"));
        resources.failed(failure, reported -> {
            assertThat(reported).isSameAs(failure);
            events.add("reported");
        });
        resources.close();
        assertThat(events).containsExactly("target", "reported");
    }

    @Test
    void submissionFailureReportsEvenWhenBufferCleanupAlsoFails() {
        var events = new ArrayList<String>();
        var submission = new IllegalStateException("submission");
        var cleanup = new IllegalStateException("buffer close");
        var resources = new QuickLitematicaPreviewReadback(() -> events.add("target"));
        resources.buffer(() -> { events.add("buffer"); throw cleanup; });
        resources.failed(submission, reported -> {
            assertThat(reported).isSameAs(submission);
            assertThat(reported.getSuppressed()).containsExactly(cleanup);
            events.add("reported");
        });
        resources.close();
        assertThat(events).containsExactly("buffer", "target", "reported");
    }
}
