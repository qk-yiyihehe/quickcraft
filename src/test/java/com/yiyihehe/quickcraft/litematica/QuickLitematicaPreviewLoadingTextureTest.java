package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewLoadingTextureTest {
    @Test void reusesOnlyDuringUploadAndInvalidatesViewAndCompletionImmediately() {
        var cache = new QuickLitematicaPreviewLoadingTexture();
        var view = new QuickLitematicaPreviewLoadingTexture.View(null, 20, 40, 256, 0, 0, 1, 0.5F, 1);
        assertThat(cache.canReuse(view, true, 0)).isFalse();
        cache.rendered(view);
        assertThat(cache.canReuse(view, true, 1_000_000_000L)).isTrue();
        assertThat(cache.canReuse(view, true, 1_249_999_999L)).isTrue();
        assertThat(cache.canReuse(view, true, 1_250_000_000L)).isFalse();
        var rotated = new QuickLitematicaPreviewLoadingTexture.View(null, 20, 40, 256, 0, 0, 2, 0.5F, 1);
        assertThat(cache.canReuse(rotated, true, 1_100_000_000L)).isFalse();
        var moved = new QuickLitematicaPreviewLoadingTexture.View(null, 20, 40, 256, 1, 0, 1, 0.5F, 1);
        assertThat(cache.canReuse(moved, true, 1_100_000_000L)).isFalse();
        assertThat(cache.canReuse(view, false, 1_100_000_000L)).isFalse();
        cache.clear();
        assertThat(cache.canReuse(view, true, 1_100_000_000L)).isFalse();
    }
}
