package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 适配 Litematica 0.28.3–0.28.5 的重写轻松放置入口。
 * HEAD 补料必须早于缺料返回，射线参数只替换观察实体；目标失效时对应功能会静默退回 Litematica 行为。
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

    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$restockBeforeRewrittenEasyPlace(CallbackInfoReturnable<InteractionResult> cir) {
        if (QuickLitematicaShulkerMaterialRestock.requestMaterialForEasyPlaceTarget(Minecraft.getInstance())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @ModifyArg(
            method = "handleEasyPlace",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getFurthestSchematicWorldTraceBeforeVanilla(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;D)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForFurthestSchematicTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(Minecraft.getInstance(), originalEntity);
    }

    @ModifyArg(
            method = "handleEasyPlace",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getRayTraceFromEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZD)Lnet/minecraft/world/phys/HitResult;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForVanillaTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(Minecraft.getInstance(), originalEntity);
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
