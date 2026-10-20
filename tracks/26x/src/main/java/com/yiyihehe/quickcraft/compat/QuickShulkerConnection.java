package com.yiyihehe.quickcraft.compat;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Constructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** QuickShulker 可选通信桥接；反射避免将它变成硬依赖。功能开关由调用方决定。 */
public final class QuickShulkerConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickShulkerConnection.class);
    private static boolean failureLogged;
    private static final Identifier BUNDLE_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "quick_bundleheld_packet");
    private static final Identifier OPEN_PACKET = Identifier.fromNamespaceAndPath("quickshulker", "open_shulker_packet");

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
        if (!canOpen() || OpenPacket.CONSTRUCTOR == null) {
            return false;
        }
        try {
            ClientPlayNetworking.send(OpenPacket.CONSTRUCTOR.newInstance(slotId));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(failure);
            return false;
        }
    }

    // 只有实际发送时才绑定可选模组；构造器查找失败不影响其他容器功能。
    private static final class OpenPacket {
        private static final Constructor<? extends CustomPacketPayload> CONSTRUCTOR = findConstructor();

        private static Constructor<? extends CustomPacketPayload> findConstructor() {
            try {
                return Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket")
                        .asSubclass(CustomPacketPayload.class).getConstructor(int.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                logFailure(failure);
                return null;
            }
        }
    }

    private static void logFailure(Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("QuickShulker open packet bridge failed; automatic opening is unavailable", failure);
        }
    }
}
