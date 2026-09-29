package com.yiyihehe.quickcraft.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 1.21.8+ 的 HUD 文字颜色参数使用带 Alpha 的 ARGB 形式。 */
final class QuickContainerToolModeHudAccess {
    private QuickContainerToolModeHudAccess() {
    }

    static void draw(MinecraftClient client, DrawContext context, Text label, int x, int y) {
        context.drawTextWithShadow(client.textRenderer, label, x, y, 0xFFFFFFFF);
    }
}
