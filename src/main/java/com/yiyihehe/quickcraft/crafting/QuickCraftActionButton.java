package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Small icon button shared by the workbench, inventory, and stonecutter quick-craft actions. */
public final class QuickCraftActionButton extends QuickDraggableButton {
    private static final int SIZE = 10;
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/craft_action_button.png");

    public QuickCraftActionButton(int x, int y, OnPress onPress, PositionKey positionKey, Component tooltip) {
        super(x, y, SIZE, SIZE, tooltip, onPress, positionKey);
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int textureV = this.isPositionDragging() ? SIZE * 2 : hovered ? SIZE : 0;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE,
                this.getX(), this.getY(), 0.0F, textureV, SIZE, SIZE, SIZE, SIZE * 3);
    }
}
