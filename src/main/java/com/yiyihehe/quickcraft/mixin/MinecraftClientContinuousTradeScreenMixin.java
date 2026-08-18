package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 持续交易期间拦截前台 MerchantScreen，保留服务端同步下发的 MerchantScreenHandler。
 * 这样依赖当前 Screen 的 REI/JEI 等叠加层不会获得渲染入口，后台交易仍可操作槽位。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientContinuousTradeScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousTradeScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof MerchantScreen && QuickTrade.shouldSuppressContinuousTradeScreenOpen()) {
            ci.cancel();
        }
    }
}
