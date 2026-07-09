package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 连续填充期间压住前台容器界面和同一次长按触发的原版右键 use。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientContinuousFillUseMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressBackgroundHandledScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof HandledScreen<?> && QuickContainerCopy.shouldSuppressBackgroundHandledScreenOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousFillUse(CallbackInfo ci) {
        if (QuickContainerCopy.shouldSuppressContinuousFillUseInput()) {
            ci.cancel();
        }
    }
}
