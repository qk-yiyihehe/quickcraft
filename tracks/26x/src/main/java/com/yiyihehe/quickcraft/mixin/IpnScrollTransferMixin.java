package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.yiyihehe.quickcraft.QuickTransfer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.input.InventoryInputHandler", remap = false)
public abstract class IpnScrollTransferMixin {
    @Inject(method = "onInput", at = @At("HEAD"), remap = false)
    private void quickcraft$captureVerticalScroll(CallbackInfoReturnable<Boolean> cir,
                                                  @Local(argsOnly = true, ordinal = 0) int lastKey,
                                                  @Share("verticalScroll") LocalBooleanRef verticalScroll) {
        // IPN 会在方法体内复用 lastKey 的局部变量槽，需在入口识别滚轮虚拟键码。
        verticalScroll.set(lastKey == -10000 || lastKey == -10001);
    }

    @Inject(
            method = "onInput",
            at = @At(value = "INVOKE",
                    target = "Lorg/anti_ad/mc/ipnext/config/Hotkeys;getSCROLL_TO_CHEST()Lorg/anti_ad/mc/common/config/options/ConfigHotkey;"),
            cancellable = true,
            remap = false
    )
    private void quickcraft$deferScrollTransfer(CallbackInfoReturnable<Boolean> cir,
                                                @Share("verticalScroll") LocalBooleanRef verticalScroll) {
        if (verticalScroll.get() && QuickTransfer.shouldHandleScrollTransfer()) {
            cir.setReturnValue(false);
        }
    }
}
