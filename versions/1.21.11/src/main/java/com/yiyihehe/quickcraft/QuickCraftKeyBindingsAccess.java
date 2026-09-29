package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;

final class QuickCraftKeyBindingsAccess {
    private QuickCraftKeyBindingsAccess() {
    }

    static boolean isShiftDown() {
        return MinecraftClient.getInstance().isShiftPressed();
    }

    static boolean isAltDown() {
        return MinecraftClient.getInstance().isAltPressed();
    }
}
