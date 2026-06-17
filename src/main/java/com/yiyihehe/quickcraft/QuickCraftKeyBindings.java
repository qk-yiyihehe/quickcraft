package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
    public static boolean isBoundKeyDown(MinecraftClient client, KeyBinding keyBinding) {
        if (client == null || keyBinding == null) {
            return false;
        }

        if (keyBinding.isUnbound()) {
            return false;
        }

        InputUtil.Key boundKey = InputUtil.fromTranslationKey(keyBinding.getBoundKeyTranslationKey());
        if (boundKey == null || boundKey == InputUtil.UNKNOWN_KEY) {
            return false;
        }

        long windowHandle = client.getWindow().getHandle();
        InputUtil.Type keyType = boundKey.getCategory();
        int code = boundKey.getCode();

        if (keyType == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(windowHandle, code) == GLFW.GLFW_PRESS;
        }
        if (keyType == InputUtil.Type.KEYSYM) {
            return GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
        }

        return false;
    }
}
