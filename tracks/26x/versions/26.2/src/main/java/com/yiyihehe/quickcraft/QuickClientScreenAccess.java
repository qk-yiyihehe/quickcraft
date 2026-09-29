package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class QuickClientScreenAccess {
    private QuickClientScreenAccess() {
    }

    public static Screen currentScreen(Minecraft client) {
        return client.gui.screen();
    }
}
