package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.storage.NbtReadView;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单人/局域网综合服务器上的实体放置协议端点。
 * 检测到 FGA 已注册同一协议时不会初始化，避免重复握手和重复放置。
 */
public final class QuickLitematicaEntityPlacementServer {
    private static final double DEFAULT_REACH = 4.5D;
    private static final double MAX_REACH = 64.0D;
    private static final double MAX_SPEED = 4.0D;
    private static final int MAX_ENTITY_NBT_BYTES = 262_144;
    private static final int MAX_ENTITY_TREE_DEPTH = 8;
    private static final int MAX_ENTITY_TREE_SIZE = 16;
    private static final int REQUEST_COOLDOWN_TICKS = 2;
    private static final int MAX_NONCES = 64;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private QuickLitematicaEntityPlacementServer() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(
                QuickLitematicaEntityPlacementPayloads.HelloPayload.ID,
                (payload, context) -> context.server().execute(
                        () -> handleHello(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(
                QuickLitematicaEntityPlacementPayloads.RequestPayload.ID,
                (payload, context) -> context.server().execute(
                        () -> handleRequest(context.player(), payload))
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.player));
    }

    public static String ensureSession(ServerPlayerEntity player) {
        if (player == null) {
            return "";
        }
        Session session = SESSIONS.get(player.getUuid());
        if (session != null && session.enabled) {
            return session.token;
        }
        String token = UUID.randomUUID().toString();
        SESSIONS.put(player.getUuid(), new Session(token, true));
        return token;
    }

    public static void handleHello(ServerPlayerEntity player, QuickLitematicaEntityPlacementPayloads.HelloPayload payload) {
        if (player == null) {
            return;
        }
        boolean compatible = payload.version() == QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION;
        String token = UUID.randomUUID().toString();
        Session session = new Session(token, compatible);
        SESSIONS.put(player.getUuid(), session);
        ServerPlayNetworking.send(player, new QuickLitematicaEntityPlacementPayloads.CapabilityPayload(
                QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION,
                session.enabled,
                placementReach(player),
                Math.min(MAX_ENTITY_NBT_BYTES, Math.max(0, payload.maxNbtBytes())),
                0,
                token
        ));
    }

    public static void handleRequest(ServerPlayerEntity player, QuickLitematicaEntityPlacementPayloads.RequestPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        Session session = SESSIONS.get(player.getUuid());
        if (session == null || !session.token.equals(payload.sessionToken())) {
            sendResult(player, payload.nonce(), "DISABLED", "");
            return;
        }
        ServerWorld world = player.getWorld();
        long tick = world.getTime();
        if (!session.enabled || !QuickCraftConfigs.isEasyPlaceEntitiesEnabled()) {
            sendResult(player, payload.nonce(), "DISABLED", "");
            return;
        }
        if (session.nonces.contains(payload.nonce())) {
            sendResult(player, payload.nonce(), "REPLAYED_REQUEST", "");
            return;
        }
        if (session.nonces.size() >= MAX_NONCES) {
            session.nonces.removeFirst();
        }
        session.nonces.add(payload.nonce());
        if (session.lastRequestTick != Long.MIN_VALUE
                && tick - session.lastRequestTick < REQUEST_COOLDOWN_TICKS) {
            sendResult(player, payload.nonce(), "RATE_LIMITED", "");
            return;
        }
        session.lastRequestTick = tick;
        if (!dimensionId(world).equals(payload.dimension().toString())) {
            sendResult(player, payload.nonce(), "OUT_OF_REACH", "");
            return;
        }
        double reach = placementReach(player);
        if (!isFinite(payload.target()) || player.getEyePos().squaredDistanceTo(payload.target()) > reach * reach) {
            sendResult(player, payload.nonce(), "OUT_OF_REACH", "");
            return;
        }
        if (!isFinite(payload.velocity()) || payload.velocity().lengthSquared() > MAX_SPEED * MAX_SPEED) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        BlockPos targetPos = BlockPos.ofFloored(payload.target());
        if (!world.isChunkLoaded(targetPos)) {
            sendResult(player, payload.nonce(), "WORLD_RULE_BLOCKED", "");
            return;
        }
        if (!world.canEntityModifyAt(player, targetPos)) {
            sendResult(player, payload.nonce(), "PERMISSION_DENIED", "");
            return;
        }

        NbtCompound requested = payload.entityNbt();
        int[] entityCount = {0};
        if (requested == null || requested.getSizeInBytes() > MAX_ENTITY_NBT_BYTES
                || payload.entityType() == null
                || !payload.entityType().toString().equals(readEntityId(requested))
                || !validateEntityTree(requested, 0, entityCount)) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }

        List<ItemStack> required = materialsForTree(requested, world, player);
        if (required == null) {
            sendResult(player, payload.nonce(), "UNSUPPORTED_ENTITY", "");
            return;
        }
        boolean creativeMaterialBypass = payload.creativeMaterialBypass() && player.isCreative();
        if (!creativeMaterialBypass && !hasMaterials(player, required)) {
            sendResult(player, payload.nonce(), "NO_MATERIAL", "");
            return;
        }

        Entity root;
        try {
            root = createEntityTree(world, requested, payload.target(), payload.yaw(), payload.pitch(),
                    payload.velocity(), true, 0);
        } catch (RuntimeException ignored) {
            root = null;
        }
        if (root == null) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        if (!isTreeWithinReach(player, root, reach)) {
            discardTree(root);
            sendResult(player, payload.nonce(), "OUT_OF_REACH", "");
            return;
        }
        if (!isTreePlacementAllowed(world, player, root)) {
            discardTree(root);
            sendResult(player, payload.nonce(), "PERMISSION_DENIED", "");
            return;
        }
        if (!isTreeInsideWorldBorder(world, root) || !canTreeStayAttached(root)) {
            discardTree(root);
            sendResult(player, payload.nonce(), "COLLISION", "");
            return;
        }

        List<ItemStack> snapshot = creativeMaterialBypass
                ? List.of()
                : inventoryItems(player).stream().map(ItemStack::copy).toList();
        if (!creativeMaterialBypass) {
            consumeMaterials(player, required);
        }
        boolean added;
        try {
            added = world.spawnNewEntityAndPassengers(root);
        } catch (RuntimeException ignored) {
            added = false;
        }
        if (!added) {
            discardTree(root);
            if (!creativeMaterialBypass) {
                restoreInventory(player, snapshot);
            }
            sendResult(player, payload.nonce(), "INTERNAL_ERROR", "");
            return;
        }
        sendResult(player, payload.nonce(), "SUCCESS", root.getUuid().toString());
    }

