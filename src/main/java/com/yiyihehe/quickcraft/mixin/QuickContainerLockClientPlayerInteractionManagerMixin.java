package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSessions;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
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
    @Inject(method = {"clickRecipe", "clickButton"}, at = @At("HEAD"))
    private void quickcraft$cancelResultTransferOnRecipeSelection(CallbackInfo ci) {
        QuickTransfer.cancelResultTransfer();
    }

    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockLockedSlotClick(int syncId,
                                                 int slotId,
                                                 int button,
                                                 SlotActionType actionType,
                                                 PlayerEntity player,
                                                 CallbackInfo ci,
                                                 @Share("manualSlotClick") LocalBooleanRef manualSlotClick) {
        QuickTransfer.cancelResultTransfer();
        if (QuickCrossBoxSortSessions.INSTANCE.blocksInventoryClicks()) {
            ci.cancel();
            return;
        }
        if (player == null
                || player.currentScreenHandler == null
                || player.currentScreenHandler.syncId != syncId) {
            return;
        }

        ScreenHandler handler = player.currentScreenHandler;
        boolean manual = QuickContainerLock.consumeManualSlotClick(handler, slotId, button, actionType);
        manualSlotClick.set(manual);
        QuickContainerLock.beginSlotClickContext(handler, slotId, button, actionType);
        if (QuickContainerLock.shouldBlockClick(handler, slotId, button, actionType, manual)) {
            QuickContainerLock.endSlotClickContext();
            ci.cancel();
        }
    }

    @WrapOperation(method = "clickSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/screen/ScreenHandler;onSlotClick(IILnet/minecraft/screen/slot/SlotActionType;Lnet/minecraft/entity/player/PlayerEntity;)V"))
    private void quickcraft$applySlotTakePermission(ScreenHandler handler, int slotId, int button,
                                                    SlotActionType actionType, PlayerEntity player, Operation<Void> original,
                                                    @Share("manualSlotClick") LocalBooleanRef manualSlotClick) {
        QuickContainerLock.runWithSlotTakePermission(manualSlotClick.get(),
                () -> original.call(handler, slotId, button, actionType, player));
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
