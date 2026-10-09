package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;

class QuickLitematicaPreviewTransparentLayersTest {
    @Test
    void waterGlassAndPortalsShareOneSortableMesh() {
        MeshCollector collector = new MeshCollector();
        var glass = collector.consumerFor(LayerKey.TRANSLUCENT);
        assertThat(collector.consumerFor(LayerKey.FLUID)).isSameAs(glass);
        assertThat(collector.consumerFor(LayerKey.PORTAL)).isSameAs(glass);
        assertThat(collector.consumerFor(LayerKey.SOLID)).isNotSameAs(glass);
    }
}
