package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HandledScreen.class)
public abstract class QuickContainerLockManualInputMixin {
    // 只标记原版输入分派的这一次槽位动作，直接调用点击 API 不会得到此标记。
    @WrapOperation(method = {"mouseClicked", "mouseReleased", "mouseDragged", "onMouseClick",
            "keyPressed", "handleHotbarKeyPressed"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V"))
    private void quickcraft$dispatchManualSlotClick(HandledScreen<?> screen, Slot slot, int slotId, int button,
                                                    SlotActionType actionType, Operation<Void> original) {
        QuickContainerLock.dispatchManualSlotClick(screen, slot == null ? slotId : slot.id, button, actionType,
                () -> original.call(screen, slot, slotId, button, actionType));
    }
}
