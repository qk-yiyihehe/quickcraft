package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

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
        ServerPlayNetworking.registerGlobalReceiver(
                QuickLitematicaEntityPlacementPayloads.PassengerRequestPayload.ID,
                (payload, context) -> context.server().execute(
                        () -> handlePassengerRequest(context.player(), payload))
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.player));
    }

    public static String ensureSession(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.enabled) {
            return session.token;
        }
        String token = UUID.randomUUID().toString();
        SESSIONS.put(player.getUUID(), new Session(token, true));
        return token;
    }

    public static void handleHello(ServerPlayer player, QuickLitematicaEntityPlacementPayloads.HelloPayload payload) {
        if (player == null) {
            return;
        }
        boolean compatible = payload.version() == QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION;
        String token = UUID.randomUUID().toString();
        Session session = new Session(token, compatible);
        SESSIONS.put(player.getUUID(), session);
        ServerPlayNetworking.send(player, new QuickLitematicaEntityPlacementPayloads.CapabilityPayload(
                QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION,
                session.enabled,
                placementReach(player),
                Math.min(MAX_ENTITY_NBT_BYTES, Math.max(0, payload.maxNbtBytes())),
                payload.features() & QuickLitematicaEntityPlacementPayloads.SERVER_FEATURES,
                token
        ));
    }

    public static void handleRequest(ServerPlayer player, QuickLitematicaEntityPlacementPayloads.RequestPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(payload.sessionToken())) {
            sendResult(player, payload.nonce(), "DISABLED", "");
            return;
        }
        ServerLevel world = player.level();
        long tick = world.getGameTime();
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
        if (!isFinite(payload.target()) || player.getEyePosition().distanceToSqr(payload.target()) > reach * reach) {
            sendResult(player, payload.nonce(), "OUT_OF_REACH", "");
            return;
        }
        if (!isFinite(payload.velocity()) || payload.velocity().lengthSqr() > MAX_SPEED * MAX_SPEED) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        BlockPos targetPos = BlockPos.containing(payload.target());
        if (!world.hasChunkAt(targetPos)) {
            sendResult(player, payload.nonce(), "WORLD_RULE_BLOCKED", "");
            return;
        }
        if (!world.mayInteract(player, targetPos)) {
            sendResult(player, payload.nonce(), "PERMISSION_DENIED", "");
            return;
        }

        CompoundTag requested = payload.entityNbt();
        int[] entityCount = {0};
        if (requested == null || requested.sizeInBytes() > MAX_ENTITY_NBT_BYTES
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
            added = world.tryAddFreshEntityWithPassengers(root);
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
        giveCopperChests(player, requested);
        sendResult(player, payload.nonce(), "SUCCESS", root.getUUID().toString());
    }

    public static void handlePassengerRequest(
            ServerPlayerEntity player,
            QuickLitematicaEntityPlacementPayloads.PassengerRequestPayload payload
    ) {
        if (player == null || payload == null
                || !beginRequest(player, payload.sessionToken(), payload.nonce())) {
            return;
        }
        ServerWorld world = player.getServerWorld();
        if (!dimensionId(world).equals(payload.dimension().toString())) {
            sendResult(player, payload.nonce(), "OUT_OF_REACH", "");
            return;
        }
        Entity vehicle = world.getEntity(payload.vehicleUuid());
        double reach = placementReach(player);
        if (vehicle == null || !vehicle.isAlive()
                || player.getEyePos().squaredDistanceTo(vehicle.getPos()) > reach * reach) {
            sendResult(player, payload.nonce(), "TARGET_ENTITY_MISSING", "");
            return;
        }
        BlockPos targetPos = vehicle.getBlockPos();
        if (!world.isChunkLoaded(targetPos)) {
            sendResult(player, payload.nonce(), "WORLD_RULE_BLOCKED", "");
            return;
        }
        if (!world.canPlayerModifyAt(player, targetPos)) {
            sendResult(player, payload.nonce(), "PERMISSION_DENIED", "");
            return;
        }
        if (!vehicle.getPassengerList().isEmpty()) {
            sendResult(player, payload.nonce(), "PASSENGERS_PRESENT", "");
            return;
        }

        NbtCompound requested = payload.entityNbt();
        int[] entityCount = {0};
        if (requested == null || requested.getSizeInBytes() > MAX_ENTITY_NBT_BYTES
                || !Registries.ENTITY_TYPE.getId(vehicle.getType()).toString().equals(readEntityId(requested))
                || !validateEntityTree(requested, 0, entityCount)) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        NbtList passengerNbt = listValue(requested, "Passengers");
        if (passengerNbt.isEmpty()) {
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        List<ItemStack> required = materialsForPassengers(requested, world, player);
        if (required == null) {
            sendResult(player, payload.nonce(), "UNSUPPORTED_ENTITY", "");
            return;
        }
        boolean creativeMaterialBypass = payload.creativeMaterialBypass() && player.isCreative();
        if (!creativeMaterialBypass && !hasMaterials(player, required)) {
            sendResult(player, payload.nonce(), "NO_MATERIAL", "");
            return;
        }

        List<Entity> passengers = new ArrayList<>();
        try {
            for (int index = 0; index < passengerNbt.size(); index++) {
                NbtCompound passenger = compoundAt(passengerNbt, index);
                Entity entity = createEntityTree(world, passenger, vehicle.getPos(),
                        0.0F, 0.0F, Vec3d.ZERO, false, 1);
                if (entity == null) {
                    passengers.forEach(QuickLitematicaEntityPlacementServer::discardTree);
                    sendResult(player, payload.nonce(), "INVALID_NBT", "");
                    return;
                }
                passengers.add(entity);
            }
        } catch (RuntimeException ignored) {
            passengers.forEach(QuickLitematicaEntityPlacementServer::discardTree);
            sendResult(player, payload.nonce(), "INVALID_NBT", "");
            return;
        }
        for (Entity passenger : passengers) {
            if (!isTreeWithinReach(player, passenger, reach)
                    || !isTreePlacementAllowed(world, player, passenger)
                    || !isTreeInsideWorldBorder(world, passenger)
                    || !canTreeStayAttached(passenger)) {
                passengers.forEach(QuickLitematicaEntityPlacementServer::discardTree);
                sendResult(player, payload.nonce(), "COLLISION", "");
                return;
            }
        }

        List<ItemStack> snapshot = creativeMaterialBypass
                ? List.of()
                : inventoryItems(player).stream().map(ItemStack::copy).toList();
        if (!creativeMaterialBypass) {
            consumeMaterials(player, required);
        }
        boolean added = true;
        try {
            for (Entity passenger : passengers) {
                if (!world.spawnNewEntityAndPassengers(passenger)) {
                    added = false;
                    break;
                }
            }
            if (added) {
                for (Entity passenger : passengers) {
                    if (!passenger.startRiding(vehicle, true)) {
                        added = false;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            added = false;
        }
        if (!added) {
            passengers.forEach(QuickLitematicaEntityPlacementServer::discardTree);
            if (!creativeMaterialBypass) {
                restoreInventory(player, snapshot);
            }
            sendResult(player, payload.nonce(), "INTERNAL_ERROR", "");
            return;
        }
        for (int index = 0; index < passengerNbt.size(); index++) {
            giveCopperChests(player, compoundAt(passengerNbt, index));
        }
        sendResult(player, payload.nonce(), "SUCCESS", "",
                "quickcraft.entity_placement.result.passengers_added");
    }

    public static void clear(ServerPlayerEntity player) {
        if (player != null) {
            SESSIONS.remove(player.getUUID());
        }
    }

    private static void sendResult(ServerPlayer player, long nonce, String status, String uuid) {
        sendResult(player, nonce, status, uuid, "");
    }

    private static void sendResult(
            ServerPlayer player,
            long nonce,
            String status,
            String uuid,
            String messageKey
    ) {
        ServerPlayNetworking.send(player, new QuickLitematicaEntityPlacementPayloads.ResultPayload(
                nonce, status, uuid, messageKey));
    }

    private static boolean beginRequest(ServerPlayerEntity player, String token, long nonce) {
        Session session = SESSIONS.get(player.getUuid());
        if (session == null || !session.token.equals(token)) {
            sendResult(player, nonce, "DISABLED", "");
            return false;
        }
        if (!session.enabled || !QuickCraftConfigs.isEasyPlaceEntitiesEnabled()) {
            sendResult(player, nonce, "DISABLED", "");
            return false;
        }
        if (session.nonces.contains(nonce)) {
            sendResult(player, nonce, "REPLAYED_REQUEST", "");
            return false;
        }
        if (session.nonces.size() >= MAX_NONCES) {
            session.nonces.removeFirst();
        }
        session.nonces.add(nonce);
        long tick = player.getServerWorld().getTime();
        if (session.lastRequestTick != Long.MIN_VALUE
                && tick - session.lastRequestTick < REQUEST_COOLDOWN_TICKS) {
            sendResult(player, nonce, "RATE_LIMITED", "");
            return false;
        }
        session.lastRequestTick = tick;
        return true;
    }

    private static double placementReach(ServerPlayer player) {
        // 生存实体交互距离只有 3 格，轻松放置按方块距离来，避免准星能扫到却放不下。
        double reach = player == null ? DEFAULT_REACH : player.blockInteractionRange();
        return Math.max(DEFAULT_REACH, Math.max(0.0D, Math.min(MAX_REACH, reach)));
    }

    private static String readEntityId(CompoundTag nbt) {
        Identifier parsed = Identifier.tryParse(stringValue(nbt, "id"));
        return parsed == null ? null : parsed.toString();
    }

    private static boolean validateEntityTree(CompoundTag nbt, int depth, int[] entityCount) {
        if (depth > MAX_ENTITY_TREE_DEPTH || ++entityCount[0] > MAX_ENTITY_TREE_SIZE) {
            return false;
        }
        String id = readEntityId(nbt);
        if (id == null || entityTypeForId(id) == null) {
            return false;
        }
        if (!isFinite(readVector(nbt, "Motion")) || readVector(nbt, "Motion").lengthSqr() > MAX_SPEED * MAX_SPEED) {
            return false;
        }
        if (!isFiniteRotation(nbt)) {
            return false;
        }
        if (nbt.contains("Passengers") && !isTagOfType(nbt, "Passengers", Tag.TAG_LIST)) {
            return false;
        }
        ListTag passengers = listValue(nbt, "Passengers");
        if (!isListOf(passengers, Tag.TAG_COMPOUND)) {
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
            ServerLevel world,
            CompoundTag nbt,
            Vec3 rootPosition,
            float rootYaw,
            float rootPitch,
            Vec3 rootVelocity,
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
        Entity entity = type.create(world, EntitySpawnReason.LOAD);
        if (entity == null) {
            return null;
        }
        boolean blockAttached = entity instanceof BlockAttachedEntity;
        CompoundTag clean = sanitizeSingleEntity(nbt, id);
        if (blockAttached) {
            ListTag position = new ListTag();
            position.add(DoubleTag.valueOf(rootPosition.x));
            position.add(DoubleTag.valueOf(rootPosition.y));
            position.add(DoubleTag.valueOf(rootPosition.z));
            clean.put("Pos", position);
        }
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), clean));
        float yaw = root ? rootYaw : readRotation(nbt, 0);
        float pitch = root ? rootPitch : readRotation(nbt, 1);
        Vec3 velocity = root ? rootVelocity : readVector(nbt, "Motion");
        if (!blockAttached) {
            entity.snapTo(rootPosition.x, rootPosition.y, rootPosition.z, yaw, pitch);
        }
        entity.setDeltaMovement(velocity);

        ListTag passengers = listValue(nbt, "Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            Entity passenger = createEntityTree(world, compoundAt(passengers, i), rootPosition,
                    rootYaw, rootPitch, rootVelocity, false, depth + 1);
            if (passenger == null || !passenger.startRiding(entity, true, true)) {
                return null;
            }
        }
        return entity;
    }

    private static CompoundTag sanitizeSingleEntity(CompoundTag source, String id) {
        CompoundTag clean = source.copy();
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

    private static boolean isTreeInsideWorldBorder(ServerLevel world, Entity root) {
        if (!world.getWorldBorder().isWithinBounds(root.getBoundingBox())) {
            return false;
        }
        for (Entity passenger : root.getPassengers()) {
            if (!isTreeInsideWorldBorder(world, passenger)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTreeWithinReach(ServerPlayer player, Entity root, double reach) {
        if (player.getEyePosition().distanceToSqr(root.position()) > reach * reach) {
            return false;
        }
        for (Entity passenger : root.getPassengers()) {
            if (!isTreeWithinReach(player, passenger, reach)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTreePlacementAllowed(ServerLevel world, ServerPlayer player, Entity root) {
        BlockPos position = root instanceof BlockAttachedEntity attached
                ? attached.getPos()
                : root.blockPosition();
        if (!world.hasChunkAt(position) || !world.mayInteract(player, position)) {
            return false;
        }
        for (Entity passenger : root.getPassengers()) {
            if (!isTreePlacementAllowed(world, player, passenger)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canTreeStayAttached(Entity root) {
        if (root instanceof BlockAttachedEntity attached && !attached.survives()) {
            return false;
        }
        for (Entity passenger : root.getPassengers()) {
            if (!canTreeStayAttached(passenger)) {
                return false;
            }
        }
        return true;
    }

    private static void discardTree(Entity root) {
        root.getPassengersAndSelf().forEach(Entity::discard);
    }

    private static List<ItemStack> materialsForTree(
            CompoundTag root,
            ServerLevel world,
            ServerPlayer player
    ) {
        List<ItemStack> materials = new ArrayList<>();
        int[] count = {0};
        return appendEntityTreeMaterials(root, world, materials, 0, count, player)
                ? mergeMaterials(materials)
                : null;
    }

    private static List<ItemStack> materialsForPassengers(
            NbtCompound root,
            ServerWorld world,
            ServerPlayerEntity player
    ) {
        List<ItemStack> materials = new ArrayList<>();
        int[] count = {0};
        NbtList passengers = listValue(root, "Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            if (!appendEntityTreeMaterials(
                    compoundAt(passengers, index), world, materials, 1, count, player)) {
                return null;
            }
        }
        return mergeMaterials(materials);
    }

    private static boolean appendEntityTreeMaterials(
            CompoundTag nbt,
            ServerLevel world,
            List<ItemStack> materials,
            int depth,
            int[] count,
            ServerPlayer player
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

        ListTag passengers = listValue(nbt, "Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            if (!appendEntityTreeMaterials(compoundAt(passengers, i), world, materials, depth + 1, count, player)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack stackForEntity(String id, CompoundTag nbt, ServerLevel world) {
        if (id == null) {
            return null;
        }
        if (pathOf(id).equals("item")) {
            if (!isTagOfType(nbt, "Item", Tag.TAG_COMPOUND)) {
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
            ServerPlayer player
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
                Item copperBlock = itemForId("minecraft", "copper_block");
                if (copperBlock == null) {
                    return false;
                }
                materials.add(new ItemStack(copperBlock));
                materials.add(new ItemStack(constructionPumpkin(player)));
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

    private static boolean hasInventoryItem(ServerPlayer player, Item item) {
        return player != null && inventoryItems(player).stream()
                .anyMatch(stack -> stack.is(item) && !stack.isEmpty());
    }

    private static Item constructionPumpkin(ServerPlayer player) {
        return hasInventoryItem(player, Items.CARVED_PUMPKIN)
                ? Items.CARVED_PUMPKIN
                : hasInventoryItem(player, Items.PUMPKIN)
                ? Items.PUMPKIN
                : Items.CARVED_PUMPKIN;
    }

    private static Item constructionWitherBase(ServerPlayer player) {
        return hasInventoryItem(player, Items.SOUL_SAND)
                ? Items.SOUL_SAND
                : hasInventoryItem(player, Items.SOUL_SOIL)
                ? Items.SOUL_SOIL
                : Items.SOUL_SAND;
    }

    private static void giveCopperChests(ServerPlayer player, CompoundTag root) {
        Item copperChest = itemForId("minecraft", "copper_chest");
        if (copperChest == null) {
            return;
        }
        int count = countEntities(root, "copper_golem");
        for (int index = 0; index < count; index++) {
            ItemStack stack = new ItemStack(copperChest);
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false, false);
            }
        }
    }

    private static int countEntities(CompoundTag nbt, String path) {
        String id = readEntityId(nbt);
        int count = path.equals(id == null ? "" : pathOf(id)) ? 1 : 0;
        ListTag passengers = listValue(nbt, "Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            count += countEntities(compoundAt(passengers, index), path);
        }
        return count;
    }

    private static boolean appendStoredItem(
            List<ItemStack> materials,
            CompoundTag nbt,
            String key,
            ServerLevel world
    ) {
        if (!nbt.contains(key)) {
            return true;
        }
        if (!isTagOfType(nbt, key, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag itemNbt = compoundValue(nbt, key);
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
            CompoundTag nbt,
            String key,
            ServerLevel world,
            int capacity
    ) {
        if (!nbt.contains(key)) {
            return true;
        }
        if (!isTagOfType(nbt, key, Tag.TAG_LIST)) {
            return false;
        }
        ListTag items = listValue(nbt, key);
        if (!isListOf(items, Tag.TAG_COMPOUND)) {
            return false;
        }
        if (capacity < 0 && !items.isEmpty()) {
            return false;
        }
        Set<Integer> slots = capacity > 0 ? new HashSet<>() : null;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemNbt = compoundAt(items, i);
            if (itemNbt.isEmpty()) {
                continue;
            }
            if (capacity > 0) {
                int slot = byteValue(itemNbt, "Slot") & 255;
                if (!isTagOfType(itemNbt, "Slot", Tag.TAG_BYTE) || slot >= capacity || !slots.add(slot)) {
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

    private static boolean isChestedHorse(String id, CompoundTag nbt) {
        return switch (pathOf(id)) {
            case "donkey", "mule", "llama", "trader_llama" -> booleanValue(nbt, "ChestedHorse");
            default -> false;
        };
    }

    private static int containerCapacity(String id, CompoundTag nbt) {
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

    private static int llamaContainerCapacity(CompoundTag nbt) {
        if (!booleanValue(nbt, "ChestedHorse")) {
            return -1;
        }
        int strength = intValue(nbt, "Strength");
        return strength >= 1 && strength <= 5 ? strength * 3 : Integer.MIN_VALUE;
    }

    private static boolean hasMaterials(ServerPlayer player, List<ItemStack> required) {
        for (ItemStack wanted : required) {
            int count = 0;
            for (ItemStack actual : inventoryItems(player)) {
                if (ItemStack.isSameItemSameComponents(actual, wanted)) {
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
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, material))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(material.copy());
            } else {
                existing.grow(material.getCount());
            }
        }
        return merged;
    }

    private static void consumeMaterials(ServerPlayer player, List<ItemStack> required) {
        for (ItemStack wanted : required) {
            int left = wanted.getCount();
            for (ItemStack actual : inventoryItems(player)) {
                if (left <= 0) {
                    break;
                }
                if (!ItemStack.isSameItemSameComponents(actual, wanted)) {
                    continue;
                }
                int amount = Math.min(left, actual.getCount());
                actual.shrink(amount);
                left -= amount;
            }
        }
        player.getInventory().setChanged();
    }

    private static void restoreInventory(ServerPlayer player, List<ItemStack> snapshot) {
        for (int i = 0; i < snapshot.size() && i < inventoryItems(player).size(); i++) {
            player.getInventory().setItem(i, snapshot.get(i).copy());
        }
        player.getInventory().setChanged();
    }

    private static Vec3 readVector(CompoundTag nbt, String key) {
        if (!nbt.contains(key)) {
            return Vec3.ZERO;
        }
        if (!isTagOfType(nbt, key, Tag.TAG_LIST)) {
            return new Vec3(Double.NaN, Double.NaN, Double.NaN);
        }
        ListTag list = listValue(nbt, key);
        if (list.size() != 3 || !isListOf(list, Tag.TAG_DOUBLE)) {
            return new Vec3(Double.NaN, Double.NaN, Double.NaN);
        }
        return new Vec3(doubleAt(list, 0), doubleAt(list, 1), doubleAt(list, 2));
    }

    private static float readRotation(CompoundTag nbt, int index) {
        if (!nbt.contains("Rotation")) {
            return 0.0F;
        }
        ListTag rotation = listValue(nbt, "Rotation");
        return rotation.size() > index ? floatAt(rotation, index) : 0.0F;
    }

    private static boolean isFiniteRotation(CompoundTag nbt) {
        if (!nbt.contains("Rotation")) {
            return true;
        }
        if (!isTagOfType(nbt, "Rotation", Tag.TAG_LIST)) {
            return false;
        }
        ListTag rotation = listValue(nbt, "Rotation");
        return rotation.size() == 2 && isListOf(rotation, Tag.TAG_FLOAT)
                && Float.isFinite(floatAt(rotation, 0)) && Float.isFinite(floatAt(rotation, 1));
    }

    private static boolean isFinite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static String dimensionId(ServerLevel world) {
        return world.dimension().identifier().toString();
    }

    private static EntityType<?> entityTypeForId(String value) {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    private static Item itemForId(String namespace, String path) {
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static ItemStack parseItem(ServerLevel world, CompoundTag nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    private static boolean isTagOfType(CompoundTag nbt, String key, int type) {
        Tag value = nbt.get(key);
        return value != null && value.getId() == type;
    }

    private static ListTag listValue(CompoundTag nbt, String key) {
        Tag value = nbt.get(key);
        return value instanceof ListTag list ? list : new ListTag();
    }

    private static CompoundTag compoundValue(CompoundTag nbt, String key) {
        Tag value = nbt.get(key);
        return value instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    private static CompoundTag compoundAt(ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    private static String stringValue(CompoundTag nbt, String key) {
        return nbt.getStringOr(key, "");
    }

    private static boolean booleanValue(CompoundTag nbt, String key) {
        return nbt.getBooleanOr(key, false);
    }

    private static int intValue(CompoundTag nbt, String key) {
        return nbt.getIntOr(key, 0);
    }

    private static byte byteValue(CompoundTag nbt, String key) {
        return nbt.getByteOr(key, (byte) 0);
    }

    private static double doubleAt(ListTag list, int index) {
        return list.getDouble(index).orElse(Double.NaN);
    }

    private static float floatAt(ListTag list, int index) {
        return list.getFloat(index).orElse(Float.NaN);
    }

    private static boolean isListOf(ListTag list, int type) {
        for (Tag element : list) {
            if (element.getId() != type) {
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

    private static List<ItemStack> inventoryItems(ServerPlayer player) {
        return player.getInventory().getNonEquipmentItems();
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
