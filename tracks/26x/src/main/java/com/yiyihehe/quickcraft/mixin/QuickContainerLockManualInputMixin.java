package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContainerScreen.class)
public abstract class QuickContainerLockManualInputMixin {
    // 只标记原版输入分派的这一次槽位动作，直接调用点击 API 不会得到此标记。
    @WrapOperation(
            method = { "mouseClicked", "mouseReleased", "mouseDragged", "slotClicked", "checkHotbarMouseClicked",
                    "keyPressed", "checkHotbarKeyPressed", "quickCraftToSlots" },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V"))
    private void quickcraft$dispatchManualSlotClick(
            AbstractContainerScreen<?> screen,
            Slot slot,
            int slotId,
            int button,
            ContainerInput actionType,
            Operation<Void> original
    ) {
        QuickContainerLock.dispatchManualSlotClick(
                screen,
                slot == null ? slotId : slot.index,
                button,
                actionType,
                () -> original.call(screen, slot, slotId, button, actionType)
        );
    }
}
