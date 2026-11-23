package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer;
import com.yiyihehe.quickcraft.portablestorage.QuickShulkerRestockFallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1.2 在 Minecraft 层面压住后台容器打开与同一次长按的物品使用。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientContinuousFillUseMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressBackgroundHandledScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen
                && (QuickContainerCopy.shouldSuppressBackgroundHandledScreenOpen(containerScreen.getMenu())
                || QuickMaterialCollector.shouldSuppressBackgroundHandledScreenOpen())) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void quickcraft$suppressContinuousFillUse(CallbackInfo ci) {
        if (QuickContainerCopy.shouldSuppressContinuousFillUseInput()
                || QuickMaterialCollector.shouldSuppressUseInput()
                || QuickPortableStorageTransfer.blocksItemUse()) {
            ci.cancel();
        } else {
            QuickShulkerRestockFallback.itemUseStarted((Minecraft) (Object) this);
        }
    }
}
