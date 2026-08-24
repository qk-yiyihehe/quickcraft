package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Icon button for moving container items into the player inventory. */
public final class QuickRetrieveButton extends QuickCompactIconButton {
    public QuickRetrieveButton(int x, int y, OnPress onPress, PositionKey positionKey, Component tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor context, int x, int y, boolean hovered) {
        int color = hovered ? 0xFFFFFFFF : 0xFFE0E0E0;
        context.fill(x + 4, y + 1, x + 6, y + 5, color);
        context.fill(x + 2, y + 4, x + 8, y + 5, color);
        context.fill(x + 3, y + 5, x + 7, y + 6, color);
        context.fill(x + 4, y + 6, x + 6, y + 7, color);
        context.fill(x + 1, y + 7, x + 2, y + 10, color);
        context.fill(x + 8, y + 7, x + 9, y + 10, color);
        context.fill(x + 1, y + 9, x + 9, y + 10, color);
    }
}
