package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.MinecraftClient;

/** 玩家锁格只在客户端读取，点击整理与准备空位时重新检查。 */
public final class QuickCrossBoxSortProtection {
    private QuickCrossBoxSortProtection() {}

    public static int menuSlot(net.minecraft.screen.ScreenHandler menu, int inventoryIndex) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return -1;
        }
        for (var slot : menu.slots) {
            if (slot.inventory == player.getInventory() && slot.getIndex() == inventoryIndex) {
                return slot.id;
            }
        }
        return -1;
    }

    public static boolean isLocked(int inventorySlot) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return true;
        }
        for (var slot : player.playerScreenHandler.slots) {
            if (slot.inventory == player.getInventory() && slot.getIndex() == inventorySlot) {
                return QuickContainerLock.isLockedSlot(player.playerScreenHandler, slot);
            }
        }
        return true;
    }
}
