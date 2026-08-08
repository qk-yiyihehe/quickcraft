package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 MC 1.21 的 ParentElement.mouseScrolled 默认实现入口处理容器槽位滚轮转移。
 * HandledScreen 经由该接口继承此入口；成功处理时必须取消原事件，避免子控件继续消费同一次滚动。
 */
@Mixin(ParentElement.class)
public interface QuickTransferScreenMixin {
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
