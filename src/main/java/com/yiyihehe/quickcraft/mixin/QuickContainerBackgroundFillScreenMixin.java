package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.MinecraftClient;
//#if MC>=12110
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

/**
 * 后台填充期间隐藏真实打开的容器/潜影盒界面，只保留服务端槽位操作。
 */
@Mixin(HandledScreen.class)
public abstract class QuickContainerBackgroundFillScreenMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideBackgroundFillScreen(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$blockWorkbenchRefillClick(Click click, boolean doubled,
    //#else
    private void quickcraft$blockWorkbenchRefillClick(double mouseX,
                                                      double mouseY,
                                                      int button,
    //#endif
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$blockWorkbenchRefillRelease(Click click,
    //#else
    private void quickcraft$blockWorkbenchRefillRelease(double mouseX,
                                                        double mouseY,
                                                        int button,
    //#endif
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$blockWorkbenchRefillDrag(Click click,
    //#else
    private void quickcraft$blockWorkbenchRefillDrag(double mouseX,
                                                     double mouseY,
                                                     int button,
    //#endif
                                                     double deltaX,
                                                     double deltaY,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$blockWorkbenchRefillKey(KeyInput input,
    //#else
    private void quickcraft$blockWorkbenchRefillKey(int keyCode,
                                                    int scanCode,
                                                    int modifiers,
    //#endif
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()) {
            return;
        }
        //#if MC>=12110
        //$$ int keyCode = input.key();
        //#endif
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            QuickCraftWorkbenchShulker.handleEscape(MinecraftClient.getInstance());
        }
        cir.setReturnValue(true);
    }
}
