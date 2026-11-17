package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;

public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public boolean onMouseClick(MouseButtonEvent click, boolean pressed) {
        return QuickInventoryShulkerTools.handleMouseClick(click.x(), click.y(), click.button(), pressed);
    }

    @Override
    public void onMouseMove(double mouseX, double mouseY) {
        QuickContainerLock.handleSlotLockDrag(Minecraft.getInstance(), mouseX, mouseY);
    }
}
