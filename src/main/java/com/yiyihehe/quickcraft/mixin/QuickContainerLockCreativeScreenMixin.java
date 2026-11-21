package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeInventoryScreen.class)
public abstract class QuickContainerLockCreativeScreenMixin {
    @Inject(method = "onMouseClick", at = @At("HEAD"), cancellable = true)
    private void quickcraft$protectCreativeClick(Slot slot, int slotId, int button, SlotActionType actionType,
                                                  CallbackInfo ci) {
        var handler = ((CreativeInventoryScreen) (Object) this).getScreenHandler();
        boolean manual = QuickContainerLock.consumeManualSlotClick(
                handler, slot == null ? slotId : slot.id, button, actionType);
        if (QuickContainerLock.shouldBlockClick(handler, slot, button, actionType, manual)) {
            ci.cancel();
        }
    }

    // 第一次创造槽同步来自垃圾桶的整包清空，其余调用负责普通点击的同步。
    @WrapOperation(method = "onMouseClick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;clickCreativeStack(Lnet/minecraft/item/ItemStack;I)V"))
    private void quickcraft$skipLockedSlotDuringCreativeClear(ClientPlayerInteractionManager gameMode, ItemStack stack,
                                                              int slotId, Operation<Void> original) {
        if (!QuickContainerLock.isLockedSlot(MinecraftClient.getInstance().player.playerScreenHandler, slotId)) {
            original.call(gameMode, stack, slotId);
        }
    }
}
