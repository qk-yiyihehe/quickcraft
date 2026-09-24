package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
/** Icon button for moving container items into the player inventory. */
public final class QuickRetrieveButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/quick_retrieve.png");

    public QuickRetrieveButton(int x, int y, PressAction onPress, PositionKey positionKey,
                               net.minecraft.text.Text tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ICON, x, y, 0, 0,
                10, 10, 10, 10);
    }
}
