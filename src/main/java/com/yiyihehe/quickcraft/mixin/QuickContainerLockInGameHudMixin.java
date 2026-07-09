package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品栏 HUD 里的快捷栏锁头。
 * 这样退出背包后，底部热栏也能继续看到锁定状态。
 */
@Mixin(InGameHud.class)
public abstract class QuickContainerLockInGameHudMixin {
    @Inject(method = "renderHotbar", at = @At("TAIL"))
    private void quickcraft$renderLockedHotbarSlots(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        QuickContainerLock.renderHotbarLocks(context);
    }
}