    public static void clear(ServerPlayerEntity player) {
        if (player != null) {
            SESSIONS.remove(player.getUuid());
        }
    }

    private static void sendResult(ServerPlayerEntity player, long nonce, String status, String uuid) {
        ServerPlayNetworking.send(player, new QuickLitematicaEntityPlacementPayloads.ResultPayload(
                nonce, status, uuid, ""));
    }

    private static double placementReach(ServerPlayerEntity player) {
        // 生存实体交互距离只有 3 格，轻松放置按方块距离来，避免准星能扫到却放不下。
        double reach = player == null ? DEFAULT_REACH : player.getBlockInteractionRange();
        return Math.max(DEFAULT_REACH, Math.max(0.0D, Math.min(MAX_REACH, reach)));
    }

    private static String readEntityId(NbtCompound nbt) {
        Identifier parsed = Identifier.tryParse(stringValue(nbt, "id"));
        return parsed == null ? null : parsed.toString();
    }

    private static boolean validateEntityTree(NbtCompound nbt, int depth, int[] entityCount) {
        if (depth > MAX_ENTITY_TREE_DEPTH || ++entityCount[0] > MAX_ENTITY_TREE_SIZE) {
            return false;
        }
        String id = readEntityId(nbt);
        if (id == null || entityTypeForId(id) == null) {
            return false;
        }
        if (!isFinite(readVector(nbt, "Motion")) || readVector(nbt, "Motion").lengthSquared() > MAX_SPEED * MAX_SPEED) {
            return false;
        }
        if (!isFiniteRotation(nbt)) {
            return false;
        }
        if (nbt.contains("Passengers") && !isTagOfType(nbt, "Passengers", NbtElement.LIST_TYPE)) {
            return false;
        }
        NbtList passengers = listValue(nbt, "Passengers");
        if (!isListOf(passengers, NbtElement.COMPOUND_TYPE)) {
            return false;
        }
        for (int i = 0; i < passengers.size(); i++) {
            if (!validateEntityTree(compoundAt(passengers, i), depth + 1, entityCount)) {
                return false;
            }
        }
        return true;
    }

