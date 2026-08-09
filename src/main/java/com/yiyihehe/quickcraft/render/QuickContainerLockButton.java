package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.BooleanSupplier;

/** Icon-only button for locking or unlocking the entire current container. */
public final class QuickContainerLockButton extends QuickDraggableButton {
    private static final Identifier LOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.of("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(int x, int y, PressAction onPress, BooleanSupplier locked,
                                    PositionKey positionKey, Text tooltip) {
        super(x, y, 12, 12, Text.empty(), onPress, positionKey);
        this.locked = locked;
        this.setTooltip(Tooltip.of(tooltip));
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderWidget(context, mouseX, mouseY, delta);
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        context.drawTexture(RenderLayer::getGuiTextured, icon,
                this.getX() + 1, this.getY() + 1, 0, 0, 10, 10, 10, 10);
    }
}
