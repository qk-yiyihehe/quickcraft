package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 连续填充期间压住前台容器界面和同一次长按触发的原版右键 use。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientContinuousFillUseMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressBackgroundHandledScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?> && QuickContainerCopy.shouldSuppressBackgroundHandledScreenOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousFillUse(CallbackInfo ci) {
        if (QuickContainerCopy.shouldSuppressContinuousFillUseInput()) {
            ci.cancel();
        }
    }
}
