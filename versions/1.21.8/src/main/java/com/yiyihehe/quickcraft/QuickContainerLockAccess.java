package com.yiyihehe.quickcraft;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Identifier;

/**
 * 1.21.8 材质绘制管线迁移至 RenderPipelines.GUI_TEXTURED。
 */
public final class QuickContainerLockAccess {
    private QuickContainerLockAccess() {
    }

    public static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    public static void renderSlotLockIcon(DrawContext context, Identifier texture,
                                          int left, int top, int width, int height,
                                          int textureWidth, int textureHeight) {
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                texture,
                left,
                top,
                0.0F,
                0.0F,
                width,
                height,
                textureWidth,
                textureHeight,
                textureWidth,
                textureHeight
        );
    }
}
