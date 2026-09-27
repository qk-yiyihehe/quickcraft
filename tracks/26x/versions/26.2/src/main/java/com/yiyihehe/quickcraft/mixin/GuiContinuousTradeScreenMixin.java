package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 由 Gui.setScreen 管理当前界面；持续交易时压住前台交易屏幕，保留后台菜单同步。
 */
@Mixin(Gui.class)
public abstract class GuiContinuousTradeScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousTradeScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof MerchantScreen && QuickTrade.shouldSuppressContinuousTradeScreenOpen()) {
            ci.cancel();
        }
    }
}
