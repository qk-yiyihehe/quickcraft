package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** Icon-only button for locking or unlocking the entire current container. */
public final class QuickContainerLockButton extends QuickCompactIconButton {
    private static final Identifier LOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(int x, int y, PressAction onPress, BooleanSupplier locked,
                                    //#if MC>=12111
                                    //$$ PositionKey positionKey, net.minecraft.text.Text tooltip) {
                                    //#else
                                    PositionKey positionKey, Text tooltip) {
                                    //#endif
        super(x, y, tooltip, onPress, positionKey);
        this.locked = locked;
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        //#if MC>=12110
        //$$ context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F, 10, 10, 10, 10, 10, 10);
        //#elseif MC>=12108
        //$$ context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x, y, 0, 0, 10, 10, 10, 10);
        //#elseif MC>=12103
        //$$ context.drawTexture(RenderLayer::getGuiTextured, icon, x, y, 0, 0, 10, 10, 10, 10);
        //#else
        context.drawTexture(icon, x, y, 0, 0, 10, 10, 10, 10);
        //#endif
    }
}
