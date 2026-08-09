package com.yiyihehe.quickcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/** Icon-only button for locking or unlocking the entire current container. */
public final class QuickContainerLockButton extends QuickDraggableButton {
    private static final Identifier LOCK_ICON = Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/container_lock.png");
    private static final Identifier UNLOCK_ICON = Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/container_unlock.png");
    private final BooleanSupplier locked;

    public QuickContainerLockButton(int x, int y, OnPress onPress, BooleanSupplier locked,
                                    PositionKey positionKey, Component tooltip) {
        super(x, y, 12, 12, Component.empty(), onPress, positionKey);
        this.locked = locked;
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.extractDefaultSprite(context);
        Identifier icon = this.locked.getAsBoolean() ? LOCK_ICON : UNLOCK_ICON;
        context.blit(RenderPipelines.GUI_TEXTURED, icon,
                this.getX() + 1, this.getY() + 1, 0.0F, 0.0F, 10, 10, 10, 10);
    }
}
