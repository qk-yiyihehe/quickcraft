package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickLitematicaPreviewCancellationTest {
    @Test
    void cancellationIsScopedAndRestoredAfterNestedFailure() {
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try (var outer = QuickLitematicaPreviewCancellation.bind(active)) {
            assertThatThrownBy(() -> {
                try (var inner = QuickLitematicaPreviewCancellation.bind(cancelled)) {
                    QuickLitematicaPreviewCancellation.check();
                }
            }).isInstanceOf(CancellationException.class);
            assertThatCode(QuickLitematicaPreviewCancellation::check).doesNotThrowAnyException();
            active.set(true);
            assertThatThrownBy(QuickLitematicaPreviewCancellation::check).isInstanceOf(CancellationException.class);
        }
        assertThatCode(QuickLitematicaPreviewCancellation::check).doesNotThrowAnyException();
    }
}
