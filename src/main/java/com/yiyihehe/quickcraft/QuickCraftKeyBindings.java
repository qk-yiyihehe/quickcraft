package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * QuickCraft 输入辅助。
 *
 * <p>用于需要“按住状态”的功能。malilib 的热键回调更适合离散动作，这里直接轮询 GLFW，
 * 让右键持续填充、自动填充等待等逻辑能稳定判断按下/松开边沿。</p>
 */
public final class QuickCraftKeyBindings {
    private QuickCraftKeyBindings() {
    }

    /**
     * 读取当前绑定键是否处于按下状态。
     *
     * <p>只处理键盘和鼠标绑定；其它输入类型返回 false，避免把未知输入误判为一直按住。</p>
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
