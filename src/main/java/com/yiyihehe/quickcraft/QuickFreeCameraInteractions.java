package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 为 Tweakeroo 灵魂出窍提供相机准星交互，不修改玩家位置或服务端距离校验。
 */
public final class QuickFreeCameraInteractions {
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";

    private QuickFreeCameraInteractions() {
    }

    public static boolean shouldOverrideCrosshair(MinecraftClient client) {
        return QuickCraftConfigs.isFreeCameraEnhancementEnabled() && isTweakerooFreeCameraActive(client);
    }

    public static Entity getEasyPlaceTraceEntity(MinecraftClient client, Entity originalEntity) {
        if (!QuickCraftConfigs.isFreeCameraEnhancementEnabled()
                || !QuickCraftConfigs.isFreeCameraEasyPlaceEnabled()
                || !isTweakerooFreeCameraActive(client)) {
            return originalEntity;
        }

        Entity camera = client.getCameraEntity();
        return camera != null ? camera : originalEntity;
    }

    public static HitResult filterCrosshairTarget(MinecraftClient client, Entity camera, HitResult target) {
        if (target instanceof BlockHitResult && !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()) {
            return createMiss(camera, target.getPos());
        }

        if (target instanceof EntityHitResult entityHitResult
                && (!QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                || entityHitResult.getEntity() == client.player)) {
            return createMiss(camera, target.getPos());
        }

        return target;
    }

    public static boolean isBlockOutsideServerInteractionRange(MinecraftClient client, BlockPos position) {
        return shouldOverrideCrosshair(client)
                && !client.player.canInteractWithBlockAt(position, 1.0);
    }

    public static boolean isEntityOutsideServerInteractionRange(MinecraftClient client, Entity entity) {
        return shouldOverrideCrosshair(client)
                && !client.player.canInteractWithEntityIn(entity.getBoundingBox(), 1.0);
    }

    private static boolean isTweakerooFreeCameraActive(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }

        Entity camera = client.getCameraEntity();
        return camera != null
                && camera != client.player
                && TWEAKEROO_CAMERA_CLASS.equals(camera.getClass().getName());
    }

    private static BlockHitResult createMiss(Entity camera, Vec3d position) {
        Vec3d rotation = camera.getRotationVec(1.0F);
        return BlockHitResult.createMissed(
                position,
                Direction.getFacing(rotation.x, rotation.y, rotation.z),
                BlockPos.ofFloored(position)
        );
    }
}
