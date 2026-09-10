package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 为 Tweakeroo 灵魂出窍提供相机准星交互；普通放置不读取 Litematica 投影状态。
 */
public final class QuickFreeCameraInteractions {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft-FreeCamera");
    private static final String TWEAKEROO_CAMERA_CLASS = "fi.dy.masa.tweakeroo.util.CameraEntity";
    private static final Set<Property<?>> V3_PROPERTIES_WITHOUT_DIRECTION = Set.of(
            Properties.INVERTED,
            Properties.OPEN,
            Properties.ATTACHMENT,
            Properties.AXIS,
            Properties.BLOCK_HALF,
            Properties.BLOCK_FACE,
            Properties.CHEST_TYPE,
            Properties.COMPARATOR_MODE,
            Properties.DOOR_HINGE,
            Properties.FACING,
            Properties.HOPPER_FACING,
            Properties.HORIZONTAL_FACING,
            Properties.ORIENTATION,
            Properties.RAIL_SHAPE,
            Properties.STRAIGHT_RAIL_SHAPE,
            Properties.SLAB_TYPE,
            Properties.STAIR_SHAPE,
            Properties.DELAY,
            Properties.BITES,
            Properties.NOTE,
            Properties.ROTATION
    );
    private static boolean sneakPlacementCommandSent;
    private static PlayerInput restoredPlayerInput;
    private static boolean entitySneakingReleasePending;
    private static boolean cameraFacingApplied;
    private static int cameraFacingDepth;
    private static boolean serverFacingRestorePending;
    private static int serverFacingRestoreDelayTicks;
    private static Hand activeBlockUseHand;
    private static int easyPlaceActionDepth;
    private static float restoredYaw;
    private static float restoredPitch;
    private static float restoredPrevYaw;
    private static float restoredPrevPitch;
    private static float restoredHeadYaw;
    private static float restoredBodyYaw;

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
        boolean outside = shouldOverrideCrosshair(client)
                && !client.player.canInteractWithBlockAt(position, 1.0);
        if (outside) {
            Entity camera = client.getCameraEntity();
            LOGGER.info(
                    "Free-camera block interaction rejected by body reach: block={}, body={}, camera={}",
                    position,
                    client.player.getPos(),
                    camera != null ? camera.getPos() : null
            );
        }
        return outside;
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

    public static void beginBlockUseFromFreeCamera(MinecraftClient client, Hand hand, BlockHitResult hitResult) {
        if (easyPlaceActionDepth > 0) {
            return;
        }
        activeBlockUseHand = hand;
        beginCameraFacing(client, hitResult);
        beginSneakPlacement(client);
    }

    public static void beginBlockUseFromFreeCamera(MinecraftClient client) {
        if (easyPlaceActionDepth > 0) {
            return;
        }
        beginCameraFacing(client, client != null && client.crosshairTarget instanceof BlockHitResult hit ? hit : null);
        beginSneakPlacement(client);
    }

    public static void beginItemUseFromFreeCamera(MinecraftClient client) {
        beginCameraFacing(client, client != null && client.crosshairTarget instanceof BlockHitResult hit ? hit : null);
        beginSneakPlacement(client);
    }

