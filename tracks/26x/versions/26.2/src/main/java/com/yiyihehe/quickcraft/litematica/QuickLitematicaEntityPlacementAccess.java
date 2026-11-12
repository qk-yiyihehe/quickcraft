package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/** 26.2+ 客户端实体放置界面与实体类型判定适配。 */
final class QuickLitematicaEntityPlacementAccess {
    private QuickLitematicaEntityPlacementAccess() {
    }

    /** 该目标实际按 equipment 复合标签加载装备；低版本仍由 ArmorItems/HandItems 加载。 */
    public static boolean usesEntityEquipment() {
        return true;
    }

    static void showOverlay(Minecraft client, Component message) {
        client.gui.hud.setOverlayMessage(message, false);
    }

    static boolean isItemEntityType(EntityType<?> type) {
        return type == EntityTypes.ITEM;
    }
}
