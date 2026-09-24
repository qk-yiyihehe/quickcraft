package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Icon button for moving matching player items into the open container. */
public final class QuickStashButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/quick_stash.png");

    public QuickStashButton(int x, int y, OnPress onPress, PositionKey positionKey, Component tooltip) {
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x, y, 0.0F, 0.0F, 10, 10, 10, 10);
    }
}
