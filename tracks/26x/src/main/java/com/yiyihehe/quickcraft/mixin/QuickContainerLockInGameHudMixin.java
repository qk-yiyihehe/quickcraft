package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品栏 HUD 里的快捷栏锁头。
 * 这样退出背包后，底部热栏也能继续看到锁定状态。
 */
@Mixin(Gui.class)
public abstract class QuickContainerLockInGameHudMixin {
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void quickcraft$renderLockedHotbarSlots(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        QuickContainerLock.renderHotbarLocks(context);
    }
}
