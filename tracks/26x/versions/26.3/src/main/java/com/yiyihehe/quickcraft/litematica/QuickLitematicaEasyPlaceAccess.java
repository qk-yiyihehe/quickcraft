package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaEasyPlaceUtilsInvoker;
import com.yiyihehe.quickcraft.mixin.LitematicaWorldUtilsInvoker;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

/** 26.3 轻松放置与工具判定适配（ItemTags 与 Predicted 摆动）。 */
public final class QuickLitematicaEasyPlaceAccess {
    private QuickLitematicaEasyPlaceAccess() {
    }

    static boolean isAxe(ItemStack stack) {
        return stack.is(ItemTags.AXES);
    }

    static boolean isShovel(ItemStack stack) {
        return stack.is(ItemTags.SHOVELS);
    }

    static boolean isHoe(ItemStack stack) {
        return stack.is(ItemTags.HOES);
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
                && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            client.player.swing(hand, SwingAnimation.DEFAULT, false);
        }
    }
}
