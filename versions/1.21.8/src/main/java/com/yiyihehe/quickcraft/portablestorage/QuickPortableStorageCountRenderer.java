package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

final class QuickPortableStorageCountRenderer {
    private QuickPortableStorageCountRenderer() {}

    static void draw(DrawContext graphics, TextRenderer textRenderer, String count, int x, int y) {
        graphics.drawTextWithShadow(textRenderer, count, x, y, 0xFFFFFFFF);
    }
}
