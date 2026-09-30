package com.yiyihehe.quickcraft;

import org.jetbrains.annotations.Nullable;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.nio.file.Path;

/** 26.1.2 客户端屏幕与系统交互适配。 */
public final class QuickClientScreenAccess {
    private QuickClientScreenAccess() {
    }

    public static Screen currentScreen(Minecraft client) {
        return client.screen;
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    public static void clearDraggingState(AbstractContainerScreen<?> screen) {
        screen.clearDraggingState();
    }

    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.options.hideGui;
    }

    public static boolean isFullscreen(Minecraft client) {
        return client.getWindow().isFullscreen();
    }

    public static void exitFullscreen(Minecraft client) {
        client.getWindow().toggleFullScreen();
    }

    public static boolean isExclusiveFullscreen(Minecraft client) {
        long handle = client.getWindow().handle();
        if (handle == 0L) {
            return false;
        }
        long monitor = GLFW.glfwGetWindowMonitor(handle);
        if (monitor != 0L) {
            return true;
        }
        return client.getWindow().isFullscreen();
    }

    public static boolean isMaximizedWindow(Minecraft client) {
        long handle = client.getWindow().handle();
        return handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) != GLFW.GLFW_FALSE;
    }

    public static void setAutoIconify(Minecraft client, boolean enabled) {
        long handle = client.getWindow().handle();
        if (handle != 0L) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, enabled ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }
    }

    public static void maximizeGameWindow(Minecraft client) {
        maximizeGameWindow(client.getWindow().handle());
    }

    public static void restoreGameWindow(Minecraft client, boolean restoreFullscreen, boolean keepMaximized) {
        long handle = client.getWindow().handle();
        if (handle != 0L) {
            boolean iconified = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE;
            if (keepMaximized) {
                maximizeGameWindow(handle);
            } else if (iconified) {
                GLFW.glfwRestoreWindow(handle);
            }
            GLFW.glfwShowWindow(handle);
            GLFW.glfwFocusWindow(handle);
            restoreWindowsGameWindow(handle, keepMaximized, iconified);
        }
        if (restoreFullscreen && !client.getWindow().isFullscreen()) {
            client.getWindow().toggleFullScreen();
        }
    }

    private static void maximizeGameWindow(long handle) {
        if (handle == 0L) {
            return;
        }
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE) {
            GLFW.glfwRestoreWindow(handle);
        }
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_FALSE) {
            GLFW.glfwMaximizeWindow(handle);
        }
    }

    private static void restoreWindowsGameWindow(long glfwHandle, boolean keepMaximized, boolean iconified) {
        if (!Platform.isWindows()) {
            return;
        }
        try {
            long hwndValue = GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
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
            long hwndValue = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
            return hwndValue != 0L ? new Pointer(hwndValue) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
