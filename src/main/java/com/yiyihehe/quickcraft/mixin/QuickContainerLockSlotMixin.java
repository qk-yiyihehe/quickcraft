package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在槽位最底层补一层锁判断，让取出和放入都过不了。
 */
@Mixin(Slot.class)
public abstract class QuickContainerLockSlotMixin {
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.isLockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedInsert(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.isLockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
