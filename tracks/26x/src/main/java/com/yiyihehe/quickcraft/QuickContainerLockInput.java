package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.Minecraft;

public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public void onMouseMove(double mouseX, double mouseY) {
        QuickContainerLock.handleSlotLockDrag(Minecraft.getInstance(), mouseX, mouseY);
    }
}
