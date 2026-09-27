package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewImageWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void centerCropsPngAndScalesToFixedPreviewDimension() throws Exception {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            image.setRGB(0, y, 0xFF0000FF);
            image.setRGB(1, y, 0xFF00FF00);
            image.setRGB(2, y, 0xFFFF0000);
            image.setRGB(3, y, 0xFF0000FF);
        }
        Path path = this.temporaryDirectory.resolve("preview.png");
        ImageIO.write(image, "png", path.toFile());

        int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(path);

        assertThat(pixels).hasSize(1024 * 1024);
        assertThat(pixels[0]).isEqualTo(0xFF00FF00);
        assertThat(pixels[1023]).isEqualTo(0xFFFF0000);
    }

    @Test
    void readsJpegAndAddsOpaqueAlpha() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Color expected = new Color(0x33, 0x66, 0x99);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, expected.getRGB());
            }
        }
        Path path = this.temporaryDirectory.resolve("preview.jpg");
        ImageIO.write(image, "jpg", path.toFile());

        int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(path);

        assertThat(pixels).hasSize(1024 * 1024);
        Color actual = new Color(pixels[0], true);
        assertThat(actual.getAlpha()).isEqualTo(255);
        assertThat(actual.getRed()).isCloseTo(expected.getRed(), within(4));
        assertThat(actual.getGreen()).isCloseTo(expected.getGreen(), within(4));
        assertThat(actual.getBlue()).isCloseTo(expected.getBlue(), within(4));
    }

    @Test
    void scalesLargeImagesToFixedPreviewDimension() throws Exception {
        BufferedImage image = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB);
        Path path = this.temporaryDirectory.resolve("large.png");
        ImageIO.write(image, "png", path.toFile());

        int[] pixels = QuickLitematicaPreviewImageWriter.readImagePixels(path);

        assertThat(pixels).hasSize(1024 * 1024);
    }

    private static org.assertj.core.data.Offset<Integer> within(int value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
