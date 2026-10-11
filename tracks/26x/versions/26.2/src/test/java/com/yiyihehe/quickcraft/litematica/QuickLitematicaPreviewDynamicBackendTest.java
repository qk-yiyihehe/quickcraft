package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuickLitematicaPreviewDynamicBackendTest {
    @Test
    void fallbackIsNotExportReadyUntilRenderStatesArePublishedAndCloseClearsThem() {
        var backend = new QuickLitematicaPreviewDynamicBackend() {};
        var data = mock(QuickLitematicaPreview3D.MeshData.class);
        when(data.hasDynamicContent()).thenReturn(true);
        when(data.dynamicSceneIfReady()).thenReturn(new QuickLitematicaPreview3D.DynamicScene(null, Map.of(), List.of()));

        backend.prepareDynamicFrame(data);
        assertThat(backend.dynamicFrameProgress()).isEqualTo(1.0F);
        assertThat(backend.dynamicContentProgress()).isZero();
        assertThat(backend.prepareDynamicStates(data)).isTrue();
        assertThat(backend.dynamicContentProgress()).isEqualTo(1.0F);
        assertThat(backend.preparedDynamicScene).isNotNull();

        backend.closeDynamicFrame();
        assertThat(backend.dynamicContentProgress()).isZero();
        assertThat(backend.preparedDynamicScene).isNull();
    }

    @Test
    void unfinishedSceneDoesNotEnterImmediateExtraction() {
        var backend = new QuickLitematicaPreviewDynamicBackend() {};
        var data = mock(QuickLitematicaPreview3D.MeshData.class);
        when(data.hasDynamicContent()).thenReturn(true);

        assertThat(backend.prepareDynamicStates(data)).isFalse();
        assertThat(backend.preparedDynamicScene).isNull();
        assertThat(backend.dynamicContentProgress()).isZero();
        backend.closeDynamicFrame();
    }
}
