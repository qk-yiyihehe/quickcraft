package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Litematica 0.19.61 的轻松放置固定从 mc.player 发起射线；灵魂出窍联动只替换射线实体，
 * 背包取材、放置玩家和交互距离仍使用真实玩家。调用点失效时只会退回玩家视角选取投影。
 */
@Mixin(value = WorldUtils.class, remap = false)
public final class LitematicaWorldUtilsFreeCameraMixin {
    @ModifyArg(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getGenericTrace(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;DZZZ)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForNearestSchematicTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(MinecraftClient.getInstance(), originalEntity);
    }

    @ModifyArg(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getFurthestSchematicWorldTraceBeforeVanilla(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;D)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForFurthestSchematicTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(MinecraftClient.getInstance(), originalEntity);
    }

    @ModifyArg(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/RayTraceUtils;getRayTraceFromEntity(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZD)Lnet/minecraft/util/hit/HitResult;",
                    remap = false
            ),
            index = 1,
            remap = false
    )
    private static Entity quickcraft$useFreeCameraForVanillaTrace(Entity originalEntity) {
        return QuickFreeCameraInteractions.getEasyPlaceTraceEntity(MinecraftClient.getInstance(), originalEntity);
    }
}
