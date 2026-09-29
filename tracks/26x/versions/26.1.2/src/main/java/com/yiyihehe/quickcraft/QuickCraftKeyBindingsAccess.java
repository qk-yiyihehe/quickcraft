package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class QuickCraftKeyBindingsAccess {
    private QuickCraftKeyBindingsAccess() {
    }

    public static int rightMouseButton() {
        return GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    public static int middleMouseButton() {
        return GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    }

    static boolean isMouseButtonDown(Minecraft client, int button) {
        return client != null
                && GLFW.glfwGetMouseButton(client.getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }

    static boolean isLeftMouseButtonDown(Minecraft client) {
        return isMouseButtonDown(client, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    static boolean isPhysicalKeyDown(Minecraft client, KeyMapping keyBinding) {
        InputConstants.Key boundKey = InputConstants.getKey(keyBinding.saveString());
        if (boundKey == null || boundKey == InputConstants.UNKNOWN) {
            return false;
        }
        long windowHandle = client.getWindow().handle();
        InputConstants.Type keyType = boundKey.getType();
        int code = boundKey.getValue();
        if (keyType == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(windowHandle, code) == GLFW.GLFW_PRESS;
        }
        if (keyType == InputConstants.Type.KEYSYM) {
            return GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
