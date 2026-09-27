package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Compact icon button used on the title bars of vanilla container screens. */
public abstract class QuickCompactIconButton extends QuickDraggableButton {
    public static final int SIZE = 14;
    private static final Identifier BACKGROUND_TEXTURE =
            Identifier.of("quickcraft", "textures/gui/compact_button.png");

    //#if MC>=12111
    //$$ protected QuickCompactIconButton(int x, int y, net.minecraft.text.Text label,
    //$$                                  PressAction onPress, PositionKey positionKey) {
    //#else
    protected QuickCompactIconButton(int x, int y, Text label, PressAction onPress, PositionKey positionKey) {
    //#endif
        super(x, y, SIZE, SIZE, label, onPress, positionKey);
        this.setTooltip(Tooltip.of(label));
    }

    @Override
    //#if MC>=12111
    //$$ protected final void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
    //#else
    protected final void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
    //#endif
        boolean hovered = this.active && this.isMouseOver(mouseX, mouseY);
        boolean selected = this.isToggleSelected();
        int textureV = this.isPositionDragging() || (hovered && selected)
                ? SIZE * 2 : hovered || selected ? SIZE : 0;
        context.drawTexture(
                //#if MC>=12108
                //$$ RenderPipelines.GUI_TEXTURED,
                //#elseif MC>=12103
                //$$ RenderLayer::getGuiTextured,
                //#endif
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
