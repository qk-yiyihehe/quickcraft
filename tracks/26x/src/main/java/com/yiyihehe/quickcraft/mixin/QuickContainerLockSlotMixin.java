package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class QuickContainerLockSlotMixin {
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (QuickContainerLock.shouldBlockSlotTake((Slot) (Object) this, player)) {
            cir.setReturnValue(false);
        }
    }
}
