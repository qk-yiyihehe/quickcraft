package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

final class QuickLitematicaEntityPlacementAccess {
    private QuickLitematicaEntityPlacementAccess() {
    }

    public static Entity createEntity(EntityType<?> type, ServerWorld world) {
        return type.create(world, SpawnReason.LOAD);
    }

    public static void readEntityData(Entity entity, ServerWorld world, NbtCompound nbt) {
        entity.readData(NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), nbt));
    }

    public static NbtCompound writeEntityNbt(Entity entity) {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, entity.getRegistryManager());
        entity.saveSelfData(view);
        return view.getNbt();
    }

    public static boolean startRiding(Entity passenger, Entity vehicle) {
        return passenger.startRiding(vehicle, true, true);
    }

    public static Vec3d getEntityPos(Entity entity) {
        return entity.getEntityPos();
    }

    public static World getEntityWorld(Entity entity) {
        return entity.getEntityWorld();
    }

    public static ServerWorld getPlayerServerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    public static boolean canModifyAt(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        return world.canEntityModifyAt(player, pos);
    }

    public static List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.getMainStacks();
    }

    public static ItemStack parseServerItem(ServerWorld world, NbtCompound nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(world.getRegistryManager().getOps(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    public static Optional<ItemStack> decodeItemStack(MinecraftClient client, NbtCompound nbt) {
        if (client == null || client.world == null) {
            return Optional.empty();
        }
        return ItemStack.CODEC.parse(client.world.getRegistryManager().getOps(NbtOps.INSTANCE), nbt).result();
    }

    public static NbtCompound compoundAt(NbtList list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    public static String stringValue(NbtCompound nbt, String key) {
        return nbt.getString(key, "");
    }

    public static boolean booleanValue(NbtCompound nbt, String key) {
        return nbt.getBoolean(key, false);
    }

    public static int intValue(NbtCompound nbt, String key) {
        return nbt.getInt(key, 0);
    }

    public static byte byteValue(NbtCompound nbt, String key) {
        return nbt.getByte(key, (byte) 0);
    }

    public static double doubleAt(NbtList list, int index, double fallback) {
        return list.getDouble(index, fallback);
    }

    public static float floatAt(NbtList list, int index, float fallback) {
        return list.getFloat(index, fallback);
    }

    public static NbtList compoundListValue(NbtCompound nbt, String key) {
        return nbt.getListOrEmpty(key);
    }

    public static NbtList floatListValue(NbtCompound nbt, String key) {
        return nbt.getListOrEmpty(key);
    }

    public static NbtList doubleListValue(NbtCompound nbt, String key) {
        return nbt.getListOrEmpty(key);
    }

    public static NbtCompound compoundValue(NbtCompound nbt, String key) {
        return nbt.getCompoundOrEmpty(key);
    }

    public static Direction directionValue(NbtCompound nbt, String key) {
        return Direction.byIndex(byteValue(nbt, key));
    }

    public static byte directionIndex(Direction direction) {
        return (byte) direction.getIndex();
    }

    public static boolean normalizeEntityTreeIds(NbtCompound nbt, int depth) {
        if (depth > 8) {
            return false;
        }
        Identifier id = Identifier.tryParse(stringValue(nbt, "id"));
        if (id == null) {
            return false;
        }
        if (!Registries.ENTITY_TYPE.containsId(id)) {
            id = getModernBoatId(id, nbt);
            if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
                return false;
            }
            nbt.putString("id", id.toString());
        }

        NbtList passengers = compoundListValue(nbt, "Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            if (!normalizeEntityTreeIds(compoundAt(passengers, index), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static Identifier getModernBoatId(Identifier id, NbtCompound nbt) {
        if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return null;
        }
        boolean chestBoat = id.getPath().equals("chest_boat");
        if (!chestBoat && !id.getPath().equals("boat")) {
            return null;
        }
        String path = getSplitBoatPath(stringValue(nbt, "Type"), chestBoat);
        return path == null ? null : Identifier.ofVanilla(path);
    }

    public static String getSplitBoatPath(String type, boolean chestBoat) {
        String prefix = switch (type) {
            case "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "mangrove",
                    "pale_oak", "bamboo", "oak" -> type;
            case "" -> "oak";
            default -> null;
        };
        if (prefix == null) {
            return null;
        }
        return prefix.equals("bamboo")
                ? (chestBoat ? "bamboo_chest_raft" : "bamboo_raft")
                : prefix + (chestBoat ? "_chest_boat" : "_boat");
    }

    public static Item getBoatItem(String type, boolean chestBoat) {
        String path = getSplitBoatPath(type, chestBoat);
        return path == null ? null : getSplitBoatItem(Identifier.ofVanilla(path));
    }

    public static Item getSplitBoatItem(Identifier entityId) {
        return isSplitBoatPath(entityId.getPath()) && Registries.ITEM.containsId(entityId)
                ? Registries.ITEM.get(entityId)
                : null;
    }

    public static boolean isSplitChestBoatOrRaft(String path) {
        return path.endsWith("_chest_boat") || path.endsWith("_chest_raft");
    }

    public static boolean isSplitBoatPath(String path) {
        return path.endsWith("_boat") || path.endsWith("_chest_boat")
                || path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    public static NbtCompound getSchematicEntityNbt(LitematicaSchematic.EntityInfo entity) {
        return QuickLitematicaDataCompat.entityNbt(entity).copy();
    }

    public static Vec3d getSchematicEntityPos(LitematicaSchematic.EntityInfo entity) {
        return QuickLitematicaDataCompat.entityPos(entity);
    }
    public static EntityType<?> getEntityType(Identifier id) {
        return Registries.ENTITY_TYPE.get(id);
    }

    public static Item getItem(Identifier id) {
        return Registries.ITEM.get(id);
    }
}
