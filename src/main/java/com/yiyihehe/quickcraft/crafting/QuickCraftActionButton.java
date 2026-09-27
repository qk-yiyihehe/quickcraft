package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Small icon button shared by the workbench, inventory, and stonecutter quick-craft actions. */
public final class QuickCraftActionButton extends QuickDraggableButton {
    private static final int SIZE = 10;
    private static final Identifier TEXTURE =
            Identifier.of("quickcraft", "textures/gui/craft_action_button.png");

    //#if MC>=12111
    //$$ public QuickCraftActionButton(int x, int y, PressAction onPress, PositionKey positionKey,
    //$$                               net.minecraft.text.Text tooltip) {
    //#else
    public QuickCraftActionButton(int x, int y, PressAction onPress, PositionKey positionKey, Text tooltip) {
    //#endif
        super(x, y, SIZE, SIZE, tooltip, onPress, positionKey);
        this.setTooltip(Tooltip.of(tooltip));
    }

    @Override
    //#if MC>=12111
    //$$ protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
    //#else
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
    //#endif
        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int textureV = this.isPositionDragging() ? SIZE * 2 : hovered ? SIZE : 0;
        //#if MC>=12108
        //$$ context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, this.getX(), this.getY(), 0, textureV,
        //$$         SIZE, SIZE, SIZE, SIZE * 3);
        //#elseif MC>=12103
        //$$ context.drawTexture(RenderLayer::getGuiTextured, TEXTURE, this.getX(), this.getY(), 0, textureV,
        //$$         SIZE, SIZE, SIZE, SIZE * 3);
        //#else
        context.drawTexture(TEXTURE, this.getX(), this.getY(), 0, textureV, SIZE, SIZE, SIZE, SIZE * 3);
        //#endif
    }
}
