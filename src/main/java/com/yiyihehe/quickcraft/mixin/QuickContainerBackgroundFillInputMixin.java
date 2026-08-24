package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.1+ 的容器输入事件由 AbstractContainerScreen 覆写，后台装盒期间在该层统一拦截。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickContainerBackgroundFillInputMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillClick(MouseButtonEvent event,
                                                      boolean doubleClick,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillRelease(MouseButtonEvent event,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillDrag(MouseButtonEvent event,
                                                     double deltaX,
                                                     double deltaY,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillKey(KeyEvent event,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            return;
        }
        if (event.isEscape()) {
            QuickCraftWorkbenchShulker.handleEscape(Minecraft.getInstance());
        }
        cir.setReturnValue(true);
    }
}
