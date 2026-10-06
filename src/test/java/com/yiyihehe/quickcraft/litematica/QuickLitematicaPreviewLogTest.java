package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewLogTest {
    @Test
    void restoresTheOuterProjectionAfterANestedCallbackFails() {
        var first = new QuickLitematicaPreviewLog.Trace(Path.of("first.litematic"), System.nanoTime());
        var second = new QuickLitematicaPreviewLog.Trace(Path.of("second.litematic"), System.nanoTime());
        try (var scope = first.bind()) {
            assertThatThrownBy(() -> second.wrap(() -> {
                assertThat(QuickLitematicaPreviewLog.current()).isSameAs(second);
                throw new IllegalStateException("callback failed");
            }).run()).isInstanceOf(IllegalStateException.class);
            assertThat(QuickLitematicaPreviewLog.current()).isSameAs(first);
        }
        assertThat(QuickLitematicaPreviewLog.current()).isNull();
    }

    @Test
    void asynchronousPersistenceKeepsItsProjectionWithoutLeakingTheThreadContext() throws Exception {
        var owner = new QuickLitematicaPreviewLog.Trace(Path.of("projection.litematic"), System.nanoTime());
        CompletableFuture.runAsync(() -> {
            owner.wrapConsumer(value -> assertThat(QuickLitematicaPreviewLog.current()).isSameAs(owner)).accept("saved");
            assertThat(QuickLitematicaPreviewLog.current()).isNull();
        }).get();
    }
}
