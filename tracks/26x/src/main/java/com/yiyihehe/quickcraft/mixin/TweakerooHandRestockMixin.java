package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.yiyihehe.quickcraft.portablestorage.QuickShulkerRestockFallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "fi.dy.masa.tweakeroo.util.InventoryUtils", remap = false)
public abstract class TweakerooHandRestockMixin {
    // 此处位于 Tweakeroo 自身的开关、阈值、过滤条件和光标检查之后。
    @Inject(method = "preRestockHand", at = @At(value = "CONSTANT", args = "intValue=9"))
    private static void quickcraft$beforePreRestock(
            Player player,
            InteractionHand hand,
            boolean allowHotbar,
            CallbackInfo ci,
            @Share("pre") LocalRef<ItemStack> before
    ) {
        if (QuickShulkerRestockFallback.enabled()) {
            before.set(player.getItemInHand(hand).copy());
        }
    }

    @Inject(method = "preRestockHand", at = @At("RETURN"))
    private static void quickcraft$afterPreRestock(
            Player player,
            InteractionHand hand,
            boolean allowHotbar,
            CallbackInfo ci,
            @Share("pre") LocalRef<ItemStack> before
    ) {
        if (before.get() != null) {
            QuickShulkerRestockFallback.afterTweakeroo(player, hand, before.get(), before.get());
        }
    }

    @Inject(method = "restockNewStackToHand", at = @At("HEAD"))
    private static void quickcraft$beforeRestock(
            Player player,
            InteractionHand hand,
            ItemStack reference,
            boolean allowHotbar,
            CallbackInfo ci,
            @Share("replacement") LocalRef<ItemStack> before
    ) {
        if (QuickShulkerRestockFallback.enabled()) {
            before.set(player.getItemInHand(hand).copy());
        }
    }

    @Inject(method = "restockNewStackToHand", at = @At("RETURN"))
    private static void quickcraft$afterRestock(
            Player player,
            InteractionHand hand,
            ItemStack reference,
            boolean allowHotbar,
            CallbackInfo ci,
            @Share("replacement") LocalRef<ItemStack> before
    ) {
        if (before.get() != null) {
            QuickShulkerRestockFallback.afterTweakeroo(player, hand, reference, before.get());
        }
    }
}
