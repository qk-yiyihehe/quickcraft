package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端发出 clickSlot 前补一层格子锁判断，
 * 同时为自动穿脱鞘翅维护一次点击上下文。
 */
@Mixin(ClientPlayerInteractionManager.class)
public class QuickContainerLockClientPlayerInteractionManagerMixin {
    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedSlotClick(int syncId,
                                                 int slotId,
                                                 int button,
                                                 SlotActionType actionType,
                                                 PlayerEntity player,
                                                 CallbackInfo ci) {
        if (player == null
                || player.currentScreenHandler == null
                || player.currentScreenHandler.syncId != syncId) {
            return;
        }

        QuickContainerLock.beginSlotClickContext(player.currentScreenHandler, slotId, button, actionType);
        if (QuickContainerLock.shouldBlockClick(player.currentScreenHandler, slotId, button, actionType)) {
            QuickContainerLock.endSlotClickContext();
            ci.cancel();
        }
    }

    @Inject(method = "clickSlot", at = @At("RETURN"))
    private void quickcraft$clearLockedSlotClickContext(int syncId,
                                                        int slotId,
                                                        int button,
                                                        SlotActionType actionType,
                                                        PlayerEntity player,
                                                        CallbackInfo ci) {
        QuickContainerLock.endSlotClickContext();
    }
}
