package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Util;

import java.nio.file.Path;

/** 26.2 客户端屏幕与系统交互适配。 */
public final class QuickClientScreenAccess {
    private QuickClientScreenAccess() {
    }

    public static Screen currentScreen(Minecraft client) {
        return client.gui.screen();
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    public static void clearDraggingState(AbstractContainerScreen<?> screen) {
        // 26.2+ 原版 AbstractContainerScreen 无 clearDraggingState()，拖拽状态由新版事件模型管理
    }

    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }

    public static boolean isHudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }
}
