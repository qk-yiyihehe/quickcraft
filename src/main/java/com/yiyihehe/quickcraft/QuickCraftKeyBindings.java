package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * QuickCraft 输入辅助。
 */
public final class QuickCraftKeyBindings {
    private QuickCraftKeyBindings() {
    }

    /**
     * 读取当前绑定键是否处于按下状态。
     * 这里继续走 GLFW 轮询，保持和原有“按住/松开边沿检测”一致。
     */
    public static boolean isBoundKeyDown(Minecraft client, KeyMapping keyBinding) {
        if (client == null || keyBinding == null) {
            return false;
        }

        if (keyBinding.isUnbound()) {
            return false;
        }

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
