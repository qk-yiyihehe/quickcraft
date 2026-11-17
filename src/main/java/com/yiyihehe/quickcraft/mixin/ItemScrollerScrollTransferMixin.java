package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "fi.dy.masa.itemscroller.event.InputHandler", remap = false)
public abstract class ItemScrollerScrollTransferMixin {
    @Inject(method = "handleInput(IZD)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void quickcraft$deferScrollTransfer(int button, boolean state, double wheelDelta,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (wheelDelta != 0 && QuickTransfer.shouldHandleScrollTransfer()) {
            cir.setReturnValue(false);
        }
    }
}
