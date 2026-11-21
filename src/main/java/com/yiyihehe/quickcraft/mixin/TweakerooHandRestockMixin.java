package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.yiyihehe.quickcraft.portablestorage.QuickShulkerRestockFallback;
import net.minecraft.util.Hand;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "fi.dy.masa.tweakeroo.util.InventoryUtils", remap = false)
public abstract class TweakerooHandRestockMixin {
    // This point follows Tweakeroo's own toggle, threshold, filter and cursor checks.
    @Inject(method = "preRestockHand", at = @At(value = "CONSTANT", args = "intValue=9"))
    private static void quickcraft$beforePreRestock(PlayerEntity player, Hand hand, boolean allowHotbar,
                                                    CallbackInfo ci, @Share("pre") LocalRef<ItemStack> before) {
        if (QuickShulkerRestockFallback.enabled()) {
            before.set(player.getStackInHand(hand).copy());
        }
    }

    @Inject(method = "preRestockHand", at = @At("RETURN"))
    private static void quickcraft$afterPreRestock(PlayerEntity player, Hand hand, boolean allowHotbar,
                                                   CallbackInfo ci, @Share("pre") LocalRef<ItemStack> before) {
        if (before.get() != null) {
            QuickShulkerRestockFallback.afterTweakeroo(player, hand, before.get(), before.get());
        }
    }

    @Inject(method = "restockNewStackToHand", at = @At("HEAD"))
    private static void quickcraft$beforeRestock(PlayerEntity player, Hand hand, ItemStack reference,
                                                boolean allowHotbar, CallbackInfo ci,
                                                @Share("replacement") LocalRef<ItemStack> before) {
        if (QuickShulkerRestockFallback.enabled()) {
            before.set(player.getStackInHand(hand).copy());
        }
    }

    @Inject(method = "restockNewStackToHand", at = @At("RETURN"))
    private static void quickcraft$afterRestock(PlayerEntity player, Hand hand, ItemStack reference,
                                               boolean allowHotbar, CallbackInfo ci,
                                               @Share("replacement") LocalRef<ItemStack> before) {
        if (before.get() != null) {
            QuickShulkerRestockFallback.afterTweakeroo(player, hand, reference, before.get());
        }
    }
}
