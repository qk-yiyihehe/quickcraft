package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.village.TradedItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 只筛选交易补料的来源槽位，保留原版切换交易、归还付款和填充流程。 */
@Mixin(MerchantScreenHandler.class)
public abstract class QuickContainerLockMerchantScreenHandlerMixin {
    @WrapOperation(method = "autofill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;"))
    private ItemStack quickcraft$readUnlockedPaymentSource(Slot slot, Operation<ItemStack> original,
                                                          int paymentSlot, TradedItem cost) {
        if (slot instanceof TradeOutputSlot || QuickContainerLock.isLockedSlot((MerchantScreenHandler) (Object) this, slot)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = original.call(slot);
        // 旧实现的主付款项要求组件完全相同，副付款项继续使用 TradedItem 的匹配规则。
        if (paymentSlot == 0 && !ItemStack.areItemsAndComponentsEqual(stack, cost.itemStack())) {
            return ItemStack.EMPTY;
        }
        return stack;
    }
}
