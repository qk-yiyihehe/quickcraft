package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 统一读取 QuickCraft 使用的原版按键、malilib 热键和物理修饰键状态。
 */
public final class QuickCraftKeyBindings {
    private QuickCraftKeyBindings() {
    }

    public static boolean isVanillaKeyDown(MinecraftClient client, KeyBinding keyBinding) {
        if (keyBinding == null || keyBinding.isUnbound()) {
            return false;
        }

        return keyBinding.isPressed()
                || client != null && isPhysicalKeyDown(client, keyBinding);
    }

    public static boolean isHotkeyDown(IKeybind keybind) {
        return keybind != null && keybind.isKeybindHeld();
    }

    /**
     * 当 malilib 热键和原版键完全相同时，同时接受原版逻辑按键状态。
     * 这让通过 {@link KeyBinding#setPressed(boolean)} 模拟长按的模组能驱动对应功能，
     * 又不会把带额外修饰键的自定义热键误判为按下。
     */
    public static boolean isHotkeyDown(MinecraftClient client, IKeybind keybind, KeyBinding vanillaFallback) {
        if (isHotkeyDown(keybind)) {
            return true;
        }
        if (keybind == null || vanillaFallback == null || vanillaFallback.isUnbound()) {
            return false;
        }

        return keybind.matches(KeybindMulti.getKeyCode(vanillaFallback))
                && isVanillaKeyDown(client, vanillaFallback);
    }

    public static boolean isShiftDown() {
        return Screen.hasShiftDown();
    }

    public static boolean isAltDown() {
        return Screen.hasAltDown();
    }

    public static boolean isMouseButtonDown(MinecraftClient client, int button) {
        return client != null
                && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
    }

    public static boolean isLeftMouseButtonDown(MinecraftClient client) {
        return isMouseButtonDown(client, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static boolean isPhysicalKeyDown(MinecraftClient client, KeyBinding keyBinding) {
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
