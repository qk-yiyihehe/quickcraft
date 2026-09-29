package com.yiyihehe.quickcraft.litematica;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.function.Consumer;

/** 26.1.2–26.2 本地图片选择适配（使用 TinyFileDialogs 同步对话框）。 */
final class QuickLitematicaPreviewImageWriterAccess {
    private QuickLitematicaPreviewImageWriterAccess() {
    }

    static void chooseImage(Path schematicPath, String[] supportedExtensions, Consumer<@Nullable Path> resultConsumer) {
        Path initialDirectory = schematicPath.toAbsolutePath().normalize().getParent();
        String defaultPath = initialDirectory == null ? "" : initialDirectory.toString();
        Path selected = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(supportedExtensions.length);
            for (String extension : supportedExtensions) {
                filters.put(stack.UTF8("*." + extension));
            }
            filters.flip();

            String selectedString = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("quickcraft.litematica.preview_3d.select_image_title").getString(),
                    defaultPath,
                    filters,
                    Component.translatable("quickcraft.litematica.preview_3d.image_files").getString(),
                    false
            );
            if (selectedString != null && !selectedString.isBlank()) {
                selected = Path.of(selectedString).toAbsolutePath().normalize();
            }
        }
        resultConsumer.accept(selected);
    }
}
