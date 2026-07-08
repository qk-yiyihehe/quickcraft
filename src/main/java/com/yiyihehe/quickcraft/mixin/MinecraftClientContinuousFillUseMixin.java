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
 * 连续填充期间的前台屏幕和右键输入抑制。
 *
 * <p>后台填充需要短暂打开 {@link HandledScreen} 完成槽位同步，但不能让它覆盖玩家正在看的界面。
 * 同时，长按右键触发填充后，原版 {@code doItemUse} 可能在同一输入周期继续执行一次，
 * 导致重复开箱或误放置。两个注入点失效时，常见症状是界面闪开、右键动作重复触发。</p>
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
