package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** 用纯图标锁定或解锁当前整个容器的按钮。 */
public final class QuickContainerLockButton extends QuickCompactIconButton {
    private static final Identifier LOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(
            int x,
            int y,
            PressAction onPress,
            BooleanSupplier locked,
            PositionKey positionKey,
            net.minecraft.text.Text tooltip
    ) {
        super(x, y, tooltip, onPress, positionKey);
        this.locked = locked;
    }

    @Override
    protected void drawIcon(DrawContext context, int x, int y, boolean hovered) {
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        QuickButtonAccess.drawButtonIcon(context, icon, x, y, 10, 10);
    }
}
