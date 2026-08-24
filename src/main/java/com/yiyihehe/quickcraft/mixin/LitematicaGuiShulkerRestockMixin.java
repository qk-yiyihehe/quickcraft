package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 由 Gui.setScreen 管理当前界面；补料等待开箱时压住后台容器屏幕。
 */
@Mixin(Gui.class)
public abstract class LitematicaGuiShulkerRestockMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$keepRestockShulkerInBackground(Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?>
                && QuickLitematicaShulkerMaterialRestock.shouldSuppressShulkerScreenOpen()) {
            ci.cancel();
        }
    }
}
