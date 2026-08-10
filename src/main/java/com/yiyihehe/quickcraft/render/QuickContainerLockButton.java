package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/** Icon-only button for locking or unlocking the entire current container. */
public final class QuickContainerLockButton extends QuickCompactIconButton {
    private static final Identifier LOCK_ICON = Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(int x, int y, OnPress onPress, BooleanSupplier locked,
                                    PositionKey positionKey, Component tooltip) {
        super(x, y, tooltip, onPress, positionKey);
        this.locked = locked;
    }

    @Override
    protected int getSurfaceColor(boolean hovered) {
        if (this.locked.getAsBoolean()) {
            return hovered ? 0xE0786740 : 0xC05C4C30;
        }
        return super.getSurfaceColor(hovered);
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor context, int x, int y, boolean hovered) {
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        context.blit(RenderPipelines.GUI_TEXTURED, icon,
                x, y, 0.0F, 0.0F, 10, 10, 10, 10);
    }
}