    private static Entity createEntityTree(
            ServerWorld world,
            NbtCompound nbt,
            Vec3d rootPosition,
            float rootYaw,
            float rootPitch,
            Vec3d rootVelocity,
            boolean root,
            int depth
    ) {
        if (depth > MAX_ENTITY_TREE_DEPTH) {
            return null;
        }
        String id = readEntityId(nbt);
        EntityType<?> type = id == null ? null : entityTypeForId(id);
        if (type == null) {
            return null;
        }
        Entity entity = type.create(world, SpawnReason.LOAD);
        if (entity == null) {
            return null;
        }
        boolean blockAttached = entity instanceof BlockAttachedEntity;
        NbtCompound clean = sanitizeSingleEntity(nbt, id);
        if (blockAttached) {
            NbtList position = new NbtList();
            position.add(NbtDouble.of(rootPosition.x));
            position.add(NbtDouble.of(rootPosition.y));
            position.add(NbtDouble.of(rootPosition.z));
            clean.put("Pos", position);
        }
        entity.readData(NbtReadView.create(ErrorReporter.EMPTY, world.getRegistryManager(), clean));
        float yaw = root ? rootYaw : readRotation(nbt, 0);
        float pitch = root ? rootPitch : readRotation(nbt, 1);
        Vec3d velocity = root ? rootVelocity : readVector(nbt, "Motion");
        if (!blockAttached) {
            entity.refreshPositionAndAngles(rootPosition.x, rootPosition.y, rootPosition.z, yaw, pitch);
        }
        entity.setVelocity(velocity);

        NbtList passengers = listValue(nbt, "Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            Entity passenger = createEntityTree(world, compoundAt(passengers, i), rootPosition,
                    rootYaw, rootPitch, rootVelocity, false, depth + 1);
            if (passenger == null || !passenger.startRiding(entity, true)) {
                return null;
            }
        }
        return entity;
    }

    private static NbtCompound sanitizeSingleEntity(NbtCompound source, String id) {
        NbtCompound clean = source.copy();
        for (String key : List.of(
                "UUID", "UUIDMost", "UUIDLeast", "Pos", "Motion", "Rotation", "Passengers", "Dimension",
                "Leash", "leash", "Owner", "OwnerUUID", "Thrower", "Attributes", "attributes",
                "ActiveEffects", "active_effects", "Invulnerable", "NoAI", "Command", "LastOutput",
                "SuccessCount", "DeathLootTable", "DeathLootTableSeed", "Offers", "Gossips"
        )) {
            clean.remove(key);
        }
        String path = pathOf(id);
        if (path.equals("item")) {
            clean.remove("Age");
            clean.remove("PickupDelay");
        }
        if (path.equals("furnace_minecart")) {
            clean.remove("Fuel");
            clean.remove("PushX");
            clean.remove("PushZ");
        }
        if (path.equals("tnt_minecart") || path.equals("creeper")) {
            clean.remove("Fuse");
            clean.remove("ExplosionRadius");
            clean.remove("ignited");
            clean.remove("powered");
        }
        return clean;
    }

    private static boolean isTreeInsideWorldBorder(ServerWorld world, Entity root) {
        if (!world.getWorldBorder().contains(root.getBoundingBox())) {
            return false;
        }
        for (Entity passenger : root.getPassengerList()) {
            if (!isTreeInsideWorldBorder(world, passenger)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTreeWithinReach(ServerPlayerEntity player, Entity root, double reach) {
        if (player.getEyePos().squaredDistanceTo(root.getPos()) > reach * reach) {
            return false;
        }
        for (Entity passenger : root.getPassengerList()) {
            if (!isTreeWithinReach(player, passenger, reach)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTreePlacementAllowed(ServerWorld world, ServerPlayerEntity player, Entity root) {
        BlockPos position = root instanceof BlockAttachedEntity attached
                ? attached.getAttachedBlockPos()
                : root.getBlockPos();
        if (!world.isChunkLoaded(position) || !world.canEntityModifyAt(player, position)) {
            return false;
        }
        for (Entity passenger : root.getPassengerList()) {
            if (!isTreePlacementAllowed(world, player, passenger)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canTreeStayAttached(Entity root) {
        if (root instanceof BlockAttachedEntity attached && !attached.canStayAttached()) {
            return false;
        }
        for (Entity passenger : root.getPassengerList()) {
            if (!canTreeStayAttached(passenger)) {
                return false;
            }
        }
        return true;
    }

    private static void discardTree(Entity root) {
        root.streamPassengersAndSelf().forEach(Entity::discard);
    }

    private static List<ItemStack> materialsForTree(
            NbtCompound root,
            ServerWorld world,
            ServerPlayerEntity player
    ) {
        List<ItemStack> materials = new ArrayList<>();
        int[] count = {0};
        return appendEntityTreeMaterials(root, world, materials, 0, count, player)
                ? mergeMaterials(materials)
                : null;
    }

    private static boolean appendEntityTreeMaterials(
            NbtCompound nbt,
            ServerWorld world,
            List<ItemStack> materials,
            int depth,
            int[] count,
            ServerPlayerEntity player
    ) {
        if (depth > MAX_ENTITY_TREE_DEPTH || ++count[0] > MAX_ENTITY_TREE_SIZE) {
            return false;
        }
        String id = readEntityId(nbt);
        if (id == null || entityTypeForId(id) == null) {
            return false;
        }
        if (!appendConstructedEntityMaterials(id, materials, player)) {
            ItemStack baseStack = stackForEntity(id, nbt, world);
            if (baseStack == null || baseStack.isEmpty()) {
                return false;
            }
            materials.add(baseStack);
        }

        if (!pathOf(id).equals("item") && !appendStoredItem(materials, nbt, "Item", world)) {
            return false;
        }
        for (String key : List.of("SaddleItem", "ArmorItem", "DecorItem", "body_armor_item")) {
            if (!appendStoredItem(materials, nbt, key, world)) {
                return false;
            }
        }
        if (isChestedHorse(id, nbt)) {
            materials.add(new ItemStack(Items.CHEST));
        }
        for (String key : List.of("Inventory", "ArmorItems", "HandItems")) {
            if (!appendStoredItems(materials, nbt, key, world, 0)) {
                return false;
            }
        }
        int containerCapacity = containerCapacity(id, nbt);
        if (containerCapacity == Integer.MIN_VALUE
                || !appendStoredItems(materials, nbt, "Items", world, containerCapacity)) {
            return false;
        }

        NbtList passengers = listValue(nbt, "Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            if (!appendEntityTreeMaterials(compoundAt(passengers, i), world, materials, depth + 1, count, player)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack stackForEntity(String id, NbtCompound nbt, ServerWorld world) {
        if (id == null) {
            return null;
        }
        if (pathOf(id).equals("item")) {
            if (!isTagOfType(nbt, "Item", NbtElement.COMPOUND_TYPE)) {
                return null;
            }
            ItemStack stack = parseItem(world, compoundValue(nbt, "Item"));
            return stack.isEmpty() ? null : stack;
        }
        String path = pathOf(id);
        Item direct = switch (path) {
            case "armor_stand" -> Items.ARMOR_STAND;
            case "painting" -> Items.PAINTING;
            case "item_frame" -> Items.ITEM_FRAME;
            case "glow_item_frame" -> Items.GLOW_ITEM_FRAME;
            case "end_crystal" -> Items.END_CRYSTAL;
            case "minecart" -> Items.MINECART;
            case "chest_minecart" -> Items.CHEST_MINECART;
            case "furnace_minecart" -> Items.FURNACE_MINECART;
            case "tnt_minecart" -> Items.TNT_MINECART;
            case "hopper_minecart" -> Items.HOPPER_MINECART;
            case "boat" -> boatItem(stringValue(nbt, "Type"), false);
            case "chest_boat" -> boatItem(stringValue(nbt, "Type"), true);
            default -> null;
        };
        if (direct == null && isSplitBoatPath(path)) {
            direct = itemForId(namespaceOf(id), path);
        }
        if (direct != null) {
            return new ItemStack(direct);
        }
        Item egg = itemForId(namespaceOf(id), path + "_spawn_egg");
        return egg == null ? null : new ItemStack(egg);
    }


    private static boolean appendConstructedEntityMaterials(
            String id,
            List<ItemStack> materials,
            ServerPlayerEntity player
    ) {
        String path = pathOf(id);
        Item spawnEgg = itemForId(namespaceOf(id), path + "_spawn_egg");
        if (spawnEgg != null && hasInventoryItem(player, spawnEgg)) {
            return false;
        }
        switch (path) {
            case "snow_golem" -> {
                materials.add(new ItemStack(constructionPumpkin(player)));
                materials.add(new ItemStack(Items.SNOW_BLOCK, 2));
                return true;
            }
            case "iron_golem" -> {
                materials.add(new ItemStack(constructionPumpkin(player)));
                materials.add(new ItemStack(Items.IRON_BLOCK, 4));
                return true;
            }
            case "copper_golem" -> {
                materials.add(new ItemStack(Items.COPPER_BLOCK));
                materials.add(new ItemStack(constructionPumpkin(player)));
                Item copperChest = itemForId("minecraft", "copper_chest");
                if (copperChest != null) {
                    materials.add(new ItemStack(copperChest));
                }
                return true;
            }
            case "wither" -> {
                materials.add(new ItemStack(constructionWitherBase(player), 4));
                materials.add(new ItemStack(Items.WITHER_SKELETON_SKULL, 3));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean hasInventoryItem(ServerPlayerEntity player, Item item) {
        return player != null && inventoryItems(player).stream()
                .anyMatch(stack -> stack.isOf(item) && !stack.isEmpty());
    }

    private static Item constructionPumpkin(ServerPlayerEntity player) {
        return hasInventoryItem(player, Items.CARVED_PUMPKIN)
                ? Items.CARVED_PUMPKIN
                : hasInventoryItem(player, Items.PUMPKIN)
                ? Items.PUMPKIN
                : Items.CARVED_PUMPKIN;
    }

    private static Item constructionWitherBase(ServerPlayerEntity player) {
        return hasInventoryItem(player, Items.SOUL_SAND)
                ? Items.SOUL_SAND
                : hasInventoryItem(player, Items.SOUL_SOIL)
                ? Items.SOUL_SOIL
                : Items.SOUL_SAND;
    }
    private static boolean appendStoredItem(
            List<ItemStack> materials,
            NbtCompound nbt,
            String key,
            ServerWorld world
    ) {
        if (!nbt.contains(key)) {
            return true;
        }
        if (!isTagOfType(nbt, key, NbtElement.COMPOUND_TYPE)) {
            return false;
        }
        NbtCompound itemNbt = compoundValue(nbt, key);
        if (itemNbt.isEmpty()) {
            return true;
        }
        ItemStack stack = parseItem(world, itemNbt);
        if (stack.isEmpty()) {
            return false;
        }
        materials.add(stack);
        return true;
    }

    private static boolean appendStoredItems(
            List<ItemStack> materials,
            NbtCompound nbt,
            String key,
            ServerWorld world,
            int capacity
    ) {
        if (!nbt.contains(key)) {
            return true;
        }
        if (!isTagOfType(nbt, key, NbtElement.LIST_TYPE)) {
            return false;
        }
        NbtList items = listValue(nbt, key);
        if (!isListOf(items, NbtElement.COMPOUND_TYPE)) {
            return false;
        }
        if (capacity < 0 && !items.isEmpty()) {
            return false;
        }
        Set<Integer> slots = capacity > 0 ? new HashSet<>() : null;
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = compoundAt(items, i);
            if (itemNbt.isEmpty()) {
                continue;
            }
            if (capacity > 0) {
                int slot = byteValue(itemNbt, "Slot") & 255;
                if (!isTagOfType(itemNbt, "Slot", NbtElement.BYTE_TYPE) || slot >= capacity || !slots.add(slot)) {
                    return false;
                }
            }
            ItemStack stack = parseItem(world, itemNbt);
            if (stack.isEmpty()) {
                return false;
            }
            materials.add(stack);
        }
        return true;
    }

    private static Item boatItem(String type, boolean chest) {
        String prefix = switch (type) {
            case "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "mangrove", "bamboo", "oak", "" ->
                    type.isEmpty() ? "oak" : type;
            default -> null;
        };
        if (prefix == null) {
            return null;
        }
        String path = prefix.equals("bamboo")
                ? (chest ? "bamboo_chest_raft" : "bamboo_raft")
                : (prefix + (chest ? "_chest_boat" : "_boat"));
        return itemForId("minecraft", path);
    }

    private static boolean isChestedHorse(String id, NbtCompound nbt) {
        return switch (pathOf(id)) {
            case "donkey", "mule", "llama", "trader_llama" -> booleanValue(nbt, "ChestedHorse");
            default -> false;
        };
    }

    private static int containerCapacity(String id, NbtCompound nbt) {
        String path = pathOf(id);
        if (path.endsWith("_chest_boat") || path.endsWith("_chest_raft")) {
            return 27;
        }
        return switch (path) {
            case "hopper_minecart" -> 5;
            case "chest_minecart", "chest_boat" -> 27;
            case "donkey", "mule" -> booleanValue(nbt, "ChestedHorse") ? 15 : -1;
            case "llama", "trader_llama" -> llamaContainerCapacity(nbt);
            default -> -1;
        };
    }

    private static int llamaContainerCapacity(NbtCompound nbt) {
        if (!booleanValue(nbt, "ChestedHorse")) {
            return -1;
        }
        int strength = intValue(nbt, "Strength");
        return strength >= 1 && strength <= 5 ? strength * 3 : Integer.MIN_VALUE;
    }

    private static boolean hasMaterials(ServerPlayerEntity player, List<ItemStack> required) {
        for (ItemStack wanted : required) {
            int count = 0;
            for (ItemStack actual : inventoryItems(player)) {
                if (ItemStack.areItemsAndComponentsEqual(actual, wanted)) {
                    count += actual.getCount();
                }
            }
            if (count < wanted.getCount()) {
                return false;
            }
        }
        return true;
    }

    private static List<ItemStack> mergeMaterials(List<ItemStack> materials) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack material : materials) {
            if (material.isEmpty()) {
                continue;
            }
            ItemStack existing = merged.stream()
                    .filter(stack -> ItemStack.areItemsAndComponentsEqual(stack, material))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(material.copy());
            } else {
                existing.increment(material.getCount());
            }
        }
        return merged;
    }

    private static void consumeMaterials(ServerPlayerEntity player, List<ItemStack> required) {
        for (ItemStack wanted : required) {
            int left = wanted.getCount();
            for (ItemStack actual : inventoryItems(player)) {
                if (left <= 0) {
                    break;
                }
                if (!ItemStack.areItemsAndComponentsEqual(actual, wanted)) {
                    continue;
                }
                int amount = Math.min(left, actual.getCount());
                actual.decrement(amount);
                left -= amount;
            }
        }
        player.getInventory().markDirty();
    }

    private static void restoreInventory(ServerPlayerEntity player, List<ItemStack> snapshot) {
        for (int i = 0; i < snapshot.size() && i < inventoryItems(player).size(); i++) {
            player.getInventory().setStack(i, snapshot.get(i).copy());
        }
        player.getInventory().markDirty();
    }

    private static Vec3d readVector(NbtCompound nbt, String key) {
        if (!nbt.contains(key)) {
            return Vec3d.ZERO;
        }
        if (!isTagOfType(nbt, key, NbtElement.LIST_TYPE)) {
            return new Vec3d(Double.NaN, Double.NaN, Double.NaN);
        }
        NbtList list = listValue(nbt, key);
        if (list.size() != 3 || !isListOf(list, NbtElement.DOUBLE_TYPE)) {
            return new Vec3d(Double.NaN, Double.NaN, Double.NaN);
        }
        return new Vec3d(doubleAt(list, 0), doubleAt(list, 1), doubleAt(list, 2));
    }

    private static float readRotation(NbtCompound nbt, int index) {
        if (!nbt.contains("Rotation")) {
            return 0.0F;
        }
        NbtList rotation = listValue(nbt, "Rotation");
        return rotation.size() > index ? floatAt(rotation, index) : 0.0F;
    }

    private static boolean isFiniteRotation(NbtCompound nbt) {
        if (!nbt.contains("Rotation")) {
            return true;
        }
        if (!isTagOfType(nbt, "Rotation", NbtElement.LIST_TYPE)) {
            return false;
        }
        NbtList rotation = listValue(nbt, "Rotation");
        return rotation.size() == 2 && isListOf(rotation, NbtElement.FLOAT_TYPE)
                && Float.isFinite(floatAt(rotation, 0)) && Float.isFinite(floatAt(rotation, 1));
    }

    private static boolean isFinite(Vec3d value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static String dimensionId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static EntityType<?> entityTypeForId(String value) {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
            return null;
        }
        return Registries.ENTITY_TYPE.get(id);
    }

    private static Item itemForId(String namespace, String path) {
        Identifier id = Identifier.of(namespace, path);
        if (!Registries.ITEM.containsId(id)) {
            return null;
        }
        return Registries.ITEM.get(id);
    }

    private static ItemStack parseItem(ServerWorld world, NbtCompound nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(world.getRegistryManager().getOps(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    private static boolean isTagOfType(NbtCompound nbt, String key, int type) {
        NbtElement value = nbt.get(key);
        return value != null && value.getType() == type;
    }

    private static NbtList listValue(NbtCompound nbt, String key) {
        NbtElement value = nbt.get(key);
        return value instanceof NbtList list ? list : new NbtList();
    }

    private static NbtCompound compoundValue(NbtCompound nbt, String key) {
        NbtElement value = nbt.get(key);
        return value instanceof NbtCompound compound ? compound : new NbtCompound();
    }

    private static NbtCompound compoundAt(NbtList list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    private static String stringValue(NbtCompound nbt, String key) {
        return nbt.getString(key, "");
    }

    private static boolean booleanValue(NbtCompound nbt, String key) {
        return nbt.getBoolean(key, false);
    }

    private static int intValue(NbtCompound nbt, String key) {
        return nbt.getInt(key, 0);
    }

    private static byte byteValue(NbtCompound nbt, String key) {
        return nbt.getByte(key, (byte) 0);
    }

    private static double doubleAt(NbtList list, int index) {
        return list.getDouble(index, Double.NaN);
    }

    private static float floatAt(NbtList list, int index) {
        return list.getFloat(index, Float.NaN);
    }

    private static boolean isListOf(NbtList list, int type) {
        for (NbtElement element : list) {
            if (element.getType() != type) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSplitBoatPath(String path) {
        return path.endsWith("_boat") || path.endsWith("_chest_boat")
                || path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    private static String namespaceOf(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? "minecraft" : id.substring(0, separator);
    }

    private static String pathOf(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    private static List<ItemStack> inventoryItems(ServerPlayerEntity player) {
        return player.getInventory().getMainStacks();
    }

    private static final class Session {
        private final String token;
        private final boolean enabled;
        private final Deque<Long> nonces = new ArrayDeque<>();
        private long lastRequestTick = Long.MIN_VALUE;

        private Session(String token, boolean enabled) {
            this.token = token;
            this.enabled = enabled;
        }
    }
}
