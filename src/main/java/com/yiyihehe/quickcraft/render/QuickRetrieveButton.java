package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Icon button for moving container items into the player inventory. */
public final class QuickRetrieveButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/quick_retrieve.png");

    //#if MC>=12111
    //$$ public QuickRetrieveButton(int x, int y, PressAction onPress, PositionKey positionKey,
    //$$                            net.minecraft.text.Text tooltip) {
    //#else
    public QuickRetrieveButton(int x, int y, PressAction onPress, PositionKey positionKey, Text tooltip) {
    //#endif
        super(x, y, tooltip, onPress, positionKey);
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        //#if MC>=12108
        //$$ context.drawTexture(RenderPipelines.GUI_TEXTURED, ICON, x, y, 0, 0, 10, 10, 10, 10);
        //#elseif MC>=12103
        //$$ context.drawTexture(RenderLayer::getGuiTextured, ICON, x, y, 0, 0, 10, 10, 10, 10);
        //#else
        context.drawTexture(ICON, x, y, 0, 0, 10, 10, 10, 10);
        //#endif
    }
}
