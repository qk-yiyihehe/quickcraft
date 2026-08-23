package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

/**
 * 后台填充期间隐藏真实打开的容器/潜影盒界面，只保留服务端槽位操作。
 */
@Mixin(Screen.class)
public abstract class QuickContainerBackgroundFillScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideBackgroundFillScreen(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof AbstractContainerScreen<?>
                && QuickContainerCopy.shouldHideBackgroundHandledScreen()) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillClick(double mouseX,
                                                      double mouseY,
                                                      int button,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillRelease(double mouseX,
                                                        double mouseY,
                                                        int button,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillDrag(double mouseX,
                                                     double mouseY,
                                                     int button,
                                                     double deltaX,
                                                     double deltaY,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockWorkbenchRefillKey(int keyCode,
                                                    int scanCode,
                                                    int modifiers,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            QuickCraftWorkbenchShulker.handleEscape(Minecraft.getInstance());
        }
        cir.setReturnValue(true);
    }
}
