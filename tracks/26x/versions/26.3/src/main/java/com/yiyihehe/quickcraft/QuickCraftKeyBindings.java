package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;

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
        return client != null && (SDLMouse.SDL_GetMouseState(null, null) & buttonMask(button)) != 0;
    }

    public static boolean isLeftMouseButtonDown(Minecraft client) {
        return isMouseButtonDown(client, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static boolean isPhysicalKeyDown(Minecraft client, KeyMapping keyBinding) {
        InputConstants.Key boundKey = InputConstants.getKey(keyBinding.saveString());
        if (boundKey == null || boundKey == InputConstants.UNKNOWN) {
            return false;
        }
        InputConstants.Type keyType = boundKey.getType();
        int code = boundKey.getValue();
        if (keyType == InputConstants.Type.MOUSE) {
            return isMouseButtonDown(client, code);
        }
        if (keyType == InputConstants.Type.KEYBOARD) {
            return InputConstants.isKeyDown(code);
        }
        return false;
    }

    private static int buttonMask(int button) {
        return button > 0 && button <= Integer.SIZE ? 1 << (button - 1) : 0;
    }
}
