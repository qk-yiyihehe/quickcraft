package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaEasyPlaceUtilsInvoker;
import com.yiyihehe.quickcraft.mixin.LitematicaWorldUtilsInvoker;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;

/** 26.2 轻松放置与工具判定适配（Litematica 重写路径）。 */
public final class QuickLitematicaEasyPlaceAccess {
    private QuickLitematicaEasyPlaceAccess() {
    }

    static boolean isAxe(ItemStack stack) {
        return stack.getItem() instanceof AxeItem;
    }

    static boolean isShovel(ItemStack stack) {
        return stack.getItem() instanceof ShovelItem;
    }

    static boolean isHoe(ItemStack stack) {
        return stack.getItem() instanceof HoeItem;
    }

    public static void triggerHoldEasyPlace(Minecraft client) {
        if (Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue()) {
            if (EasyPlaceUtils.isHandling()) {
                return;
            }

            EasyPlaceUtils.setHandling(true);
            try {
                LitematicaEasyPlaceUtilsInvoker.quickcraft$handleEasyPlace();
            } finally {
                EasyPlaceUtils.setHandling(false);
            }
        } else {
            LitematicaWorldUtilsInvoker.quickcraft$doEasyPlaceAction(client);
        }
    }

    public static void swingHandIfSuccess(Minecraft client, InteractionHand hand, InteractionResult result) {
        if (client.player != null && result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            client.player.swing(hand);
        }
    }
}
