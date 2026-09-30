package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 1.21 按钮材质与图标绘制适配器。
 */
public final class QuickButtonAccess {
    private QuickButtonAccess() {
    }

    public static void drawButtonTexture(DrawContext context, Identifier texture,
                                         int x, int y, int u, int v,
                                         int width, int height,
                                         int textureWidth, int textureHeight) {
        context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    public static void drawButtonIcon(DrawContext context, Identifier texture,
                                      int x, int y, int width, int height) {
        context.drawTexture(texture, x, y, 0, 0, width, height, width, height);
    }
}
