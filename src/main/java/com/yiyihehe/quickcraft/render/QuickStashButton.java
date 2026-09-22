package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Icon button for moving matching player items into the open container. */
public final class QuickStashButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/quick_stash.png");

    public QuickStashButton(int x, int y, PressAction onPress, PositionKey positionKey, Text tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        context.drawTexture(ICON, x, y, 0, 0, 10, 10, 10, 10);
    }
}
