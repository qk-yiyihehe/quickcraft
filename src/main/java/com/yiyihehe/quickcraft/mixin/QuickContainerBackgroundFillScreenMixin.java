package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 后台填充期间隐藏真实打开的容器/潜影盒界面，只保留服务端槽位操作。
 */
@Mixin(Screen.class)
public abstract class QuickContainerBackgroundFillScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hideBackgroundFillScreen(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof AbstractContainerScreen<?>
                && QuickContainerCopy.shouldHideBackgroundHandledScreen()) {
            ci.cancel();
        }
    }
}
