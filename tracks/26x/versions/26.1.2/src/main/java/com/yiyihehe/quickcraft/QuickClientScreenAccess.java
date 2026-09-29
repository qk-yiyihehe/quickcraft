package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Util;

import java.nio.file.Path;

/** 26.1.2 客户端屏幕与系统交互适配。 */
public final class QuickClientScreenAccess {
    private QuickClientScreenAccess() {
    }

    public static Screen currentScreen(Minecraft client) {
        return client.screen;
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    public static void clearDraggingState(AbstractContainerScreen<?> screen) {
        screen.clearDraggingState();
    }

    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }
}
