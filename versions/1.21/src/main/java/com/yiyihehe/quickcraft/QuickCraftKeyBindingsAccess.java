package com.yiyihehe.quickcraft;

import net.minecraft.client.gui.screen.Screen;

final class QuickCraftKeyBindingsAccess {
    private QuickCraftKeyBindingsAccess() {
    }

    static boolean isShiftDown() {
        return Screen.hasShiftDown();
    }

    static boolean isAltDown() {
        return Screen.hasAltDown();
    }
}
