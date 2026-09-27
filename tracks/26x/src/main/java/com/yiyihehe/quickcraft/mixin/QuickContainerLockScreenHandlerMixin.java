package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 在原版 moveItemStackTo 分发链里跳过锁格。
 * 这里只改原版取槽位堆叠的两个点，尽量保留其他模组对原方法的注入空间。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class QuickContainerLockScreenHandlerMixin {
    @Redirect(
            method = "moveItemStackTo",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 0
            )
    )
    private ItemStack quickcraft$skipLockedSlotMerge(Slot slot) {
        if (QuickContainerLock.isLockedSlot((AbstractContainerMenu) (Object) this, slot)) {
            return ItemStack.EMPTY;
        }

        return slot.getItem();
    }

    @Redirect(
            method = "moveItemStackTo",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 1
            )
    )
    private ItemStack quickcraft$skipLockedSlotFill(Slot slot) {
        if (QuickContainerLock.isLockedSlot((AbstractContainerMenu) (Object) this, slot)) {
            return Items.BARRIER.getDefaultInstance();
        }

        return slot.getItem();
    }
}
