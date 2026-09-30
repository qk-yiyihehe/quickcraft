package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/**
 * 1.21.3 按钮材质与图标 RenderLayer 绘制适配器。
 */
public final class QuickButtonAccess {
    private QuickButtonAccess() {
    }

    public static void drawButtonTexture(DrawContext context, Identifier texture,
                                         int x, int y, int u, int v,
                                         int width, int height,
                                         int textureWidth, int textureHeight) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, (float) u, (float) v, width, height, textureWidth, textureHeight);
    }

    public static void drawButtonIcon(DrawContext context, Identifier texture,
                                      int x, int y, int width, int height) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 0.0F, 0.0F, width, height, width, height);
    }
}
