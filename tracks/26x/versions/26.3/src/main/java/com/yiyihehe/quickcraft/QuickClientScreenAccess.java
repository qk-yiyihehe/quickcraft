package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.nio.file.Path;

/** 26.3 客户端屏幕与系统交互适配（openPath 改用 Blaze3D）。 */
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
        Blaze3D.openPath(path);
    }
}
