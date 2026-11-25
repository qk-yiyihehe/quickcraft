package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;

/** 玩家锁格只在客户端读取，点击整理与准备空位时重新检查。 */
public final class QuickCrossBoxSortProtection {
    private QuickCrossBoxSortProtection() {}

    public static int menuSlot(net.minecraft.world.inventory.AbstractContainerMenu menu, int inventoryIndex) {
        var player = Minecraft.getInstance().player;
        return player == null ? -1 : menu.findSlot(player.getInventory(), inventoryIndex).orElse(-1);
    }

    public static boolean isLocked(int inventorySlot) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }
        for (var slot : player.inventoryMenu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return QuickContainerLock.isLockedSlot(player.inventoryMenu, slot);
            }
        }
        return true;
    }
}
