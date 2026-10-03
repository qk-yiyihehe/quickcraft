package com.yiyihehe.quickcraft.compat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** QuickShulker 可选通信桥接；反射避免将它变成硬依赖。功能开关由调用方决定。 */
public final class QuickShulkerConnection {
    private static final Identifier BUNDLE_PACKET = Identifier.of("quickshulker", "quick_bundleheld_packet");
    private static final Identifier OPEN_PACKET = Identifier.of("quickshulker", "open_shulker_packet");

    private QuickShulkerConnection() {
    }

    public static boolean canBundle() {
        return canSend(BUNDLE_PACKET);
    }

    public static boolean canOpen() {
        return canSend(OPEN_PACKET);
    }

    private static boolean canSend(Identifier channel) {
        if (!FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(channel);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean sendOpen(int slotId) {
        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            Object packet = packetClass.getConstructor(int.class).newInstance(slotId);
            ClientPlayNetworking.send((CustomPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return false;
        }
    }
}
