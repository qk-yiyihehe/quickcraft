package com.yiyihehe.quickcraft;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.Property;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * 1.21.8+ 自由相机玩家输入数据包改为 PlayerInputC2SPacket。
 */
public final class QuickFreeCameraAccess {
    private static PlayerInput restoredPlayerInput;

    private QuickFreeCameraAccess() {
    }

    public static void sendLookPacket(ClientPlayerEntity player, float yaw, float pitch) {
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround(), player.horizontalCollision)
        );
    }

    public static float getPrevYaw(ClientPlayerEntity player) {
        return player.lastYaw;
    }

    public static float getPrevPitch(ClientPlayerEntity player) {
        return player.lastPitch;
    }

    public static void setPrevRotation(ClientPlayerEntity player, float prevYaw, float prevPitch) {
        player.lastYaw = prevYaw;
        player.lastPitch = prevPitch;
    }

    public static boolean canSneakPlace(MinecraftClient client) {
        return client.player != null && client.player.networkHandler != null && client.player.input != null;
    }

    public static void beginSneakPlacement(MinecraftClient client) {
        if (client.player == null || client.player.input == null || client.player.networkHandler == null) {
            return;
        }
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
    }

    public static void endSneakPlacement(MinecraftClient client) {
        PlayerInput restored = restoredPlayerInput;
        restoredPlayerInput = null;
        if (client == null || client.player == null || client.player.networkHandler == null || restored == null) {
            return;
        }
        client.player.networkHandler.sendPacket(new PlayerInputC2SPacket(restored));
    }

    public static void endEntitySneaking(MinecraftClient client) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }
        if (client.player.input != null) {
            client.player.networkHandler.sendPacket(new PlayerInputC2SPacket(client.player.input.playerInput));
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static Property<Direction> getDirectionProperty(BlockState state) {
        return (Property<Direction>) state.getProperties().stream()
                .filter(property -> property.getType() == Direction.class)
                .findFirst()
                .orElse(null);
    }

    public static int getDirectionIndex(Direction direction) {
        return direction.getIndex();
    }
}
