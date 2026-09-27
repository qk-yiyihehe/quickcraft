package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Compact icon button used on the title bars of vanilla container screens. */
public abstract class QuickCompactIconButton extends QuickDraggableButton {
    public static final int SIZE = 14;
    private static final Identifier BACKGROUND_TEXTURE =
            Identifier.of("quickcraft", "textures/gui/compact_button.png");

    protected QuickCompactIconButton(int x, int y, Text label, PressAction onPress, PositionKey positionKey) {
        super(x, y, SIZE, SIZE, label, onPress, positionKey);
        this.setTooltip(Tooltip.of(label));
    }

    @Override
    protected final void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.active && this.isMouseOver(mouseX, mouseY);
        boolean selected = this.isToggleSelected();
        int textureV = this.isPositionDragging() || (hovered && selected)
                ? SIZE * 2 : hovered || selected ? SIZE : 0;
        context.drawTexture(
                RenderLayer::getGuiTextured,
                BACKGROUND_TEXTURE,
                this.getX(),
                this.getY(),
                0,
                textureV,
                SIZE,
                SIZE,
                SIZE,
                SIZE * 3
        );
        this.drawIcon(context, this.getX() + 2, this.getY() + 2, hovered);
    }

    protected boolean isToggleSelected() {
        return false;
    }

    protected abstract void drawIcon(DrawContext context, int x, int y, boolean hovered);
}
