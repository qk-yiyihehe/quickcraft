package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreview3DCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesFileContentsInsteadOfFileMetadata() throws Exception {
        Path first = this.temporaryDirectory.resolve("first.litematic");
        Path second = this.temporaryDirectory.resolve("second.litematic");
        Files.write(first, new byte[]{1, 2, 3, 4});
        Files.write(second, new byte[]{1, 2, 3, 5});
        Files.setLastModifiedTime(second, Files.getLastModifiedTime(first));

        String firstHash = QuickLitematicaPreview3D.hashFile(first);
        String secondHash = QuickLitematicaPreview3D.hashFile(second);

        assertThat(firstHash).hasSize(64);
        assertThat(secondHash).hasSize(64).isNotEqualTo(firstHash);
    }

    @Test
    void keepsOneCacheSlotWhenTheSameProjectionChanges() throws Exception {
        Path schematic = this.temporaryDirectory.resolve("same-name.litematic");
        Files.write(schematic, new byte[]{1, 2, 3});
        String initialSlot = QuickLitematicaPreview3D.cacheKey(schematic);

        Files.write(schematic, new byte[]{4, 5, 6, 7});

        assertThat(QuickLitematicaPreview3D.cacheKey(schematic)).isEqualTo(initialSlot);
    }
}
