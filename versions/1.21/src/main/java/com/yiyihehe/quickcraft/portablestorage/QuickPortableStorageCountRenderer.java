package com.yiyihehe.quickcraft.portablestorage;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

final class QuickPortableStorageCountRenderer {
    private QuickPortableStorageCountRenderer() {}

    static void draw(DrawContext graphics, TextRenderer textRenderer, String count, int x, int y) {
        var matrices = graphics.getMatrices();
        matrices.push();
        try {
            // 与原版堆叠数量文字的渲染深度一致，避免 Z=150 处的物品遮住文字。
            matrices.translate(0.0F, 0.0F, 200.0F);
            graphics.drawTextWithShadow(textRenderer, count, x, y, 0xFFFFFFFF);
        } finally {
            matrices.pop();
        }
    }
}
