package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端发出 handleContainerInput 前补一层格子锁判断，
 * 同时为自动穿脱鞘翅维护一次点击上下文。
 */
@Mixin(MultiPlayerGameMode.class)
public class QuickContainerLockClientPlayerInteractionManagerMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedSlotClick(int syncId,
                                                 int slotId,
                                                 int button,
                                                 ContainerInput actionType,
                                                 Player player,
                                                 CallbackInfo ci) {
        if (player == null
                || player.containerMenu == null
                || player.containerMenu.containerId != syncId) {
            return;
        }

        QuickContainerLock.beginSlotClickContext(player.containerMenu, slotId, button, actionType);
        if (QuickContainerLock.shouldBlockClick(player.containerMenu, slotId, button, actionType)) {
            QuickContainerLock.endSlotClickContext();
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void quickcraft$clearLockedSlotClickContext(int syncId,
                                                        int slotId,
                                                        int button,
                                                        ContainerInput actionType,
                                                        Player player,
                                                        CallbackInfo ci) {
        QuickContainerLock.endSlotClickContext();
    }
}
