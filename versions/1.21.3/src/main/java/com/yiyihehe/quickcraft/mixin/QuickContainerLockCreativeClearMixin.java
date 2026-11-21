package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CreativeInventoryScreen.class)
public abstract class QuickContainerLockCreativeClearMixin {
    // 从 1.21.3 目标起，垃圾桶发包前还会在客户端清空这格。
    @WrapOperation(method = "onMouseClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/screen/slot/Slot;setStackNoCallbacks(Lnet/minecraft/item/ItemStack;)V"))
    private void quickcraft$keepLockedSlotDuringCreativeClear(Slot slot, ItemStack stack, Operation<Void> original) {
        if (!QuickContainerLock.isLockedSlot(slot)) {
            original.call(slot, stack);
        }
    }
}
