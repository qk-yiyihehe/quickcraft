package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSessions;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
    @Inject(method = {"handlePlaceRecipe", "handleInventoryButtonClick"}, at = @At("HEAD"))
    private void quickcraft$cancelResultTransferOnRecipeSelection(CallbackInfo ci) {
        QuickTransfer.cancelResultTransfer();
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedSlotClick(int syncId,
                                                 int slotId,
                                                 int button,
                                                 ContainerInput actionType,
                                                 Player player,
                                                 CallbackInfo ci,
                                                 @Share("manualSlotClick") LocalBooleanRef manualSlotClick) {
        QuickTransfer.cancelResultTransfer();
        if (QuickCrossBoxSortSessions.INSTANCE.blocksInventoryClicks()) {
            ci.cancel();
            return;
        }
        if (player == null
                || player.containerMenu == null
                || player.containerMenu.containerId != syncId) {
            return;
        }

        AbstractContainerMenu handler = player.containerMenu;
        boolean manual = QuickContainerLock.consumeManualSlotClick(handler, slotId, button, actionType);
        manualSlotClick.set(manual);
        QuickContainerLock.beginSlotClickContext(handler, slotId, button, actionType);
        if (QuickContainerLock.shouldBlockClick(handler, slotId, button, actionType, manual)) {
            QuickContainerLock.endSlotClickContext();
            ci.cancel();
        }
    }

    @WrapOperation(method = "handleContainerInput", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"))
    private void quickcraft$applySlotTakePermission(AbstractContainerMenu handler, int slotId, int button,
                                                    ContainerInput actionType, Player player, Operation<Void> original,
                                                    @Share("manualSlotClick") LocalBooleanRef manualSlotClick) {
        QuickContainerLock.runWithSlotTakePermission(manualSlotClick.get(),
                () -> original.call(handler, slotId, button, actionType, player));
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
