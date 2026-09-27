package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.InteractionResult;

/**
 * Litematica 0.27.9–0.27.11 的轻松放置固定从 mc.player 发起射线；灵魂出窍联动只替换射线实体，
 * 背包取材、放置玩家和交互距离仍使用真实玩家。调用点失效时只会退回玩家视角选取投影。
 */
@Mixin(value = WorldUtils.class, remap = false, priority = 1100)
public final class LitematicaWorldUtilsFreeCameraMixin {
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), remap = false)
    private static void quickcraft$beginEasyPlaceAction(
            Minecraft client, CallbackInfoReturnable<InteractionResult> cir) {
        QuickFreeCameraInteractions.beginEasyPlaceAction();
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), remap = false)
    private static void quickcraft$endEasyPlaceAction(
            Minecraft client, CallbackInfoReturnable<InteractionResult> cir) {
        QuickFreeCameraInteractions.endEasyPlaceAction();
    }

    @ModifyArg(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getGenericTrace(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;DZZZ)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForNearestSchematicTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(Minecraft.getInstance(), originalEntity);
    }

    @ModifyArg(
            method = "doEasyPlaceAction",
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
            method = "doEasyPlaceAction",
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
}
