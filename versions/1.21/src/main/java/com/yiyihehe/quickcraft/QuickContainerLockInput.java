package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaSelectionDrag;
import fi.dy.masa.malilib.hotkeys.IMouseInputHandler;
import net.minecraft.client.MinecraftClient;

/** 1.21–1.21.8 的 malilib 使用 GUI 整数坐标。 */
public final class QuickContainerLockInput implements IMouseInputHandler {
    @Override
    public boolean onMouseClick(int mouseX, int mouseY, int button, boolean pressed) {
        if (button == 0) {
            QuickLitematicaSelectionDrag.stop();
        }
        return QuickInventoryShulkerTools.handleMouseClick(mouseX, mouseY, button, pressed);
    }

    @Override
    public void onMouseMove(int mouseX, int mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        QuickContainerLock.handleSlotLockDrag(client, mouseX, mouseY);
        QuickLitematicaSelectionDrag.move(client.currentScreen, mouseX, mouseY);
    }
}
