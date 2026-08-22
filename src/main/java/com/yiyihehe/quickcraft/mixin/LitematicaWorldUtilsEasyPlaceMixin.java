package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在可配置的真实方块交互前让轻松放置让出右键。
 * Litematica 的 WorldUtils 会对带 onUse 的支撑方块伪装潜行后继续发送放置包；
 * 因此必须同时退出轻松放置和放置限制入口，原版交互才不会被覆盖。
 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUsePassThrough(MinecraftClient mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnVanillaInteractions(MinecraftClient mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlacementRestriction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUseBypassPlacementRestriction(
            MinecraftClient mc,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }
}
