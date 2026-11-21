package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

final class QuickPortableStorageCountRenderer {
    private QuickPortableStorageCountRenderer() {}

    static void draw(DrawContext graphics, TextRenderer textRenderer, String count, int x, int y) {
        var matrices = graphics.getMatrices();
        matrices.push();
        try {
            // Match the vanilla stack count depth so the item at Z=150 cannot cover the text.
            matrices.translate(0.0F, 0.0F, 200.0F);
            graphics.drawTextWithShadow(textRenderer, count, x, y, 0xFFFFFFFF);
        } finally {
            matrices.pop();
        }
    }
}
