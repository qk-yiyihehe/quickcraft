package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 为 Tweakeroo 灵魂出窍提供相机准星交互，不修改玩家位置或服务端距离校验。
 */
public final class QuickFreeCameraInteractions {
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";
    private static boolean sneakPlacementCommandSent;
    private static Input restoredPlayerInput;
    private static boolean cameraFacingApplied;
    private static float restoredYaw;
    private static float restoredPitch;

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

    /**
     * Tweakeroo 灵魂出窍默认用 DummyMovementInput 冻本体，潜行键只驱动相机下降，
     * {@code player.isShiftKeyDown()} 仍为 false。这里改读潜行键，让客户端按原版规则取消方块交互并走放置。
     */
    public static boolean shouldSneakPlaceFromFreeCamera(Minecraft client) {
        return shouldOverrideCrosshair(client)
                && QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                && client != null
                && client.options != null
                && client.options.keyShift.isDown();
    }

    public static void beginBlockUseFromFreeCamera(Minecraft client) {
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(Minecraft client) {
        endSneakPlacement(client);
        endCameraFacing(client);
    }

    private static void beginSneakPlacement(Minecraft client) {
        if (sneakPlacementCommandSent
                || !shouldSneakPlaceFromFreeCamera(client)
                || client.player == null
                || client.player.connection == null
                || client.player.input == null) {
            return;
        }

        restoredPlayerInput = client.player.input.keyPresses;
        Input sneaking = new Input(
                restoredPlayerInput.forward(),
                restoredPlayerInput.backward(),
                restoredPlayerInput.left(),
                restoredPlayerInput.right(),
                restoredPlayerInput.jump(),
                true,
                restoredPlayerInput.sprint()
        );
        client.player.connection.send(new ServerboundPlayerInputPacket(sneaking));
        sneakPlacementCommandSent = true;
    }

    private static void endSneakPlacement(Minecraft client) {
        if (!sneakPlacementCommandSent) {
            return;
        }

        sneakPlacementCommandSent = false;
        Input restored = restoredPlayerInput;
        restoredPlayerInput = null;
        if (client == null || client.player == null || client.player.connection == null || restored == null) {
            return;
        }

        client.player.connection.send(new ServerboundPlayerInputPacket(restored));
    }

    /**
     * 方向方块的朝向读的是玩家本体 yaw/pitch。灵魂出窍时改用相机朝向做客户端预测，
     * 并先发一条 Look 包让服务端按同一朝向放置，放完立刻还原，避免本体转身。
     * 26.x 的 Rot 包需要带上 horizontalCollision，否则服务端会拒绝或改写朝向。
     */
    private static void beginCameraFacing(Minecraft client) {
        if (cameraFacingApplied
                || !shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || client.player.connection == null) {
            return;
        }

        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return;
        }

        LocalPlayer player = client.player;
        restoredYaw = player.getYRot();
        restoredPitch = player.getXRot();
        float cameraYaw = camera.getYRot();
        float cameraPitch = camera.getXRot();
        if (cameraYaw == restoredYaw && cameraPitch == restoredPitch) {
            return;
        }

        player.setYRot(cameraYaw);
        player.setXRot(cameraPitch);
        player.connection.send(
                new ServerboundMovePlayerPacket.Rot(cameraYaw, cameraPitch, player.onGround(), player.horizontalCollision)
        );
        cameraFacingApplied = true;
    }

    private static void endCameraFacing(Minecraft client) {
        if (!cameraFacingApplied) {
            return;
        }

        cameraFacingApplied = false;
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }

        LocalPlayer player = client.player;
        player.setYRot(restoredYaw);
        player.setXRot(restoredPitch);
        player.connection.send(
                new ServerboundMovePlayerPacket.Rot(restoredYaw, restoredPitch, player.onGround(), player.horizontalCollision)
        );
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
