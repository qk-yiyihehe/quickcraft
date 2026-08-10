package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** Icon-only button for locking or unlocking the entire current container. */
public final class QuickContainerLockButton extends QuickCompactIconButton {
    private static final Identifier LOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(int x, int y, PressAction onPress, BooleanSupplier locked,
                                    PositionKey positionKey, Text tooltip) {
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
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        context.drawTexture(icon, x, y, 0, 0, 10, 10, 10, 10);
    }
}
