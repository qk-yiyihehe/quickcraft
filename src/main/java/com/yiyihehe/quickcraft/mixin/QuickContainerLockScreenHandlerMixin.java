package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在原版 insertItem 分发链里跳过锁格。
 * 这里只改原版取槽位堆叠的两个点，尽量保留其他模组对原方法的注入空间。
 */
@Mixin(ScreenHandler.class)
public abstract class QuickContainerLockScreenHandlerMixin {
    @Unique
    private static final ItemStack QUICKCRAFT_LOCKED_SLOT_PLACEHOLDER = new ItemStack(Items.BARRIER);

    @WrapOperation(
            method = "insertItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 0
            )
    )
    private ItemStack quickcraft$skipLockedSlotMerge(Slot slot, Operation<ItemStack> original) {
        if (QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)) {
            return ItemStack.EMPTY;
        }

        return original.call(slot);
    }

    @WrapOperation(
            method = "insertItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 1
            )
    )
    private ItemStack quickcraft$skipLockedSlotFill(Slot slot, Operation<ItemStack> original) {
        if (QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)) {
            return QUICKCRAFT_LOCKED_SLOT_PLACEHOLDER;
        }

        return original.call(slot);
    }
}
