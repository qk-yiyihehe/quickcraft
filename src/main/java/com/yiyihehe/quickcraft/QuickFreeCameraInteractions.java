package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 为 Tweakeroo 灵魂出窍提供相机准星交互，不修改玩家位置或服务端距离校验。 */
public final class QuickFreeCameraInteractions {
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";
    private static final Set<Property<?>> V3_PROPERTIES_WITHOUT_DIRECTION = Set.of(
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS,
            BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.DELAY,
            BlockStateProperties.BITES,
            BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16);
    private static boolean sneakPlacementCommandSent;
    private static Input restoredPlayerInput;
    private static boolean cameraFacingApplied;
    private static int cameraFacingDepth;
    private static boolean serverFacingRestorePending;
    private static int serverFacingRestoreDelayTicks;
    private static InteractionHand activeBlockUseHand;
    private static int easyPlaceActionDepth;
    private static float restoredYaw;
    private static float restoredPitch;
    private static float restoredPrevYaw;
    private static float restoredPrevPitch;
    private static float restoredHeadYaw;
    private static float restoredBodyYaw;

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

    public static void beginBlockUseFromFreeCamera(Minecraft client, InteractionHand hand, BlockHitResult hitResult) {
        if (easyPlaceActionDepth > 0) {
            return;
        }
        activeBlockUseHand = hand;
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void beginBlockUseFromFreeCamera(Minecraft client) {
        if (easyPlaceActionDepth > 0) {
            return;
        }
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void beginItemUseFromFreeCamera(Minecraft client) {
        beginCameraFacing(client);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(Minecraft client) {
        endSneakPlacement(client);
        endCameraFacing(client);
        if (!cameraFacingApplied) {
            activeBlockUseHand = null;
        }
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

    public static void beginEasyPlaceAction() {
        easyPlaceActionDepth++;
    }

    public static void endEasyPlaceAction() {
        if (easyPlaceActionDepth > 0) {
            easyPlaceActionDepth--;
        }
    }

    /** Tweakeroo V3 协议把方块方向和属性编码在命中点 X 坐标中。 */
    public static BlockHitResult encodeFreeCameraPlacementState(Minecraft client, BlockHitResult hitResult) {
        if (!cameraFacingApplied || easyPlaceActionDepth > 0
                || activeBlockUseHand == null || client == null || client.player == null) {
            return hitResult;
        }

        ItemStack stack = client.player.getItemInHand(activeBlockUseHand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return hitResult;
        }

        BlockPlaceContext context = new BlockPlaceContext(client.player, activeBlockUseHand, stack, hitResult);
        BlockState targetState = blockItem.getBlock().getStateForPlacement(context);
        if (targetState == null) {
            return hitResult;
        }
        Integer protocolValue = encodeV3PlacementState(targetState);
        if (protocolValue == null) {
            return hitResult;
        }

        BlockPos placementPos = context.getClickedPos();
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

    /** 放置上下文从玩家姿态派生方向；这里不移动本体碰撞箱。 */
    private static void beginCameraFacing(Minecraft client) {
        if (!shouldOverrideCrosshair(client) || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || easyPlaceActionDepth > 0 || client.player == null || client.player.connection == null) {
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
        restoredPrevYaw = player.yRotO;
        restoredPrevPitch = player.xRotO;
        restoredHeadYaw = player.getYHeadRot();
        restoredBodyYaw = player.yBodyRot;
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        serverFacingRestorePending = false;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
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
        client.player.yRotO = restoredPrevYaw;
        client.player.xRotO = restoredPrevPitch;
        client.player.setYHeadRot(restoredHeadYaw);
        client.player.setYBodyRot(restoredBodyYaw);
        serverFacingRestorePending = true;
        serverFacingRestoreDelayTicks = 1;
    }

    private static Integer encodeV3PlacementState(BlockState state) {
        Optional<Property<?>> directionProperty = state.getProperties().stream()
                .filter(property -> property.getValueClass() == Direction.class)
                .findFirst();
        int protocolValue = 0;
        int shift;
        if (directionProperty.isPresent()) {
            protocolValue = ((Direction) state.getValue(directionProperty.get())).get3DDataValue() << 1;
            shift = 4;
        } else {
            shift = 1;
        }

        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        boolean encoded = directionProperty.isPresent();
        for (Property<?> property : properties) {
            if (directionProperty.isPresent() && property.equals(directionProperty.get())) {
                continue;
            }
            if (directionProperty.isEmpty() && !V3_PROPERTIES_WITHOUT_DIRECTION.contains(property)) {
                continue;
            }
            protocolValue |= getSortedValueIndex(state, property) << shift;
            shift += Mth.ceillog2(property.getPossibleValues().size());
            encoded = true;
        }
        return encoded ? protocolValue : null;
    }

    private static <T extends Comparable<T>> int getSortedValueIndex(BlockState state, Property<T> property) {
        List<T> values = new ArrayList<>(property.getPossibleValues());
        values.sort(Comparable::compareTo);
        return values.indexOf(state.getValue(property));
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
