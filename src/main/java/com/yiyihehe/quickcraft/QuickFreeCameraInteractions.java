package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 为 Tweakeroo 灵魂出窍提供相机准星交互，不修改玩家位置或服务端距离校验。 */
public final class QuickFreeCameraInteractions {
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";
    private static boolean sneakPlacementCommandSent;
    private static Input restoredPlayerInput;
    private static boolean cameraFacingApplied;
    private static int cameraFacingDepth;
    private static boolean serverFacingRestorePending;
    private static int serverFacingRestoreDelayTicks;
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

    /** Tweakeroo 冻结本体输入时，潜行键仍应控制原版交互是否让位。 */
    public static boolean shouldSneakPlaceFromFreeCamera(Minecraft client) {
        return shouldOverrideCrosshair(client)
                && QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                && isSneakKeyPressed(client);
    }

    public static boolean shouldCancelInteractionFromFreeCamera(Minecraft client) {
        if (!shouldOverrideCrosshair(client) || !isSneakKeyPressed(client)) {
            return false;
        }
        return client.hitResult instanceof EntityHitResult
                ? QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                : QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled();
    }

    /** 26.x 的实体交互包自身携带潜行状态，不能只发送玩家输入包。 */
    public static boolean resolveEntitySneaking(Minecraft client, boolean originalSneaking) {
        return originalSneaking
                || (shouldOverrideCrosshair(client)
                && QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                && isSneakKeyPressed(client));
    }

    public static void beginBlockUseFromFreeCamera(Minecraft client) {
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void beginItemUseFromFreeCamera(Minecraft client) {
        beginCameraFacingToCrosshair(client);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(Minecraft client) {
        endSneakPlacement(client);
        endCameraFacing(client);
    }

    public static void tick(Minecraft client) {
        if (!serverFacingRestorePending || serverFacingRestoreDelayTicks-- > 0) {
            return;
        }
        serverFacingRestorePending = false;
        if (client != null && client.player != null && client.player.connection != null) {
            client.player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    restoredYaw, restoredPitch, client.player.onGround(), client.player.horizontalCollision));
        }
    }

    /** Tweakeroo 精准放置协议把朝向编码在命中点 X 坐标中，观察者方块需与灵魂相机朝向一致。 */
    public static BlockHitResult encodeObserverPlacementDirection(Minecraft client, BlockHitResult hitResult) {
        if (!shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || (!client.player.getMainHandItem().is(Items.OBSERVER)
                && !client.player.getOffhandItem().is(Items.OBSERVER))) {
            return hitResult;
        }
        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return hitResult;
        }
        Direction direction = Direction.orderedByNearest(camera)[0];
        InteractionHand hand = client.player.getMainHandItem().is(Items.OBSERVER)
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = client.player.getItemInHand(hand);
        BlockPos placementPos = new BlockPlaceContext(client.player, hand, stack, hitResult).getClickedPos();
        int previousValue = (int) (hitResult.getLocation().x - placementPos.getX()) - 2;
        int preservedBits = previousValue >= 0 ? previousValue & ~0xF : 0;
        int protocolValue = preservedBits | (direction.get3DDataValue() << 1);
        Vec3 encodedPos = new Vec3(placementPos.getX() + 2.25D + protocolValue,
                hitResult.getLocation().y, hitResult.getLocation().z);
        return new BlockHitResult(encodedPos, hitResult.getDirection(), hitResult.getBlockPos(), hitResult.isInside());
    }

    private static void beginSneakPlacement(Minecraft client) {
        if (sneakPlacementCommandSent || !shouldSneakPlaceFromFreeCamera(client)
                || client.player == null || client.player.connection == null || client.player.input == null) {
            return;
        }
        restoredPlayerInput = client.player.input.keyPresses;
        Input sneaking = new Input(restoredPlayerInput.forward(), restoredPlayerInput.backward(),
                restoredPlayerInput.left(), restoredPlayerInput.right(),
                restoredPlayerInput.jump(), true, restoredPlayerInput.sprint());
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
        if (client != null && client.player != null && client.player.connection != null && restored != null) {
            client.player.connection.send(new ServerboundPlayerInputPacket(restored));
        }
    }

    /** 客户端预测和服务端放置都读取玩家本体方向，临时与灵魂相机同步。 */
    private static void beginCameraFacing(Minecraft client) {
        if (!shouldOverrideCrosshair(client) || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null || client.player.connection == null) {
            return;
        }
        if (cameraFacingApplied) {
            cameraFacingDepth++;
            return;
        }
        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return;
        }
        LocalPlayer player = client.player;
        restoredYaw = player.getYRot();
        restoredPitch = player.getXRot();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        if (yaw != restoredYaw || pitch != restoredPitch) {
            applyCameraFacing(player, yaw, pitch);
        }
    }

    /** 桶等物品从本体眼睛重新射线，故临时朝向必须指向相机命中点。 */
    private static void beginCameraFacingToCrosshair(Minecraft client) {
        if (!shouldOverrideCrosshair(client) || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null || client.player.connection == null) {
            return;
        }
        if (cameraFacingApplied) {
            cameraFacingDepth++;
            return;
        }
        if (!(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            beginCameraFacing(client);
            return;
        }
        LocalPlayer player = client.player;
        Vec3 delta = hit.getLocation().subtract(player.getEyePosition());
        if (delta.lengthSqr() < 1.0E-7D) {
            beginCameraFacing(client);
            return;
        }
        restoredYaw = player.getYRot();
        restoredPitch = player.getXRot();
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0D / Math.PI));
        float pitch = (float) (Math.atan2(-delta.y, horizontal) * (180.0D / Math.PI));
        applyCameraFacing(player, yaw, pitch);
    }

    private static void applyCameraFacing(LocalPlayer player, float yaw, float pitch) {
        serverFacingRestorePending = false;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(
                yaw, pitch, player.onGround(), player.horizontalCollision));
        cameraFacingApplied = true;
        cameraFacingDepth = 1;
    }

    private static void endCameraFacing(Minecraft client) {
        if (!cameraFacingApplied) {
            return;
        }
        if (cameraFacingDepth > 1) {
            cameraFacingDepth--;
            return;
        }
        cameraFacingApplied = false;
        cameraFacingDepth = 0;
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        client.player.setYRot(restoredYaw);
        client.player.setXRot(restoredPitch);
        serverFacingRestorePending = true;
        serverFacingRestoreDelayTicks = 1;
    }

    private static boolean isSneakKeyPressed(Minecraft client) {
        return client != null && client.options != null && client.options.keyShift.isDown();
    }

    private static boolean isTweakerooFreeCameraActive(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        Entity camera = client.getCameraEntity();
        return camera != null && camera != client.player
                && TWEAKEROO_CAMERA_CLASS.equals(camera.getClass().getName());
    }

    private static BlockHitResult createMiss(Entity camera, Vec3 position) {
        Vec3 rotation = camera.getViewVector(1.0F);
        return BlockHitResult.miss(position,
                Direction.getApproximateNearest(rotation.x, rotation.y, rotation.z),
                BlockPos.containing(position));
    }
}
