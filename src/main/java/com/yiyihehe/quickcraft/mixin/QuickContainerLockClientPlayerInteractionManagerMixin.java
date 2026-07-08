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
 * 客户端槽位点击发送前的锁格保护层。
 *
 * <p>{@link ClientPlayerInteractionManager#clickSlot(int, int, int, SlotActionType, PlayerEntity)}
 * 是客户端向服务端发送槽位操作的统一出口。这里在发包前取消锁格点击，并在同一次调用中保存
 * slot/button/actionType 上下文，供自动穿脱装备等下游逻辑判断。注入失效时，玩家操作会绕过锁格保护。</p>
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
