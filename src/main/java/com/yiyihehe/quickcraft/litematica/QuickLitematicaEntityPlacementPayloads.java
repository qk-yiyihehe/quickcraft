package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 轻松放置实体的跨端线协议。
 * 服务端扩展必须使用相同的字段顺序；所有放置内容仍必须由服务端重新校验。
 */
public final class QuickLitematicaEntityPlacementPayloads {
    public static final int PROTOCOL_VERSION = 2;
    public static final int CLIENT_FEATURES = 0;
    public static final int MAX_CLIENT_NBT_BYTES = 262_144;

    private QuickLitematicaEntityPlacementPayloads() {
    }

    public record HelloPayload(int version, int features, int maxNbtBytes) implements CustomPacketPayload {
        public static final Type<HelloPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("quickcraft", "entity_place_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeVarInt(payload.version);
                    buffer.writeVarInt(payload.features);
                    buffer.writeVarInt(payload.maxNbtBytes);
                },
                buffer -> new HelloPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt())
        );

        @Override
        public Type<HelloPayload> type() {
            return ID;
        }
    }

    public record CapabilityPayload(
            int version,
            boolean enabled,
            double reach,
            int maxNbtBytes,
            int features,
            String sessionToken
    ) implements CustomPacketPayload {
        public static final Type<CapabilityPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("quickcraft", "entity_place_capability"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CapabilityPayload> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeVarInt(payload.version);
                    buffer.writeBoolean(payload.enabled);
                    buffer.writeDouble(payload.reach);
                    buffer.writeVarInt(payload.maxNbtBytes);
                    buffer.writeVarInt(payload.features);
                    buffer.writeUtf(payload.sessionToken, 128);
                },
                buffer -> new CapabilityPayload(
                        buffer.readVarInt(),
                        buffer.readBoolean(),
                        buffer.readDouble(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readUtf(128)
                )
        );

        @Override
        public Type<CapabilityPayload> type() {
            return ID;
        }
    }

    public record RequestPayload(
            String sessionToken,
            long nonce,
            Identifier dimension,
            Vec3 target,
            Identifier entityType,
            String region,
            int entityIndex,
            float yaw,
            float pitch,
            Vec3 velocity,
            boolean creativeMaterialBypass,
            CompoundTag entityNbt
    ) implements CustomPacketPayload {
        public static final Type<RequestPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("quickcraft", "entity_place_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.sessionToken, 128);
                    buffer.writeLong(payload.nonce);
                    buffer.writeIdentifier(payload.dimension);
                    writeVec3(buffer, payload.target);
                    buffer.writeIdentifier(payload.entityType);
                    buffer.writeUtf(payload.region, 256);
                    buffer.writeVarInt(payload.entityIndex);
                    buffer.writeFloat(payload.yaw);
                    buffer.writeFloat(payload.pitch);
                    writeVec3(buffer, payload.velocity);
                    buffer.writeBoolean(payload.creativeMaterialBypass);
                    buffer.writeNbt(payload.entityNbt);
                },
                buffer -> new RequestPayload(
                        buffer.readUtf(128),
                        buffer.readLong(),
                        buffer.readIdentifier(),
                        readVec3(buffer),
                        buffer.readIdentifier(),
                        buffer.readUtf(256),
                        buffer.readVarInt(),
                        buffer.readFloat(),
                        buffer.readFloat(),
                        readVec3(buffer),
                        buffer.readBoolean(),
                        buffer.readNbt()
                )
        );

        @Override
        public Type<RequestPayload> type() {
            return ID;
        }
    }

    public record ResultPayload(long nonce, String status, String entityUuid, String messageKey) implements CustomPacketPayload {
        public static final Type<ResultPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("quickcraft", "entity_place_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResultPayload> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeLong(payload.nonce);
                    buffer.writeUtf(payload.status, 64);
                    buffer.writeUtf(payload.entityUuid, 64);
                    buffer.writeUtf(payload.messageKey, 256);
                },
                buffer -> new ResultPayload(
                        buffer.readLong(),
                        buffer.readUtf(64),
                        buffer.readUtf(64),
                        buffer.readUtf(256)
                )
        );

        @Override
        public Type<ResultPayload> type() {
            return ID;
        }
    }

    private static void writeVec3(RegistryFriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }

    private static Vec3 readVec3(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
