package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Icon button for moving matching player items into the open container. */
public final class QuickStashButton extends QuickCompactIconButton {
    public QuickStashButton(int x, int y, OnPress onPress, PositionKey positionKey, Component tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
        int color = hovered ? 0xFFFFFFFF : 0xFFE0E0E0;
        graphics.fill(x + 4, y + 1, x + 6, y + 5, color);
        graphics.fill(x + 2, y + 4, x + 8, y + 5, color);
        graphics.fill(x + 3, y + 5, x + 7, y + 6, color);
        graphics.fill(x + 4, y + 6, x + 6, y + 7, color);
        graphics.fill(x + 1, y + 7, x + 2, y + 10, color);
        graphics.fill(x + 8, y + 7, x + 9, y + 10, color);
        graphics.fill(x + 1, y + 9, x + 9, y + 10, color);
    }
}
