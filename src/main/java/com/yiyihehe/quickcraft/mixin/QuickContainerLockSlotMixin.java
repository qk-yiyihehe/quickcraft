package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class QuickContainerLockSlotMixin {
    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedTake(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.shouldBlockSlotTake((Slot) (Object) this, player)) {
            cir.setReturnValue(false);
        }
    }
}
