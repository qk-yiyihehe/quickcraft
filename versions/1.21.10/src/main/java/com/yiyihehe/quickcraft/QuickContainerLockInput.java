package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.MinecraftClient;

/** 1.21.9 起 malilib 的鼠标移动回调改用 double 坐标。 */
public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public void onMouseMove(double mouseX, double mouseY) {
        QuickContainerLock.handleSlotLockDrag(MinecraftClient.getInstance(), mouseX, mouseY);
    }
}
