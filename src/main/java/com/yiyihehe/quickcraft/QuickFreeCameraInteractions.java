package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
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
    private static boolean entitySneakingReleasePending;
    private static boolean cameraFacingApplied;
    private static int cameraFacingDepth;
    private static boolean serverFacingRestorePending;
    private static int serverFacingRestoreDelayTicks;
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
                && isSneakKeyPressed(client);
    }

    public static boolean shouldCancelInteractionFromFreeCamera(MinecraftClient client) {
        if (!shouldOverrideCrosshair(client) || !isSneakKeyPressed(client)) {
            return false;
        }

        return client.crosshairTarget instanceof EntityHitResult
                ? QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                : QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled();
    }

    /**
     * 1.21 的实体交互包会用自身的布尔值覆盖服务端潜行状态，单独发送 SHIFT 命令不足以生效。
     */
    public static boolean resolveEntitySneaking(MinecraftClient client, boolean originalSneaking) {
        if (originalSneaking
                || !shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraEntityInteractionsEnabled()
                || !isSneakKeyPressed(client)) {
            return originalSneaking;
        }

        entitySneakingReleasePending = true;
        return true;
    }

    public static void endEntityUseFromFreeCamera(MinecraftClient client) {
        if (!entitySneakingReleasePending) {
            return;
        }

        entitySneakingReleasePending = false;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }

        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)
        );
    }

    public static void beginBlockUseFromFreeCamera(MinecraftClient client) {
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void beginItemUseFromFreeCamera(MinecraftClient client) {
        beginCameraFacingToCrosshair(client);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(MinecraftClient client) {
        endSneakPlacement(client);
        endCameraFacing(client);
    }

    public static void tick(MinecraftClient client) {
        if (!serverFacingRestorePending || serverFacingRestoreDelayTicks-- > 0) {
            return;
        }

        serverFacingRestorePending = false;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }

        client.player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(restoredYaw, restoredPitch, client.player.isOnGround())
        );
    }

    /**
     * Tweakeroo's accurate-placement protocol stores direction bits in the hit X coordinate.
     * Litematica can leave those bits pointing east even after the player yaw is synchronized.
     */
    public static BlockHitResult encodeObserverPlacementDirection(MinecraftClient client, BlockHitResult hitResult) {
        if (!shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || (!client.player.getMainHandStack().isOf(Items.OBSERVER)
                && !client.player.getOffHandStack().isOf(Items.OBSERVER))) {
            return hitResult;
        }

        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return hitResult;
        }

        Direction direction = Direction.getEntityFacingOrder(camera)[0];
        Hand placementHand = client.player.getMainHandStack().isOf(Items.OBSERVER)
                ? Hand.MAIN_HAND
                : Hand.OFF_HAND;
        ItemStack placementStack = client.player.getStackInHand(placementHand);
        ItemPlacementContext placementContext = new ItemPlacementContext(
                client.player,
                placementHand,
                placementStack,
                hitResult
        );
        BlockPos placementPos = placementContext.getBlockPos();
        int previousProtocolValue = (int) (hitResult.getPos().x - placementPos.getX()) - 2;
        int preservedValueBits = previousProtocolValue >= 0 ? previousProtocolValue & ~0xF : 0;
        int protocolValue = preservedValueBits | (direction.getId() << 1);
        Vec3d encodedPos = new Vec3d(
                placementPos.getX() + 2.25D + protocolValue,
                hitResult.getPos().y,
                hitResult.getPos().z
        );
        return new BlockHitResult(
                encodedPos,
                hitResult.getSide(),
                hitResult.getBlockPos(),
                hitResult.isInsideBlock()
        );
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
        if (!shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || client.player.networkHandler == null) {
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

        ClientPlayerEntity player = client.player;
        restoredYaw = player.getYaw();
        restoredPitch = player.getPitch();
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();
        if (cameraYaw == restoredYaw && cameraPitch == restoredPitch) {
            return;
        }

        serverFacingRestorePending = false;
        player.setYaw(cameraYaw);
        player.setPitch(cameraPitch);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(cameraYaw, cameraPitch, player.isOnGround())
        );
        cameraFacingApplied = true;
        cameraFacingDepth = 1;
    }

    /**
     * 桶的 {@code use} 会在客户端和服务端都从玩家眼睛重新射线；将临时朝向指向相机命中点，
     * 才能让偏离本体的灵魂视角在原版交互距离内命中同一方块。
     */
    private static void beginCameraFacingToCrosshair(MinecraftClient client) {
        if (!shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || client.player == null
                || client.player.networkHandler == null) {
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

        HitResult target = client.crosshairTarget;
        if (!(target instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            beginCameraFacing(client);
            return;
        }

        ClientPlayerEntity player = client.player;
        Vec3d delta = blockHitResult.getPos().subtract(player.getEyePos());
        double horizontalLength = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (delta.lengthSquared() < 1.0E-7D) {
            beginCameraFacing(client);
            return;
        }

        restoredYaw = player.getYaw();
        restoredPitch = player.getPitch();
        float targetYaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0D / Math.PI));
        float targetPitch = (float) (Math.atan2(-delta.y, horizontalLength) * (180.0D / Math.PI));
        serverFacingRestorePending = false;
        player.setYaw(targetYaw);
        player.setPitch(targetPitch);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(targetYaw, targetPitch, player.isOnGround())
        );
        cameraFacingApplied = true;
        cameraFacingDepth = 1;
    }

    private static void endCameraFacing(MinecraftClient client) {
        if (!cameraFacingApplied) {
            return;
        }

        if (cameraFacingDepth > 1) {
            cameraFacingDepth--;
            return;
        }

        cameraFacingApplied = false;
        cameraFacingDepth = 0;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        player.setYaw(restoredYaw);
        player.setPitch(restoredPitch);
        serverFacingRestorePending = true;
        serverFacingRestoreDelayTicks = 1;
    }

    private static boolean isSneakKeyPressed(MinecraftClient client) {
        return client != null
                && client.options != null
                && client.options.sneakKey.isPressed();
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