    public static void endBlockUseFromFreeCamera(MinecraftClient client) {
        endSneakPlacement(client);
        endCameraFacing(client);
        if (!cameraFacingApplied) {
            activeBlockUseHand = null;
        }
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
                new PlayerMoveC2SPacket.LookAndOnGround(restoredYaw, restoredPitch,
                        client.player.isOnGround(), client.player.horizontalCollision)
        );
        LOGGER.info("Free-camera server facing restored: yaw={}, pitch={}", restoredYaw, restoredPitch);
    }

    public static void beginEasyPlaceAction() {
        easyPlaceActionDepth++;
    }

    public static void endEasyPlaceAction() {
        if (easyPlaceActionDepth > 0) {
            easyPlaceActionDepth--;
        }
    }

    public static BlockHitResult encodeFreeCameraPlacementState(
            MinecraftClient client,
            BlockHitResult hitResult
    ) {
        if (!cameraFacingApplied
                || easyPlaceActionDepth > 0
                || activeBlockUseHand == null
                || client == null
                || client.player == null) {
            return hitResult;
        }

        ItemStack stack = client.player.getStackInHand(activeBlockUseHand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return hitResult;
        }

        ItemPlacementContext context = new ItemPlacementContext(
                client.player,
                activeBlockUseHand,
                stack,
                hitResult
        );
        BlockState targetState = blockItem.getBlock().getPlacementState(context);
        if (targetState == null) {
            LOGGER.info("Free-camera placement state unavailable: item={}, hit={}", stack.getItem(), hitResult);
            return hitResult;
        }

        Integer protocolValue = encodeV3PlacementState(targetState);
        if (protocolValue == null) {
            LOGGER.info("Free-camera vanilla placement state needs no protocol encoding: state={}", targetState);
            return hitResult;
        }

        BlockPos placementPos = context.getBlockPos();
        Vec3d encodedPos = new Vec3d(
                placementPos.getX() + 2.25D + protocolValue,
                hitResult.getPos().y,
                hitResult.getPos().z
        );
        LOGGER.info(
                "Free-camera placement encoded: hand={}, state={}, protocol={}, clicked={}, placement={}, side={}, cameraYaw={}, cameraPitch={}",
                activeBlockUseHand,
                targetState,
                protocolValue,
                hitResult.getBlockPos(),
                placementPos,
                hitResult.getSide(),
                client.getCameraEntity() != null ? client.getCameraEntity().getYaw() : null,
                client.getCameraEntity() != null ? client.getCameraEntity().getPitch() : null
        );
        return new BlockHitResult(
                encodedPos,
                hitResult.getSide(),
                hitResult.getBlockPos(),
                hitResult.isInsideBlock()
        );
    }

    public static void logBlockUseResult(BlockHitResult hitResult, ActionResult result) {
        if (cameraFacingApplied) {
            LOGGER.info("Free-camera interactBlock returned: result={}, hit={}", result, hitResult);
        }
    }

    private static void beginSneakPlacement(MinecraftClient client) {
        if (sneakPlacementCommandSent
                || !shouldSneakPlaceFromFreeCamera(client)
                || client.player == null
                || client.player.networkHandler == null
                || client.player.input == null) {
            return;
        }

        // 1.21.6+ 删除了 ClientCommandC2SPacket 的 PRESS_SHIFT_KEY，潜行改走 PlayerInputC2SPacket。
        restoredPlayerInput = client.player.input.playerInput;
        PlayerInput sneaking = new PlayerInput(
                restoredPlayerInput.forward(),
                restoredPlayerInput.backward(),
                restoredPlayerInput.left(),
                restoredPlayerInput.right(),
                restoredPlayerInput.jump(),
                true,
                restoredPlayerInput.sprint()
        );
        client.player.networkHandler.sendPacket(new PlayerInputC2SPacket(sneaking));
        sneakPlacementCommandSent = true;
    }

    private static void endSneakPlacement(MinecraftClient client) {
        if (!sneakPlacementCommandSent) {
            return;
        }

        sneakPlacementCommandSent = false;
        PlayerInput restored = restoredPlayerInput;
        restoredPlayerInput = null;
        if (client == null || client.player == null || client.player.networkHandler == null || restored == null) {
            return;
        }

        client.player.networkHandler.sendPacket(new PlayerInputC2SPacket(restored));
    }

    /**
     * 原版放置上下文会从玩家姿态派生水平朝向、六向顺序和 16 段旋转。
     * 这里只同步姿态，不移动本体碰撞箱；方向状态由精准放置 V3 编码稳定传给服务端。
     * 1.21.2+ 的 LookAndOnGround 必须带上 horizontalCollision。
     */
    private static void beginCameraFacing(MinecraftClient client, BlockHitResult hitResult) {
        if (!shouldOverrideCrosshair(client)
                || !QuickCraftConfigs.areFreeCameraBlockInteractionsEnabled()
                || easyPlaceActionDepth > 0
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
        restoredPrevYaw = player.prevYaw;
        restoredPrevPitch = player.prevPitch;
        restoredHeadYaw = player.getHeadYaw();
        restoredBodyYaw = player.getBodyYaw();
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();

        serverFacingRestorePending = false;
        player.setAngles(cameraYaw, cameraPitch);
        player.setHeadYaw(cameraYaw);
        player.setBodyYaw(cameraYaw);
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(cameraYaw, cameraPitch, player.isOnGround(), player.horizontalCollision)
        );
        cameraFacingApplied = true;
        cameraFacingDepth = 1;
        LOGGER.info(
                "Free-camera placement begin: hand={}, hit={}, body={}, camera={}, bodyYaw={}, bodyPitch={}, cameraYaw={}, cameraPitch={}",
                activeBlockUseHand,
                hitResult,
                player.getPos(),
                camera.getPos(),
                restoredYaw,
                restoredPitch,
                cameraYaw,
                cameraPitch
        );
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
        player.prevYaw = restoredPrevYaw;
        player.prevPitch = restoredPrevPitch;
        player.setHeadYaw(restoredHeadYaw);
        player.setBodyYaw(restoredBodyYaw);
        serverFacingRestorePending = true;
        serverFacingRestoreDelayTicks = 1;
    }

    private static Integer encodeV3PlacementState(BlockState state) {
        Optional<DirectionProperty> directionProperty = state.getProperties().stream()
                .filter(DirectionProperty.class::isInstance)
                .map(DirectionProperty.class::cast)
                .findFirst();
        int protocolValue = 0;
        int shift;

        if (directionProperty.isPresent()) {
            Direction facing = state.get(directionProperty.get());
            protocolValue = facing.getId() << 1;
            shift = 4;
        } else {
            shift = 1;
        }

        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        boolean encodedProperty = directionProperty.isPresent();
        for (Property<?> property : properties) {
            if (directionProperty.isPresent()) {
                if (property.equals(directionProperty.get())) {
                    continue;
                }
            } else if (!V3_PROPERTIES_WITHOUT_DIRECTION.contains(property)) {
                continue;
            }

            int valueIndex = getSortedValueIndex(state, property);
            int requiredBits = MathHelper.ceilLog2(property.getValues().size());
            protocolValue |= valueIndex << shift;
            shift += requiredBits;
            encodedProperty = true;
        }

        return encodedProperty ? protocolValue : null;
    }

    private static <T extends Comparable<T>> int getSortedValueIndex(BlockState state, Property<T> property) {
        List<T> values = new ArrayList<>(property.getValues());
        values.sort(Comparable::compareTo);
        return values.indexOf(state.get(property));
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
