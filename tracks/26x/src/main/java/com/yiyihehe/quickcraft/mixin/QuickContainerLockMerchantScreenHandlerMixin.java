package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.ItemCost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 只筛选交易补料的来源槽位，保留原版切换交易、归还付款和填充流程。 */
@Mixin(MerchantMenu.class)
public abstract class QuickContainerLockMerchantScreenHandlerMixin {
    @WrapOperation(method = "moveFromInventoryToPaymentSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack quickcraft$readUnlockedPaymentSource(Slot slot, Operation<ItemStack> original,
                                                          int paymentSlot, ItemCost cost) {
        if (slot instanceof MerchantResultSlot || QuickContainerLock.isLockedSlot((MerchantMenu) (Object) this, slot)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = original.call(slot);
        return stack;
    }
}
