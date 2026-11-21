package com.yiyihehe.quickcraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaSelectionDrag;
import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;

public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public boolean onMouseClick(MouseButtonEvent click, boolean pressed) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            QuickLitematicaSelectionDrag.stop();
        }
        return QuickInventoryShulkerTools.handleMouseClick(click.x(), click.y(), click.button(), pressed);
    }

    @Override
    public void onMouseMove(double mouseX, double mouseY) {
        Minecraft client = Minecraft.getInstance();
        QuickContainerLock.handleSlotLockDrag(client, mouseX, mouseY);
        QuickLitematicaSelectionDrag.move(QuickClientScreenAccess.currentScreen(client), mouseX, mouseY);
    }
}
