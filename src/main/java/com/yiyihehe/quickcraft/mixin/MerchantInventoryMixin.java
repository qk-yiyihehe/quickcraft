package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.item.ItemStack;
import net.minecraft.village.MerchantInventory;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 收藏交易置顶后，显示索引 0 不能继续沿用原版“自动匹配任意交易”的特殊语义。
 * 只重定向 MerchantInventory.updateOffers 的配方查找，否则成交后的剩余材料会切到其他交易。
 */
@Mixin(MerchantInventory.class)
public abstract class MerchantInventoryMixin {
    @WrapOperation(
            method = "updateOffers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/village/TradeOfferList;getValidOffer(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/village/TradeOffer;"
            )
    )
    private TradeOffer quickcraft$keepPinnedTradeSelected(TradeOfferList offers,
                                                           ItemStack firstBuyItem,
                                                           ItemStack secondBuyItem,
                                                           int tradeIndex, Operation<TradeOffer> original) {
        return QuickTrade.getValidOfferForCurrentOrder(offers, firstBuyItem, secondBuyItem, tradeIndex,
                () -> original.call(offers, firstBuyItem, secondBuyItem, tradeIndex));
    }
}
