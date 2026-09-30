package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.3+ HandledScreen 直接重写 mouseScrolled 入口处理容器槽位滚轮转移。
 */
@Mixin(HandledScreen.class)
public abstract class QuickTransferScreenMixin {
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleScrollTransfer(double mouseX,
                                                 double mouseY,
                                                 double horizontalAmount,
                                                 double verticalAmount,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof HandledScreen<?> screen
                && QuickTransfer.handleScrollTransfer(screen, mouseX, mouseY, verticalAmount)) {
            cir.setReturnValue(true);
        }
    }
}
