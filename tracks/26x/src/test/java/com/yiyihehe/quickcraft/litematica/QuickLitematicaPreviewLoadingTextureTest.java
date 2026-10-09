package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewLoadingTextureTest {
    private static PreviewGuiElement view(int size, float dragX, double angle, ScreenRectangle scissor) {
        return new PreviewGuiElement(null, 0, 0, size, dragX, 0, angle, 0, 1, scissor, null);
    }

    @Test
    void leavesTimeForUploadsEvenAfterAGpuStallAndDoesNotExtendTheDeadline() {
        LoadingTexture texture = new LoadingTexture();
        PreviewGuiElement view = view(100, 0, 0, null);
        assertThat(texture.canReuse(view, true, 0)).isFalse();
        texture.rendered(view);
        long afterGpuStall = 2_000_000_000L;
        assertThat(texture.canReuse(view(100, 0, 0, null), true, afterGpuStall)).isTrue();
        assertThat(texture.canReuse(view, true, afterGpuStall + 249_999_999)).isTrue();
        assertThat(texture.canReuse(view, true, afterGpuStall + 250_000_000)).isFalse();
        assertThat(texture.canReuse(view, true, afterGpuStall + 300_000_000)).isFalse();
        texture.rendered(view);
        assertThat(texture.canReuse(view, true, afterGpuStall + 300_000_000)).isTrue();
    }

    @Test
    void redrawsViewChangesAndCompletionWithoutFreezingDynamicPreparation() {
        LoadingTexture texture = new LoadingTexture();
        PreviewGuiElement first = view(100, 0, 0, null);
        texture.rendered(first);
        assertThat(texture.canReuse(first, true, 0)).isTrue();
        assertThat(texture.canReuse(view(100, 1, 0, null), true, 1)).isFalse();
        assertThat(texture.canReuse(view(100, 0, 90, null), true, 1)).isFalse();
        assertThat(texture.canReuse(view(200, 0, 0, null), true, 1)).isFalse();
        assertThat(texture.canReuse(view(100, 0, 0, new ScreenRectangle(0, 0, 50, 50)), true, 1)).isFalse();
        assertThat(texture.canReuse(first, false, 1)).isFalse();
        texture.rendered(first);
        assertThat(texture.canReuse(first, false, 2)).isFalse();
        PreviewGuiElement second = view(200, 0, 0, null);
        texture.rendered(second);
        assertThat(texture.canReuse(first, true, 3)).isFalse();
    }
}
