package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaSelectionDrag;
import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.MinecraftClient;

/** 1.21.9 起 malilib 的鼠标移动回调改用 double 坐标。 */
public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public boolean onMouseClick(Click click, boolean pressed) {
        if (click.button() == 0) {
            QuickLitematicaSelectionDrag.stop();
        }
        return QuickInventoryShulkerTools.handleMouseClick(click.x(), click.y(), click.button(), pressed);
    }

    @Override
    public void onMouseMove(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        QuickContainerLock.handleSlotLockDrag(client, mouseX, mouseY);
        QuickLitematicaSelectionDrag.move(client.currentScreen, mouseX, mouseY);
    }
}
