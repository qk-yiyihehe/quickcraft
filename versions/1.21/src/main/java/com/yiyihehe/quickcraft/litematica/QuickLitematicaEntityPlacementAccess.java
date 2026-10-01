package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
        return type.create(world);
    }

    public static void readEntityData(Entity entity, ServerWorld world, NbtCompound nbt) {
        entity.readNbt(nbt);
    }

    public static NbtCompound writeEntityNbt(Entity entity) {
        return entity.writeNbt(new NbtCompound());
    }

    public static boolean startRiding(Entity passenger, Entity vehicle) {
        return passenger.startRiding(vehicle, true);
    }

    public static Vec3d getEntityPos(Entity entity) {
        return entity.getPos();
    }

    public static World getEntityWorld(Entity entity) {
        return entity.getWorld();
    }

    public static ServerWorld getPlayerServerWorld(ServerPlayerEntity player) {
        return player.getServerWorld();
    }

    public static boolean canModifyAt(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        return world.canPlayerModifyAt(player, pos);
    }

    public static List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.main;
    }

    public static ItemStack parseServerItem(ServerWorld world, NbtCompound nbt) {
        return ItemStack.fromNbt(world.getRegistryManager(), nbt).orElse(ItemStack.EMPTY);
    }

    public static Optional<ItemStack> decodeItemStack(MinecraftClient client, NbtCompound nbt) {
        if (client == null || client.world == null) {
            return Optional.empty();
        }
        return ItemStack.fromNbt(client.world.getRegistryManager(), nbt);
    }

    public static NbtCompound compoundAt(NbtList list, int index) {
        return list.getCompound(index);
    }

    public static String stringValue(NbtCompound nbt, String key) {
        return nbt.getString(key);
    }

    public static boolean booleanValue(NbtCompound nbt, String key) {
        return nbt.getBoolean(key);
    }

    public static int intValue(NbtCompound nbt, String key) {
        return nbt.getInt(key);
    }

    public static byte byteValue(NbtCompound nbt, String key) {
        return nbt.getByte(key);
    }

    public static double doubleAt(NbtList list, int index, double fallback) {
        return list.getDouble(index);
    }

    public static float floatAt(NbtList list, int index, float fallback) {
        return list.getFloat(index);
    }

    public static NbtList compoundListValue(NbtCompound nbt, String key) {
        return nbt.getList(key, 10);
    }

    public static NbtList floatListValue(NbtCompound nbt, String key) {
        return nbt.getList(key, 5);
    }

    public static NbtList doubleListValue(NbtCompound nbt, String key) {
        return nbt.getList(key, 6);
    }

    public static NbtCompound compoundValue(NbtCompound nbt, String key) {
        return nbt.getCompound(key);
    }

    public static Direction directionValue(NbtCompound nbt, String key) {
        return Direction.byId(byteValue(nbt, key));
    }

    public static byte directionIndex(Direction direction) {
        return (byte) direction.getId();
    }

    public static boolean normalizeEntityTreeIds(NbtCompound nbt, int depth) {
        return true;
    }

    public static Item getBoatItem(String type, boolean chestBoat) {
        return switch (type) {
            case "spruce" -> chestBoat ? Items.SPRUCE_CHEST_BOAT : Items.SPRUCE_BOAT;
            case "birch" -> chestBoat ? Items.BIRCH_CHEST_BOAT : Items.BIRCH_BOAT;
            case "jungle" -> chestBoat ? Items.JUNGLE_CHEST_BOAT : Items.JUNGLE_BOAT;
            case "acacia" -> chestBoat ? Items.ACACIA_CHEST_BOAT : Items.ACACIA_BOAT;
            case "cherry" -> chestBoat ? Items.CHERRY_CHEST_BOAT : Items.CHERRY_BOAT;
            case "dark_oak" -> chestBoat ? Items.DARK_OAK_CHEST_BOAT : Items.DARK_OAK_BOAT;
            case "mangrove" -> chestBoat ? Items.MANGROVE_CHEST_BOAT : Items.MANGROVE_BOAT;
            case "bamboo" -> chestBoat ? Items.BAMBOO_CHEST_RAFT : Items.BAMBOO_RAFT;
            case "oak", "" -> chestBoat ? Items.OAK_CHEST_BOAT : Items.OAK_BOAT;
            default -> null;
        };
    }

    public static Item getSplitBoatItem(Identifier entityId) {
        return null;
    }

    public static boolean isSplitChestBoatOrRaft(String path) {
        return false;
    }

    public static boolean isSplitBoatPath(String path) {
        return path.endsWith("_boat") || path.endsWith("_chest_boat")
                || path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    public static NbtCompound getSchematicEntityNbt(LitematicaSchematic.EntityInfo entity) {
        return entity.nbt.copy();
    }

    public static Vec3d getSchematicEntityPos(LitematicaSchematic.EntityInfo entity) {
        return entity.posVec;
    }
    public static EntityType<?> getEntityType(Identifier id) {
        return Registries.ENTITY_TYPE.get(id);
    }

    public static Item getItem(Identifier id) {
        return Registries.ITEM.get(id);
    }
}
