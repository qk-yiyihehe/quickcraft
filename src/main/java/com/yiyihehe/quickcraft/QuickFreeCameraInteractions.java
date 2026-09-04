package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
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
    private static boolean sneakPlacementCommandSent;
    private static boolean cameraFacingApplied;
    private static float restoredYaw;
    private static float restoredPitch;

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

    /**
     * Tweakeroo 灵魂出窍默认用 DummyMovementInput 冻本体，潜行键只驱动相机下降，
     * {@code player.isSneaking()} 仍为 false。这里改读潜行键，让客户端按原版规则取消方块交互并走放置。
     */
    public static boolean shouldSneakPlaceFromFreeCamera(MinecraftClient client) {
        return shouldOverrideCrosshair(client)
                && QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                && client != null
                && client.options != null
                && client.options.sneakKey.isPressed();
    }

    public static void beginBlockUseFromFreeCamera(MinecraftClient client) {
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(MinecraftClient client) {
        endSneakPlacement(client);
        endCameraFacing(client);
    }

    private static void beginSneakPlacement(MinecraftClient client) {
        if (sneakPlacementCommandSent
                || !shouldSneakPlaceFromFreeCamera(client)
                || client.player == null
                || client.player.networkHandler == null) {
            return;
        }

        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY)
        );
        sneakPlacementCommandSent = true;
    }

    private static void endSneakPlacement(MinecraftClient client) {
        if (!sneakPlacementCommandSent) {
            return;
        }

        sneakPlacementCommandSent = false;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }

        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)
        );
    }

    /**
     * 方向方块的朝向读的是玩家本体 yaw/pitch。灵魂出窍时改用相机朝向做客户端预测，
     * 并先发一条 Look 包让服务端按同一朝向放置，放完立刻还原，避免本体转身。
     */
    private static void beginCameraFacing(MinecraftClient client) {
        if (cameraFacingApplied
                || !shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || client.player.networkHandler == null) {
            return;
        }

        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return;
        }

        ClientPlayerEntity player = client.player;
        restoredYaw = player.getYaw();
        restoredPitch = player.getPitch();
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();
        if (cameraYaw == restoredYaw && cameraPitch == restoredPitch) {
            return;
        }

        player.setYaw(cameraYaw);
        player.setPitch(cameraPitch);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(cameraYaw, cameraPitch, player.isOnGround())
        );
        cameraFacingApplied = true;
    }

    private static void endCameraFacing(MinecraftClient client) {
        if (!cameraFacingApplied) {
            return;
        }

        cameraFacingApplied = false;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        player.setYaw(restoredYaw);
        player.setPitch(restoredPitch);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(restoredYaw, restoredPitch, player.isOnGround())
        );
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
