package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class QuickContainerLockCreativeScreenMixin {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$protectCreativeClick(
            Slot slot,
            int slotId,
            int button,
            ContainerInput actionType,
            CallbackInfo ci
    ) {
        var menu = ((CreativeModeInventoryScreen) (Object) this).getMenu();
        boolean manual = QuickContainerLock.consumeManualSlotClick(
                menu,
                slot == null ? slotId : slot.index,
                button,
                actionType
        );
        if (QuickContainerLock.shouldBlockClick(menu, slot, button, actionType, manual)) {
            ci.cancel();
        }
    }

    // 第一次创造槽同步来自垃圾桶的整包清空，其余调用负责普通点击的同步。
    @WrapOperation(
            method = "slotClicked",
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handleCreativeModeItemAdd(Lnet/minecraft/world/item/ItemStack;I)V"))
    private void quickcraft$skipLockedSlotDuringCreativeClear(
            MultiPlayerGameMode gameMode,
            ItemStack stack,
            int slotId,
            Operation<Void> original
    ) {
        if (!QuickContainerLock.isLockedSlot(Minecraft.getInstance().player.inventoryMenu, slotId)) {
            original.call(gameMode, stack, slotId);
        }
    }

    @WrapOperation(
            method = "slotClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;set(Lnet/minecraft/world/item/ItemStack;)V"))
    private void quickcraft$keepLockedSlotDuringCreativeClear(Slot slot, ItemStack stack, Operation<Void> original) {
        if (!QuickContainerLock.isLockedSlot(slot)) {
            original.call(slot, stack);
        }
    }
}
