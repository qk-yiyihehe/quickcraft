package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.mixin.LitematicaWorldUtilsInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;

/** 26.1.2 轻松放置与工具判定适配。 */
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
        QuickFreeCameraInteractions.callEasyPlaceAction(
                () -> LitematicaWorldUtilsInvoker.quickcraft$doEasyPlaceAction(client));
    }

    public static void swingHandIfSuccess(Minecraft client, InteractionHand hand, InteractionResult result) {
        if (client.player != null && result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            client.player.swing(hand);
        }
    }
}
