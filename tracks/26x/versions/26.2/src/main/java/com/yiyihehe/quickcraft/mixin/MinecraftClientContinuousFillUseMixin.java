package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickMaterialCollector;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2+ 连续填充期间压住同一次长按触发的原版右键 use。
 * 容器界面压制由 GuiContinuousFillScreenMixin 负责。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientContinuousFillUseMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousFillUse(CallbackInfo ci) {
        if (QuickContainerCopy.shouldSuppressContinuousFillUseInput()
                || QuickMaterialCollector.shouldSuppressUseInput()) {
            ci.cancel();
        }
    }
}
