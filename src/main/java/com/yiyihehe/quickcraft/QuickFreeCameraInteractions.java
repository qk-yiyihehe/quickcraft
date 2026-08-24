package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 为 Tweakeroo 灵魂出窍提供相机准星交互，不修改玩家位置或服务端距离校验。
 */
public final class QuickFreeCameraInteractions {
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";

    private QuickFreeCameraInteractions() {
    }

    public static boolean shouldOverrideCrosshair(Minecraft client) {
        return QuickCraftConfigs.isFreeCameraEnhancementEnabled() && isTweakerooFreeCameraActive(client);
    }

    public static Entity getEasyPlaceTraceEntity(Minecraft client, Entity originalEntity) {
        if (!QuickCraftConfigs.isFreeCameraEnhancementEnabled()
                || !QuickCraftConfigs.isFreeCameraEasyPlaceEnabled()
                || !isTweakerooFreeCameraActive(client)) {
            return originalEntity;
        }

        Entity camera = client.getCameraEntity();
        return camera != null ? camera : originalEntity;
    }

    public static HitResult filterCrosshairTarget(Minecraft client, Entity camera, HitResult target) {
        if (target instanceof BlockHitResult && !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()) {
            return createMiss(camera, target.getLocation());
        }

        if (target instanceof EntityHitResult entityHitResult
                && (!QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                || entityHitResult.getEntity() == client.player)) {
            return createMiss(camera, target.getLocation());
        }

        return target;
    }

    public static boolean isBlockOutsideServerInteractionRange(Minecraft client, BlockPos position) {
        return shouldOverrideCrosshair(client)
                && !client.player.isWithinBlockInteractionRange(position, 1.0);
    }

    public static boolean isEntityOutsideServerInteractionRange(Minecraft client, Entity entity) {
        return shouldOverrideCrosshair(client)
                && !client.player.isWithinEntityInteractionRange(entity.getBoundingBox(), 1.0);
    }

    private static boolean isTweakerooFreeCameraActive(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }

        Entity camera = client.getCameraEntity();
        return camera != null
                && camera != client.player
                && TWEAKEROO_CAMERA_CLASS.equals(camera.getClass().getName());
    }

    private static BlockHitResult createMiss(Entity camera, Vec3 position) {
        Vec3 rotation = camera.getViewVector(1.0F);
        return BlockHitResult.miss(
                position,
                Direction.getApproximateNearest(rotation.x, rotation.y, rotation.z),
                BlockPos.containing(position)
        );
    }
}
