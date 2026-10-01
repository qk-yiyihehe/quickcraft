package com.yiyihehe.quickcraft.litematica;

import net.minecraft.entity.player.PlayerInventory;

final class QuickLitematicaMaterialAccess {
    private QuickLitematicaMaterialAccess() {
    }

    static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.selectedSlot;
    }

    static void registerSpecialRenderer() {
    }
}
