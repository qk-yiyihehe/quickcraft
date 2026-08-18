package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把村民交易收藏与顺序映射逻辑接到原版交易界面。
 * 这里负责初始化排序、绘制收藏星标，并拦截点击与配方同步。
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$prepareTradeOrder(CallbackInfo ci) {
        QuickTrade.prepareTradeOrder((MerchantScreen) (Object) this);
    }

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void quickcraft$renderFavoriteStar(GuiGraphicsExtractor context,
                                               int mouseX,
                                               int mouseY,
                                               float delta,
                                               CallbackInfo ci) {
        QuickTrade.renderFavoriteStar((MerchantScreen) (Object) this, context);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideContinuousTradeScreen(GuiGraphicsExtractor context,
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
    private void quickcraft$handleTradeMouseClick(MouseButtonEvent click,
                                                  boolean doubled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (QuickTrade.handleMerchantMouseClicked(
                (MerchantScreen) (Object) this,
                click.x(),
                click.y(),
                click.button()
        )) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "postButtonClick", at = @At("HEAD"), cancellable = true)
    private void quickcraft$syncMappedRecipeIndex(CallbackInfo ci) {
        QuickTrade.syncRecipeIndex((MerchantScreen) (Object) this);
    }
}
