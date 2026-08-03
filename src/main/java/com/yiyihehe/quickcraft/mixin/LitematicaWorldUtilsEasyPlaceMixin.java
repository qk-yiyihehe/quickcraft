package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceContainers;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让轻松放置在真实容器前让出右键，避免拦截原版开箱行为。
 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letContainerUsePassThrough(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceContainers.shouldAllowVanillaContainerUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnContainers(Minecraft mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceContainers.shouldAllowVanillaContainerUse(mc)) {
            ci.cancel();
        }
    }
}
