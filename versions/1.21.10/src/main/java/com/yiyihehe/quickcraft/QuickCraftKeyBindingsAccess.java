package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;

final class QuickCraftKeyBindingsAccess {
    private QuickCraftKeyBindingsAccess() {
    }

    static boolean isShiftDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isShiftPressed();
    }

    static boolean isAltDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isAltPressed();
    }
}
