package com.yiyihehe.quickcraft.litematica;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewResourcesTest {
    private static Callable<InputStream> data(byte[] bytes) { return () -> new ByteArrayInputStream(bytes); }

    @Test
    void invalidatesSameNameModelChangesAndIgnoresEnumerationOrder() throws Exception {
        Map<String, Callable<InputStream>> resources = new LinkedHashMap<>();
        resources.put("minecraft:models/block/test.json", data(new byte[]{1, 2, 3}));
        resources.put("minecraft:blockstates/test.json", data(new byte[]{4, 5}));
        String signature = QuickLitematicaPreviewResources.hashResources(resources);
        Map<String, Callable<InputStream>> reversed = new LinkedHashMap<>();
        reversed.put("minecraft:blockstates/test.json", resources.get("minecraft:blockstates/test.json"));
        reversed.put("minecraft:models/block/test.json", resources.get("minecraft:models/block/test.json"));
        assertThat(QuickLitematicaPreviewResources.hashResources(reversed)).isEqualTo(signature);
        reversed.put("minecraft:models/block/test.json", data(new byte[]{1, 2, 4}));
        assertThat(QuickLitematicaPreviewResources.hashResources(reversed)).isNotEqualTo(signature);
        resources.remove("minecraft:models/block/test.json");
        assertThat(QuickLitematicaPreviewResources.hashResources(resources)).isNotEqualTo(signature);
    }

    @Test
    void tracksTextureLayoutAndAnimationWithoutReadingPixelPayload() throws Exception {
        byte[] header = new byte[33];
        Map<String, Callable<InputStream>> resources = new LinkedHashMap<>();
        resources.put("minecraft:textures/block/test.png", () -> new ByteArrayInputStream(header) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if ((long) this.pos + length > 33) throw new AssertionError("Pixel payload must not be read");
                return super.read(bytes, offset, length);
            }
        });
        String signature = QuickLitematicaPreviewResources.hashResources(resources);
        byte[] pixelsChanged = java.util.Arrays.copyOf(header, 1000);
        pixelsChanged[999] = 71;
        resources.put("minecraft:textures/block/test.png", data(pixelsChanged));
        assertThat(QuickLitematicaPreviewResources.hashResources(resources)).isEqualTo(signature);
        pixelsChanged[19] = 32;
        assertThat(QuickLitematicaPreviewResources.hashResources(resources)).isNotEqualTo(signature);
        resources.put("minecraft:textures/block/test.png.mcmeta", data(new byte[]{1}));
        String animated = QuickLitematicaPreviewResources.hashResources(resources);
        resources.put("minecraft:textures/block/test.png.mcmeta", data(new byte[]{2}));
        assertThat(QuickLitematicaPreviewResources.hashResources(resources)).isNotEqualTo(animated);
        assertThat(QuickLitematicaPreviewResources.relevant("optifine/ctm/test.properties")).isTrue();
    }
}
