package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 村民交易收藏和排序的界面注入层。
 *
 * <p>QuickTrade 维护的是“显示顺序”和“原版配方索引”的映射，因此需要在界面初始化、
 * 星标渲染、鼠标点击和 {@link MerchantScreen#syncRecipeIndex()} 四个点同步状态。
 * 注入点失效时，常见症状是收藏星标位置错误、点击到错误交易，或服务端收到未映射的配方索引。</p>
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$prepareTradeOrder(CallbackInfo ci) {
        QuickTrade.prepareTradeOrder((MerchantScreen) (Object) this);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$renderFavoriteStar(DrawContext context,
                                               int mouseX,
                                               int mouseY,
                                               float delta,
                                               CallbackInfo ci) {
        QuickTrade.renderFavoriteStar((MerchantScreen) (Object) this, context);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleTradeMouseClick(double mouseX,
                                                  double mouseY,
                                                  int button,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (QuickTrade.handleMerchantMouseClicked((MerchantScreen) (Object) this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "syncRecipeIndex", at = @At("HEAD"), cancellable = true)
    private void quickcraft$syncMappedRecipeIndex(CallbackInfo ci) {
        QuickTrade.syncRecipeIndex((MerchantScreen) (Object) this);
    }
}
