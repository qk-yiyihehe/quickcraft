package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.yiyihehe.quickcraft.QuickTransfer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.input.InventoryInputHandler", remap = false)
public abstract class IpnScrollTransferMixin {
    @Inject(
            method = "onInput",
            at = @At(value = "INVOKE",
                    target = "Lorg/anti_ad/mc/ipnext/config/Hotkeys;getSCROLL_TO_CHEST()Lorg/anti_ad/mc/common/config/options/ConfigHotkey;"),
            cancellable = true,
            remap = false
    )
    private void quickcraft$deferScrollTransfer(CallbackInfoReturnable<Boolean> cir,
                                                @Local(argsOnly = true, ordinal = 0) int lastKey) {
        // libIPN 的竖向滚轮虚拟键码；只跳过滚轮转移，保留键盘快捷键和此前的一键整理分支。
        if ((lastKey == -10000 || lastKey == -10001) && QuickTransfer.shouldHandleScrollTransfer()) {
            cir.setReturnValue(false);
        }
    }
}
