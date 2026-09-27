package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//#if MC>=260300
//$$ import org.lwjgl.sdl.SDLMouse;
//#else
import org.lwjgl.glfw.GLFW;
//#endif

/** 统一读取原版按键、malilib 热键和物理修饰键状态。 */
public final class QuickCraftKeyBindings {
    private QuickCraftKeyBindings() {
    }

    public static boolean isVanillaKeyDown(Minecraft client, KeyMapping keyBinding) {
        if (keyBinding == null || keyBinding.isUnbound()) {
            return false;
        }
        return keyBinding.isDown() || client != null && isPhysicalKeyDown(client, keyBinding);
    }

    public static boolean isHotkeyDown(IKeybind keybind) {
        return keybind != null && keybind.isKeybindHeld();
    }

    /** 仅在热键与原版键完全一致时，接受其他模组设置的原版逻辑按键状态。 */
    public static boolean isHotkeyDown(Minecraft client, IKeybind keybind, KeyMapping vanillaFallback) {
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
        Minecraft client = Minecraft.getInstance();
        return client != null && client.hasShiftDown();
    }

    public static boolean isAltDown() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.hasAltDown();
    }

    public static boolean isMouseButtonDown(Minecraft client, int button) {
        //#if MC>=260300
        //$$ return client != null && (SDLMouse.SDL_GetMouseState(null, null) & buttonMask(button)) != 0;
        //#else
        return client != null
                && GLFW.glfwGetMouseButton(client.getWindow().handle(), button) == GLFW.GLFW_PRESS;
        //#endif
    }

    public static boolean isLeftMouseButtonDown(Minecraft client) {
        //#if MC>=260300
        //$$ return isMouseButtonDown(client, InputConstants.MOUSE_BUTTON_LEFT);
        //#else
        return isMouseButtonDown(client, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        //#endif
    }

    private static boolean isPhysicalKeyDown(Minecraft client, KeyMapping keyBinding) {
        InputConstants.Key boundKey = InputConstants.getKey(keyBinding.saveString());
        if (boundKey == null || boundKey == InputConstants.UNKNOWN) {
            return false;
        }
        //#if MC<260300
        long windowHandle = client.getWindow().handle();
        //#endif
        InputConstants.Type keyType = boundKey.getType();
        int code = boundKey.getValue();
        if (keyType == InputConstants.Type.MOUSE) {
            //#if MC>=260300
            //$$ return isMouseButtonDown(client, code);
            //#else
            return GLFW.glfwGetMouseButton(windowHandle, code) == GLFW.GLFW_PRESS;
            //#endif
        }
        //#if MC>=260300
        //$$ if (keyType == InputConstants.Type.KEYBOARD) {
        //$$     return InputConstants.isKeyDown(code);
        //$$ }
        //#else
        if (keyType == InputConstants.Type.KEYSYM) {
            return GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
        }
        //#endif
        return false;
    }

    //#if MC>=260300
    //$$ private static int buttonMask(int button) {
    //$$     return button > 0 && button <= Integer.SIZE ? 1 << (button - 1) : 0;
    //$$ }
    //#endif
}
