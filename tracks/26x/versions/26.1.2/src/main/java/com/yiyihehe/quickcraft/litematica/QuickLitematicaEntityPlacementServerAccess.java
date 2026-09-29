package com.yiyihehe.quickcraft.litematica;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 26.1.2 的服务端掉落物品 API：第三参数为布尔值。 */
final class QuickLitematicaEntityPlacementServerAccess {
    private QuickLitematicaEntityPlacementServerAccess() {
    }

    static void drop(ServerPlayer player, ItemStack stack) {
        player.drop(stack, false, false);
    }
}
