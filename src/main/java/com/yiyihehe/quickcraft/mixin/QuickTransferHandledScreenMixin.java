package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickTransfer;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截原版 Shift 快速转移，把“保留一个”逻辑收口到 QuickTransfer。
 */
@Mixin(HandledScreen.class)
public abstract class QuickTransferHandledScreenMixin {
    @Inject(method = "onMouseClick", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleRetainOneQuickMove(Slot slot,
                                                     int slotId,
                                                     int button,
                                                     SlotActionType actionType,
                                                     CallbackInfo ci) {
        if (QuickTransfer.handleRetainOneQuickMove((HandledScreen<?>) (Object) this, slot, actionType)) {
            ci.cancel();
        }
    }
}
