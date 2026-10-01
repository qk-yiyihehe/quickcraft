package com.yiyihehe.quickcraft.litematica;

import net.minecraft.entity.player.PlayerInventory;

final class QuickLitematicaMaterialAccess {
    private QuickLitematicaMaterialAccess() {
    }

    static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    static void registerSpecialRenderer() {
        QuickLitematicaPreview3D.registerSpecialRenderer();
    }
}
