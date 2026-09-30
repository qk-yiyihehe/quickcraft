package com.yiyihehe.quickcraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Identifier;

/**
 * 1.21.3 容器快捷栏槽位访问与 RenderLayer 材质绘制适配器。
 */
public final class QuickContainerLockAccess {
    private QuickContainerLockAccess() {
    }

    public static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.selectedSlot;
    }

    public static void renderSlotLockIcon(DrawContext context, Identifier texture,
                                          int left, int top, int width, int height,
                                          int textureWidth, int textureHeight) {
        context.drawTexture(
                RenderLayer::getGuiTextured,
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
