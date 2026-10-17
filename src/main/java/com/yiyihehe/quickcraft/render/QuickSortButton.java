package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

public final class QuickSortButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/quick_sort.png");

    public QuickSortButton(int x, int y, PressAction onPress, PositionKey positionKey, net.minecraft.text.Text tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        QuickButtonAccess.drawButtonIcon(context, ICON, x, y, 10, 10);
    }
}
