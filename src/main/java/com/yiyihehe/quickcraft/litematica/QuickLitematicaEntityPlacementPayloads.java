package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

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

    public record HelloPayload(int version, int features, int maxNbtBytes) implements CustomPayload {
        public static final Id<HelloPayload> ID = new Id<>(Identifier.of("quickcraft", "entity_place_hello"));
        public static final PacketCodec<PacketByteBuf, HelloPayload> CODEC = CustomPayload.codecOf(
                (payload, buffer) -> {
                    buffer.writeVarInt(payload.version);
                    buffer.writeVarInt(payload.features);
                    buffer.writeVarInt(payload.maxNbtBytes);
                },
                buffer -> new HelloPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt())
        );

        @Override
        public Id<HelloPayload> getId() {
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
    ) implements CustomPayload {
        public static final Id<CapabilityPayload> ID = new Id<>(Identifier.of("quickcraft", "entity_place_capability"));
        public static final PacketCodec<PacketByteBuf, CapabilityPayload> CODEC = CustomPayload.codecOf(
                (payload, buffer) -> {
                    buffer.writeVarInt(payload.version);
                    buffer.writeBoolean(payload.enabled);
                    buffer.writeDouble(payload.reach);
                    buffer.writeVarInt(payload.maxNbtBytes);
                    buffer.writeVarInt(payload.features);
                    buffer.writeString(payload.sessionToken, 128);
                },
                buffer -> new CapabilityPayload(
                        buffer.readVarInt(),
                        buffer.readBoolean(),
                        buffer.readDouble(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readString(128)
                )
        );

        @Override
        public Id<CapabilityPayload> getId() {
            return ID;
        }
    }

    public record RequestPayload(
            String sessionToken,
            long nonce,
            Identifier dimension,
            Vec3d target,
            Identifier entityType,
            String region,
            int entityIndex,
            float yaw,
            float pitch,
            Vec3d velocity,
            boolean creativeMaterialBypass,
            NbtCompound entityNbt
    ) implements CustomPayload {
        public static final Id<RequestPayload> ID = new Id<>(Identifier.of("quickcraft", "entity_place_request"));
        public static final PacketCodec<PacketByteBuf, RequestPayload> CODEC = CustomPayload.codecOf(
                (payload, buffer) -> {
                    buffer.writeString(payload.sessionToken, 128);
                    buffer.writeLong(payload.nonce);
                    buffer.writeIdentifier(payload.dimension);
                    writeVec3d(buffer, payload.target);
                    buffer.writeIdentifier(payload.entityType);
                    buffer.writeString(payload.region, 256);
                    buffer.writeVarInt(payload.entityIndex);
                    buffer.writeFloat(payload.yaw);
                    buffer.writeFloat(payload.pitch);
                    writeVec3d(buffer, payload.velocity);
                    buffer.writeBoolean(payload.creativeMaterialBypass);
                    buffer.writeNbt(payload.entityNbt);
                },
                buffer -> new RequestPayload(
                        buffer.readString(128),
                        buffer.readLong(),
                        buffer.readIdentifier(),
                        readVec3d(buffer),
                        buffer.readIdentifier(),
                        buffer.readString(256),
                        buffer.readVarInt(),
                        buffer.readFloat(),
                        buffer.readFloat(),
                        readVec3d(buffer),
                        buffer.readBoolean(),
                        buffer.readNbt()
                )
        );

        @Override
        public Id<RequestPayload> getId() {
            return ID;
        }
    }

    public record ResultPayload(long nonce, String status, String entityUuid, String messageKey) implements CustomPayload {
        public static final Id<ResultPayload> ID = new Id<>(Identifier.of("quickcraft", "entity_place_result"));
        public static final PacketCodec<PacketByteBuf, ResultPayload> CODEC = CustomPayload.codecOf(
                (payload, buffer) -> {
                    buffer.writeLong(payload.nonce);
                    buffer.writeString(payload.status, 64);
                    buffer.writeString(payload.entityUuid, 64);
                    buffer.writeString(payload.messageKey, 256);
                },
                buffer -> new ResultPayload(
                        buffer.readLong(),
                        buffer.readString(64),
                        buffer.readString(64),
                        buffer.readString(256)
                )
        );

        @Override
        public Id<ResultPayload> getId() {
            return ID;
        }
    }

    private static void writeVec3d(PacketByteBuf buffer, Vec3d value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }

    private static Vec3d readVec3d(PacketByteBuf buffer) {
        return new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
