package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Compact icon button used on the title bars of vanilla container screens. */
public abstract class QuickCompactIconButton extends QuickDraggableButton {
    public static final int SIZE = 14;

    protected QuickCompactIconButton(int x, int y, Component label, OnPress onPress, PositionKey positionKey) {
        super(x, y, SIZE, SIZE, label, onPress, positionKey);
        this.setTooltip(Tooltip.create(label));
    }

    @Override
    protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int surfaceColor = this.isPositionDragging()
                ? 0xE04A6F8F
                : this.getSurfaceColor(hovered);
        graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + SIZE, this.getY() + SIZE, 0x70000000);
        graphics.fill(this.getX(), this.getY(), this.getX() + SIZE - 1, this.getY() + SIZE - 1, surfaceColor);
        this.extractIcon(graphics, this.getX() + 2, this.getY() + 2, hovered);
    }

    protected int getSurfaceColor(boolean hovered) {
        return hovered ? 0xE0525252 : 0xC0323232;
    }

    protected abstract void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered);
}
