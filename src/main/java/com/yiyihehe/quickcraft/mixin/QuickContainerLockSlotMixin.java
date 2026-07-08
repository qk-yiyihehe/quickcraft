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
 * 槽位自身权限检查里的锁格兜底。
 *
 * <p>部分操作不会经过客户端点击入口或 {@code insertItem} 的重定向分支，最终仍会询问
 * {@link Slot#canTakeItems(PlayerEntity)} / {@link Slot#canInsert(ItemStack)}。
 * 这里作为底层保护，防止锁格被拖拽、快捷移动或其他模组操作绕过。</p>
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
