package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

/** 26.1.2 客户端实体放置界面与实体类型判定适配。 */
final class QuickLitematicaEntityPlacementAccess {
    private QuickLitematicaEntityPlacementAccess() {
    }

    static void showOverlay(Minecraft client, Component message) {
        client.gui.setOverlayMessage(message, false);
    }

    static boolean isItemEntityType(EntityType<?> type) {
        return type == EntityType.ITEM;
    }
}
