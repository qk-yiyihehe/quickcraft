package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 Slot.canTakeItems 阻止从锁格取出物品。
 * 仅在“允许手动操作锁格”关闭时拦截；QuickTransfer 和 ScreenHandler 的客户端路径始终跳过锁格，
 * 若该注入点因 1.21 API 变化失效，保护模式下锁格会重新允许鼠标取出。
 */
@Mixin(Slot.class)
public abstract class QuickContainerLockSlotMixin {
    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedTake(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (!QuickCraftConfigs.areManualLockedSlotInteractionsAllowed()
                && QuickContainerLock.isLockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

}
