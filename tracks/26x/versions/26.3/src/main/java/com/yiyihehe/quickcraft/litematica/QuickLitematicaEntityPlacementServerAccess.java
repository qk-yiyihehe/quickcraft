package com.yiyihehe.quickcraft.litematica;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/** 26.3 的服务端掉落物品 API：第三参数为 Prediction。 */
final class QuickLitematicaEntityPlacementServerAccess {
    private QuickLitematicaEntityPlacementServerAccess() {
    }

    static void drop(ServerPlayer player, ItemStack stack) {
        player.drop(stack, false, Prediction.SERVER_ONLY);
    }
}
