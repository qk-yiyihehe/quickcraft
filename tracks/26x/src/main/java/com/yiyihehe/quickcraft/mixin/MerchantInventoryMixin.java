package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickTrade;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 收藏交易置顶后，显示索引 0 不能继续沿用原版“自动匹配任意交易”的特殊语义。
 * 只重定向 MerchantContainer.updateSellItem 的配方查找，否则成交后的剩余材料会切到其他交易。
 */
@Mixin(MerchantContainer.class)
public abstract class MerchantInventoryMixin {
    @WrapOperation(
            method = "updateSellItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/trading/MerchantOffers;getRecipeFor(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/trading/MerchantOffer;"
            )
    )
    private MerchantOffer quickcraft$keepPinnedTradeSelected(MerchantOffers offers,
                                                              ItemStack firstBuyItem,
                                                              ItemStack secondBuyItem,
                                                              int tradeIndex, Operation<MerchantOffer> original) {
        return QuickTrade.getValidOfferForCurrentOrder(offers, firstBuyItem, secondBuyItem, tradeIndex,
                () -> original.call(offers, firstBuyItem, secondBuyItem, tradeIndex));
    }
}
