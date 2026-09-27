package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.2+ 的 HandledScreen 会直接处理物品槽位滚轮动作，因此在其 mouseScrolled 入口接管滚轮转移。
 * 成功处理时必须取消原事件，避免原版槽位动作继续消费同一次滚动。
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
