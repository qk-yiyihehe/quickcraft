package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quick Shulker 已创建后台处理器后才调用 MinecraftClient#setScreen。
 * 仅在补料请求等待开箱时取消该调用，确保玩家不会看到自动开箱的容器界面。
 */
@Mixin(MinecraftClient.class)
public abstract class LitematicaMinecraftClientShulkerRestockMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void quickcraft$keepRestockShulkerInBackground(Screen screen, CallbackInfo ci) {
        if (screen instanceof HandledScreen<?>
                && QuickLitematicaShulkerMaterialRestock.shouldSuppressShulkerScreenOpen()) {
            ci.cancel();
        }
    }
}
