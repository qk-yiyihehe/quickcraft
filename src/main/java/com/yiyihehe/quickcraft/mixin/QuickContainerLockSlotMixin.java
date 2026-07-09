package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在槽位最底层补一层锁判断，让取出和放入都过不了。
 */
@Mixin(Slot.class)
public abstract class QuickContainerLockSlotMixin {
    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedTake(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.isLockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canInsert", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedInsert(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.isLockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
