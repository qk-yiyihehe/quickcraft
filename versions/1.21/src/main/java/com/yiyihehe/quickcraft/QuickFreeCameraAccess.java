package com.yiyihehe.quickcraft;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * 1.21 自由相机网络包、旋转同步、潜行与 Carpet 放置协议适配。
 */
public final class QuickFreeCameraAccess {
    private QuickFreeCameraAccess() {
    }

    public static void sendLookPacket(ClientPlayerEntity player, float yaw, float pitch) {
        player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround())
        );
    }

    public static float getPrevYaw(ClientPlayerEntity player) {
        return player.prevYaw;
    }

    public static float getPrevPitch(ClientPlayerEntity player) {
        return player.prevPitch;
    }

    public static void setPrevRotation(ClientPlayerEntity player, float prevYaw, float prevPitch) {
        player.prevYaw = prevYaw;
        player.prevPitch = prevPitch;
    }

    public static boolean canSneakPlace(MinecraftClient client) {
        return client.player != null && client.player.networkHandler != null;
    }

    public static void beginSneakPlacement(MinecraftClient client) {
        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY)
        );
    }

    public static void endSneakPlacement(MinecraftClient client) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }
        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)
        );
    }

    public static void endEntitySneaking(MinecraftClient client) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }
        client.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)
        );
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static Property<Direction> getDirectionProperty(BlockState state) {
        return (Property<Direction>) state.getProperties().stream()
                .filter(DirectionProperty.class::isInstance)
                .findFirst()
                .orElse(null);
    }

    public static int getDirectionIndex(Direction direction) {
        return direction.getId();
    }
}
