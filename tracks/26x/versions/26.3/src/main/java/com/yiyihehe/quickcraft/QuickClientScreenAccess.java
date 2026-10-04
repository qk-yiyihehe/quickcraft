package com.yiyihehe.quickcraft;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.Blaze3D;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** 26.3 客户端屏幕与系统交互适配，窗口管理使用 SDL3。 */
public final class QuickClientScreenAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickClientScreenAccess.class);
    private QuickClientScreenAccess() {
    }

    public static Screen currentScreen(Minecraft client) {
        return client.gui.screen();
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    public static void clearDraggingState(AbstractContainerScreen<?> screen) {
        // 26.2+ 原版 AbstractContainerScreen 无 clearDraggingState()，拖拽状态由新版事件模型管理
    }

    public static void openPath(Path path) {
        if (Platform.isWindows()) {
            // SDL_OpenURL 按 URL 打开 file URI；本地路径交给 Windows Shell，兼容游戏的 AWT headless 模式。
            var result = Shell32.INSTANCE.ShellExecute(null, "open", path.toAbsolutePath().normalize().toString(),
                    null, null, WinUser.SW_SHOWNORMAL);
            if (result.longValue() <= 32L) {
                LOGGER.warn("Could not open path {}: Windows Shell error {}", path, result.longValue());
            }
            return;
        }
        Blaze3D.openPath(path);
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }

    public static boolean isFullscreen(Minecraft client) {
        long handle = client.getWindow().handle();
        return handle != 0L && (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0L;
    }

    public static void exitFullscreen(Minecraft client) {
        client.getWindow().setFullscreen(false);
    }

    public static boolean isExclusiveFullscreen(Minecraft client) {
        return isFullscreen(client);
    }

    public static boolean isMaximizedWindow(Minecraft client) {
        long handle = client.getWindow().handle();
        return handle != 0L && (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0L;
    }

    public static void setAutoIconify(Minecraft client, boolean enabled) {
        // SDL 3 不再提供 GLFW 的独占全屏自动最小化开关。
    }

    public static void maximizeGameWindow(Minecraft client) {
        maximizeGameWindow(client.getWindow().handle());
    }

    public static void restoreGameWindow(Minecraft client, boolean restoreFullscreen, boolean keepMaximized) {
        long handle = client.getWindow().handle();
        if (handle != 0L) {
            boolean iconified = (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0L;
            if (keepMaximized) {
                maximizeGameWindow(handle);
            } else if (iconified) {
                SDLVideo.SDL_RestoreWindow(handle);
            }
            SDLVideo.SDL_ShowWindow(handle);
            SDLVideo.SDL_RaiseWindow(handle);
            restoreWindowsGameWindow(handle, keepMaximized, iconified);
        }
        if (restoreFullscreen && !isFullscreen(client)) {
            client.getWindow().setFullscreen(true);
        }
    }

    private static void maximizeGameWindow(long handle) {
        if (handle == 0L) {
            return;
        }
        if ((SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0L) {
            SDLVideo.SDL_RestoreWindow(handle);
        }
        if ((SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_MAXIMIZED) == 0L) {
            SDLVideo.SDL_MaximizeWindow(handle);
        }
    }

    private static void restoreWindowsGameWindow(long windowHandle, boolean keepMaximized, boolean iconified) {
        if (!Platform.isWindows()) {
            return;
        }
        try {
            int properties = SDLVideo.SDL_GetWindowProperties(windowHandle);
            long hwndValue = SDLProperties.SDL_GetPointerProperty(
                    properties,
                    SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
                    0L
            );
            if (hwndValue == 0L) {
                return;
            }
            HWND hwnd = new HWND();
            hwnd.setPointer(new Pointer(hwndValue));
            if (keepMaximized) {
                User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWMAXIMIZED);
            } else if (iconified) {
                User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
            }
            User32.INSTANCE.SetForegroundWindow(hwnd);
        } catch (Throwable ignored) {
        }
    }
    @Nullable
    public static Pointer getWin32Hwnd(long windowHandle) {
        if (windowHandle == 0L || !Platform.isWindows()) {
            return null;
        }
        try {
            int properties = SDLVideo.SDL_GetWindowProperties(windowHandle);
            long hwndValue = SDLProperties.SDL_GetPointerProperty(
                    properties,
                    SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
                    0L
            );
            return hwndValue != 0L ? new Pointer(hwndValue) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
