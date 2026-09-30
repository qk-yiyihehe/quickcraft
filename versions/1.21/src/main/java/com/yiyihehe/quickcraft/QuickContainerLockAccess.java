package com.yiyihehe.quickcraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Identifier;

/**
 * 1.21 容器快捷栏槽位访问与材质绘制适配器。
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
                texture,
                left,
                top,
                width,
                height,
                0.0F,
                0.0F,
                textureWidth,
                textureHeight,
                textureWidth,
                textureHeight
        );
    }
}
