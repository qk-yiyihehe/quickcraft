package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 26.2–26.3 扩展 Litematica 轻松放置的水源施工与原版交互退让。 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$placeIceOverProjectedSourceWater(
            Minecraft client, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = QuickLitematicaEasyPlaceInteractions.placeIceOverProjectedSourceWater(client);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUsePassThrough(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnVanillaInteractions(Minecraft mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(mc)) {
            ci.cancel();
        }
    }
}
