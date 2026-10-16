package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.MinecraftClient;

/** 1.21–1.21.8 的 malilib 使用 GUI 整数坐标。 */
public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public void onMouseMove(int mouseX, int mouseY) {
        QuickContainerLock.handleSlotLockDrag(MinecraftClient.getInstance(), mouseX, mouseY);
    }
}
