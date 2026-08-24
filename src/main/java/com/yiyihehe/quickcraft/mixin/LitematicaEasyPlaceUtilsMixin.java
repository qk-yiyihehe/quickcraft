package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 适配 Litematica 0.28.4 的重写轻松放置与放置限制入口，并保留持续放置缓存配置。
 */
@Mixin(value = EasyPlaceUtils.class, remap = false)
public abstract class LitematicaEasyPlaceUtilsMixin {
    @Inject(method = "handleEasyPlaceWithMessage", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUsePassThrough(CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(Minecraft.getInstance())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnVanillaInteractions(CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(Minecraft.getInstance())) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlacementRestriction()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letVanillaUseBypassPlacementRestriction(CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(Minecraft.getInstance())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "handlePlacementRestriction(Lnet/minecraft/client/Minecraft;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void quickcraft$letVanillaUseBypassLegacyPlacementRestriction(
            Minecraft client,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(client)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyConstant(
            method = "cacheEasyPlacePosition",
            constant = @Constant(longValue = 2_000_000_000L),
            remap = false
    )
    private static long quickcraft$useConfiguredCacheTime(long timeout) {
        return QuickCraftConfigs.isHoldEasyPlaceEnabled()
                ? 1_000_000L * QuickCraftConfigs.getHoldEasyPlaceCacheTimeMs()
                : timeout;
    }
}
