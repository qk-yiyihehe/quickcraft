package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Compact icon button used on the title bars of vanilla container screens. */
public abstract class QuickCompactIconButton extends QuickDraggableButton {
    public static final int SIZE = 14;
    private static final Identifier BACKGROUND_TEXTURE =
            Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/compact_button.png");

    protected QuickCompactIconButton(int x, int y, Component label, OnPress onPress, PositionKey positionKey) {
        super(x, y, SIZE, SIZE, label, onPress, positionKey);
        this.setTooltip(Tooltip.create(label));
    }

    @Override
    protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int textureV = this.isPositionDragging() ? SIZE * 2 : hovered ? SIZE : 0;
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE,
                this.getX(), this.getY(), 0.0F, textureV, SIZE, SIZE, SIZE, SIZE * 3);
        this.extractIcon(graphics, this.getX() + 2, this.getY() + 2, hovered);
    }

    protected abstract void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered);
}
