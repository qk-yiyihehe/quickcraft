package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageResize;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 将本地图片或 3D 快照转换为 Litematica 的正方形 ARGB 预览数据，并安全写回原理图文件。
 */
final class QuickLitematicaPreviewImageWriter {
    static final int EMBEDDED_PREVIEW_DIMENSION = 1024;

    private static final long MAX_SOURCE_FILE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_SOURCE_DIMENSION = 8192;
    private static final long MAX_SOURCE_PIXELS = (long) MAX_SOURCE_DIMENSION * MAX_SOURCE_DIMENSION;
    private static final String[] SUPPORTED_EXTENSIONS = {"png", "jpg", "jpeg"};

    private QuickLitematicaPreviewImageWriter() {
    }

    @Nullable
    static Path chooseImage(Path schematicPath) {
        Path initialDirectory = schematicPath.toAbsolutePath().normalize().getParent();
        String defaultPath = initialDirectory == null ? "" : initialDirectory.toString();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(SUPPORTED_EXTENSIONS.length);
            for (String extension : SUPPORTED_EXTENSIONS) {
                filters.put(stack.UTF8("*." + extension));
            }
            filters.flip();

            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("quickcraft.litematica.preview_3d.select_image_title").getString(),
                    defaultPath,
                    filters,
                    Component.translatable("quickcraft.litematica.preview_3d.image_files").getString(),
                    false
            );
            return selected == null ? null : Path.of(selected).toAbsolutePath().normalize();
        }
    }

    static int[] readImagePixels(Path imagePath) throws IOException {
        validateSourceFile(imagePath);

        int[] width = new int[1];
        int[] height = new int[1];
        int[] channels = new int[1];
        if (!STBImage.stbi_info(imagePath.toString(), width, height, channels)) {
            throw new InvalidImageException();
        }

        long sourcePixels = (long) width[0] * height[0];
        if (width[0] <= 0 || height[0] <= 0 || sourcePixels > MAX_SOURCE_PIXELS) {
            throw new ImageTooLargeException();
        }

        ByteBuffer source = STBImage.stbi_load(imagePath.toString(), width, height, channels, 4);
        if (source == null) {
            throw new InvalidImageException();
        }

        try {
            return cropAndScaleToArgb(source, width[0], height[0]);
        } finally {
            STBImage.stbi_image_free(source);
        }
    }

    static void writePreview(Path schematicPath, int[] pixels) throws IOException {
        updatePreview(schematicPath, pixels);
    }

    static void removePreview(Path schematicPath) throws IOException {
        updatePreview(schematicPath, null);
    }

    private static void updatePreview(Path schematicPath, @Nullable int[] pixels) throws IOException {
        Path target = schematicPath.toAbsolutePath().normalize();
        Path directory = target.getParent();
        Path targetName = target.getFileName();
        if (directory == null || targetName == null || !Files.isRegularFile(target) || !Files.isWritable(target)) {
            throw new IOException("Schematic file is not writable");
        }

        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
                directory,
                targetName.toString(),
                fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC
        );
        if (schematic == null) {
            throw new IOException("Failed to read litematic file");
        }

        schematic.getMetadata().setPreviewImagePixelData(pixels);
        schematic.getMetadata().setTimeModifiedToNow();

        Path temporary = Files.createTempFile(directory, ".quickcraft-preview-", LitematicaSchematic.FILE_EXTENSION);
        try {
            Path temporaryName = temporary.getFileName();
            if (temporaryName == null
                    || !schematic.writeToFile(directory, temporaryName.toString(), true)) {
                throw new IOException("Failed to write temporary litematic file");
            }
            replaceFile(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String failureTranslationKey(Throwable throwable) {
        if (throwable instanceof ImageTooLargeException) {
            return "quickcraft.litematica.preview_3d.image_too_large";
        }
        if (throwable instanceof InvalidImageException) {
            return "quickcraft.litematica.preview_3d.image_invalid";
        }
        return "quickcraft.litematica.preview_3d.preview_write_failed";
    }

    private static void validateSourceFile(Path imagePath) throws IOException {
        Path fileNamePath = imagePath.getFileName();
        if (fileNamePath == null) {
            throw new InvalidImageException();
        }
        String fileName = fileNamePath.toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        boolean supported = false;
        for (String candidate : SUPPORTED_EXTENSIONS) {
            if (candidate.equals(extension)) {
                supported = true;
                break;
            }
        }

        if (!supported || !Files.isRegularFile(imagePath) || !Files.isReadable(imagePath)) {
            throw new InvalidImageException();
        }
        if (Files.size(imagePath) > MAX_SOURCE_FILE_BYTES) {
            throw new ImageTooLargeException();
        }
    }

    private static int[] cropAndScaleToArgb(ByteBuffer source, int width, int height) throws IOException {
        int sourceSide = Math.min(width, height);
        int targetSide = EMBEDDED_PREVIEW_DIMENSION;
        int cropX = (width - sourceSide) / 2;
        int cropY = (height - sourceSide) / 2;
        int[] argb = new int[targetSide * targetSide];

        if (targetSide == sourceSide) {
            copyArgb(source, width, cropX, cropY, targetSide, argb);
            return argb;
        }

        ByteBuffer scaled = MemoryUtil.memAlloc(targetSide * targetSide * 4);
        try {
            ByteBuffer cropped = source.duplicate();
            cropped.position((cropY * width + cropX) * 4);
            cropped = cropped.slice();
            boolean resized = STBImageResize.stbir_resize_uint8_srgb(
                    cropped,
                    sourceSide,
                    sourceSide,
                    width * 4,
                    scaled,
                    targetSide,
                    targetSide,
                    0,
                    4
            ) != null;
            if (!resized) {
                throw new IOException("Failed to resize image");
            }
            copyArgb(scaled, targetSide, 0, 0, targetSide, argb);
            return argb;
        } finally {
            MemoryUtil.memFree(scaled);
        }
    }

    private static void copyArgb(ByteBuffer source, int stridePixels, int startX, int startY, int size, int[] output) {
        for (int y = 0; y < size; y++) {
            int sourceRow = ((startY + y) * stridePixels + startX) * 4;
            int outputRow = y * size;
            for (int x = 0; x < size; x++) {
                int index = sourceRow + x * 4;
                int red = source.get(index) & 0xFF;
                int green = source.get(index + 1) & 0xFF;
                int blue = source.get(index + 2) & 0xFF;
                int alpha = source.get(index + 3) & 0xFF;
                output[outputRow + x] = alpha << 24 | red << 16 | green << 8 | blue;
            }
        }
    }

    private static void replaceFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException atomicFailure) {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    static final class ImageTooLargeException extends IOException {
    }

    static final class InvalidImageException extends IOException {
    }
}
