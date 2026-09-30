package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把村民交易收藏与顺序映射逻辑接到原版交易界面。
 * 1.21.10+ 使用 renderMain 与 mouseClicked(Click, boolean) 签名。
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$prepareTradeOrder(CallbackInfo ci) {
        QuickTrade.prepareTradeOrder((MerchantScreen) (Object) this);
    }

    @Inject(method = "renderMain", at = @At("TAIL"))
    private void quickcraft$renderFavoriteStar(DrawContext context,
                                               int mouseX,
                                               int mouseY,
                                               float delta,
                                               CallbackInfo ci) {
        QuickTrade.renderFavoriteStar((MerchantScreen) (Object) this, context);
    }

    @Inject(method = "renderMain", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideContinuousTradeScreen(DrawContext context,
                                                      int mouseX,
                                                      int mouseY,
                                                      float delta,
                                                      CallbackInfo ci) {
        // 持续交易保留界面实例处理槽位和网络同步，但不绘制原版交易窗口，避免自动扫描时闪屏。
        if (QuickTrade.shouldHideContinuousTradeScreen((MerchantScreen) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleTradeMouseClick(Click click, boolean doubled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (QuickTrade.handleMerchantMouseClicked((MerchantScreen) (Object) this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "syncRecipeIndex", at = @At("HEAD"), cancellable = true)
    private void quickcraft$syncMappedRecipeIndex(CallbackInfo ci) {
        QuickTrade.syncRecipeIndex((MerchantScreen) (Object) this);
        ci.cancel();
    }
}
