package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;

final class QuickCraftKeyBindingsAccess {
    private QuickCraftKeyBindingsAccess() {
    }

    static boolean isMouseButtonDown(Minecraft client, int button) {
        return client != null && (SDLMouse.SDL_GetMouseState(null, null) & buttonMask(button)) != 0;
    }

    static boolean isLeftMouseButtonDown(Minecraft client) {
        return isMouseButtonDown(client, InputConstants.MOUSE_BUTTON_LEFT);
    }

    static boolean isPhysicalKeyDown(Minecraft client, KeyMapping keyBinding) {
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
