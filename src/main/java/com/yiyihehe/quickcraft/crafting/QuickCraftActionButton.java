package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Small icon button shared by the workbench, inventory, and stonecutter quick-craft actions. */
public final class QuickCraftActionButton extends QuickDraggableButton {
    private static final int SIZE = 10;
    private static final Identifier TEXTURE =
            Identifier.of("quickcraft", "textures/gui/craft_action_button.png");

    public QuickCraftActionButton(int x, int y, PressAction onPress, PositionKey positionKey, Text tooltip) {
        super(x, y, SIZE, SIZE, tooltip, onPress, positionKey);
        this.setTooltip(Tooltip.of(tooltip));
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int textureV = this.isPositionDragging() ? SIZE * 2 : hovered ? SIZE : 0;
        context.drawTexture(TEXTURE, this.getX(), this.getY(), 0, textureV, SIZE, SIZE, SIZE, SIZE * 3);
    }
}
