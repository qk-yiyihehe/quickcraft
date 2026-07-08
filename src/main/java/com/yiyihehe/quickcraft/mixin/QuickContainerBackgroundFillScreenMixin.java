package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 后台容器填充的屏幕隐藏层。
 *
 * <p>连续填充会临时打开真实容器来执行服务端槽位操作，但玩家不应该看到这些中间界面。
 * 拦截 {@link HandledScreen#render(DrawContext, int, int, float)} 只隐藏画面，不改变屏幕处理器生命周期；
 * 注入失效时会看到容器界面闪烁或短暂抢占前台。</p>
 */
@Mixin(HandledScreen.class)
public abstract class QuickContainerBackgroundFillScreenMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideBackgroundFillScreen(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (QuickContainerCopy.shouldHideBackgroundHandledScreen()) {
            ci.cancel();
        }
    }
}
