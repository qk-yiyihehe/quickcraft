package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 26.3 本地图片选择适配（使用 SDLDialog 异步对话框）。 */
final class QuickLitematicaPreviewImageWriterAccess {
    private static final Set<SDL_DialogFileCallback> ACTIVE_DIALOG_CALLBACKS = ConcurrentHashMap.newKeySet();

    private QuickLitematicaPreviewImageWriterAccess() {
    }

    static void chooseImage(Path schematicPath, String[] supportedExtensions, Consumer<@Nullable Path> resultConsumer) {
        Path initialDirectory = schematicPath.toAbsolutePath().normalize().getParent();
        String defaultPath = initialDirectory == null ? "" : initialDirectory.toString();
        SDL_DialogFileCallback[] holder = new SDL_DialogFileCallback[1];
        SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            Path selected = null;
            try {
                long selectedAddress = fileList == 0L ? 0L : MemoryUtil.memGetAddress(fileList);
                String selectedPath = MemoryUtil.memUTF8Safe(selectedAddress);
                if (selectedPath != null && !selectedPath.isBlank()) {
                    selected = Path.of(selectedPath).toAbsolutePath().normalize();
                }
                Path result = selected;
                Minecraft.getInstance().execute(() -> resultConsumer.accept(result));
            } finally {
                SDL_DialogFileCallback retained = holder[0];
                if (retained != null) {
                    ACTIVE_DIALOG_CALLBACKS.remove(retained);
                    retained.free();
                }
            }
        });
        holder[0] = callback;
        ACTIVE_DIALOG_CALLBACKS.add(callback);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1, stack);
            filters.get(0).name(stack.UTF8(Component.translatable(
                    "quickcraft.litematica.preview_3d.image_files").getString()))
                    .pattern(stack.UTF8(String.join(";", supportedExtensions)));
            SDLDialog.SDL_ShowOpenFileDialog(
                    callback,
                    0L,
                    Minecraft.getInstance().getWindow().handle(),
                    filters,
                    defaultPath,
                    false
            );
        } catch (Throwable throwable) {
            ACTIVE_DIALOG_CALLBACKS.remove(callback);
            callback.free();
            throw throwable;
        }
    }
}
