package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2+ 注入原版 Hud 快捷栏渲染。
 */
@Mixin(Hud.class)
public abstract class QuickContainerLockInGameHudMixin {
    @Inject(method = "extractItemHotbar", at = @At("TAIL"))
    private void quickcraft$renderLockedHotbarSlots(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        QuickContainerLock.renderHotbarLocks(context);
    }
}
