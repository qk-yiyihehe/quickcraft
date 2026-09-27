package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#if MC>=260200
//$$ import net.minecraft.client.gui.Hud;
//#else
import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品栏 HUD 里的快捷栏锁头。
 * 这样退出背包后，底部热栏也能继续看到锁定状态。
 */
//#if MC>=260200
//$$ @Mixin(Hud.class)
//#else
@Mixin(Gui.class)
//#endif
public abstract class QuickContainerLockInGameHudMixin {
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void quickcraft$renderLockedHotbarSlots(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        QuickContainerLock.renderHotbarLocks(context);
    }
}
