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
 * 快捷栏锁定状态的 HUD 渲染入口。
 *
 * <p>锁格数据不只在背包界面生效，快捷栏在 HUD 上也需要提示。注入
 * {@code renderHotbar} 末尾可以复用原版快捷栏位置，目标点失效时只影响锁头显示，
 * 不影响实际锁定判定。</p>
 */
@Mixin(InGameHud.class)
public abstract class QuickContainerLockInGameHudMixin {
    @Inject(method = "renderHotbar", at = @At("TAIL"))
    private void quickcraft$renderLockedHotbarSlots(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        QuickContainerLock.renderHotbarLocks(context);
    }
}
