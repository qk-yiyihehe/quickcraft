package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 客户端实体放置入口：仅从投影收集候选并向已握手服务器发请求，不生成实体也不修改库存。
 */
public final class QuickLitematicaEntityPlacement {
    private static final double DEFAULT_REACH = 4.5D;
    private static final int MAX_SCAN_CANDIDATES = 4096;
    private static final int MAX_SELECTOR_CANDIDATES = 54;
    private static final int MAX_ENTITY_TREE_DEPTH = 8;
    private static final int MAX_ENTITY_TREE_SIZE = 16;
    private static final long PENDING_TIMEOUT_TICKS = 40L;
    private static ServerCapability capability;
    private static boolean helloSent;
    private static long clientTick;
    private static final Map<Long, PendingRequest> pendingRequests = new java.util.HashMap<>();
    private static final Map<String, UUID> confirmedEntityUuids = new java.util.HashMap<>();

    public static void initializeClient() {
        // 只有实际包含实体放置服务端实现的 FGA 才会在 main 入口先注册 codec。
        // 旧版 FGA 只有相同的 mod ID，不能据此跳过客户端注册。
        if (FabricLoader.getInstance().getModContainer("carpet-fga-addition")
                .flatMap(container -> container.findPath("carpet/fga/QuickCraftEntityPlacementServer.class"))
                .isEmpty()) {
            PayloadTypeRegistry.playC2S().register(
                    QuickLitematicaEntityPlacementPayloads.HelloPayload.ID,
                    QuickLitematicaEntityPlacementPayloads.HelloPayload.CODEC
            );
            PayloadTypeRegistry.playC2S().register(
                    QuickLitematicaEntityPlacementPayloads.RequestPayload.ID,
                    QuickLitematicaEntityPlacementPayloads.RequestPayload.CODEC
            );
            PayloadTypeRegistry.playS2C().register(
                    QuickLitematicaEntityPlacementPayloads.CapabilityPayload.ID,
                    QuickLitematicaEntityPlacementPayloads.CapabilityPayload.CODEC
            );
            PayloadTypeRegistry.playS2C().register(
                    QuickLitematicaEntityPlacementPayloads.ResultPayload.ID,
                    QuickLitematicaEntityPlacementPayloads.ResultPayload.CODEC
            );
        }
        ClientPlayNetworking.registerGlobalReceiver(
                QuickLitematicaEntityPlacementPayloads.CapabilityPayload.ID,
                (payload, context) -> receiveCapability(payload)
        );
        ClientPlayNetworking.registerGlobalReceiver(
                QuickLitematicaEntityPlacementPayloads.ResultPayload.ID,
                (payload, context) -> receiveResult(MinecraftClient.getInstance(), payload)
        );
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaEntityPlacement::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSession());
    }

    public static boolean openSelector(MinecraftClient client) {
        if (!canCollectCandidates(client) || client.currentScreen != null) {
            return false;
        }

        if (!isServerAvailable()) {
            sendHello();
        }
        client.setScreen(new QuickLitematicaEntityPlacementScreen(collectRayCandidates(client)));
        return true;
    }

    public static boolean requestPlacement(
            MinecraftClient client,
            Candidate candidate,
            List<Candidate> candidates
    ) {
        if (!QuickCraftConfigs.isEasyPlaceEntitiesEnabled() || !isServerAvailable() || candidate == null) {
            return false;
        }
        PlacementStatus status = evaluatePlacementStatuses(client, candidates)
                .getOrDefault(candidate, PlacementStatus.UNPLACED);
        if (status == PlacementStatus.MATCHED) {
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("quickcraft.entity_placement.already_placed"), true);
            }
            return true;
        }
        if (pendingRequests.values().stream().anyMatch(request -> request.key.equals(candidate.key()))) {
            return true;
        }

        return sendRequest(client, candidate);
    }

    public static boolean isServerAvailable() {
        return capability != null
                && capability.enabled
                && capability.version == QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION;
    }

    public static void tick(MinecraftClient client) {
        clientTick++;
        pendingRequests.entrySet().removeIf(entry -> clientTick - entry.getValue().createdTick > PENDING_TIMEOUT_TICKS);
        if (client.player == null) {
            clearSession();
            return;
        }
        // The hello is the mechanism that discovers server support, so it must not be gated by
        // canSend(): Fabric only reports a channel after the server has already declared it.
        if (!helloSent) {
            sendHello();
        }
    }

    private static void sendHello() {
        ClientPlayNetworking.send(new QuickLitematicaEntityPlacementPayloads.HelloPayload(
                QuickLitematicaEntityPlacementPayloads.PROTOCOL_VERSION,
                QuickLitematicaEntityPlacementPayloads.CLIENT_FEATURES,
                QuickLitematicaEntityPlacementPayloads.MAX_CLIENT_NBT_BYTES
        ));
        helloSent = true;
    }

    private static void receiveCapability(QuickLitematicaEntityPlacementPayloads.CapabilityPayload payload) {
        double reach = Math.max(0.0D, Math.min(payload.reach(), 64.0D));
        int maxNbtBytes = Math.max(0, Math.min(payload.maxNbtBytes(), QuickLitematicaEntityPlacementPayloads.MAX_CLIENT_NBT_BYTES));
        capability = new ServerCapability(
                payload.version(),
                payload.enabled(),
                reach,
                maxNbtBytes,
                payload.sessionToken()
        );
    }

    private static void receiveResult(MinecraftClient client, QuickLitematicaEntityPlacementPayloads.ResultPayload payload) {
        PendingRequest pending = pendingRequests.remove(payload.nonce());
        if (pending != null) {
            pendingRequests.values().removeIf(request -> request.key.equals(pending.key));
        }
        if (payload.status().equals("SUCCESS")) {
            if (pending != null && !payload.entityUuid().isBlank()) {
                try {
                    confirmedEntityUuids.put(pending.key, UUID.fromString(payload.entityUuid()));
                } catch (IllegalArgumentException ignored) {
                    // The UUID only improves client-side matching; the server result remains authoritative.
                }
            }
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("quickcraft.entity_placement.result.success"), true);
            }
            return;
        }
        if (client.player == null) {
            return;
        }
        String messageKey = payload.messageKey().isBlank()
                ? resultMessageKey(payload.status())
                : payload.messageKey();
        client.player.sendMessage(Text.translatable(messageKey), true);
    }

    private static String resultMessageKey(String status) {
        return switch (status) {
            case "DISABLED" -> "quickcraft.entity_placement.result.disabled";
            case "UNSUPPORTED_ENTITY" -> "quickcraft.entity_placement.result.unsupported_entity";
            case "OUT_OF_REACH" -> "quickcraft.entity_placement.result.out_of_reach";
            case "NO_MATERIAL" -> "quickcraft.entity_placement.result.no_material";
            case "INVALID_NBT" -> "quickcraft.entity_placement.result.invalid_nbt";
            case "COLLISION" -> "quickcraft.entity_placement.result.collision";
            case "PERMISSION_DENIED" -> "quickcraft.entity_placement.result.permission_denied";
            case "WORLD_RULE_BLOCKED" -> "quickcraft.entity_placement.result.world_rule_blocked";
            case "RATE_LIMITED" -> "quickcraft.entity_placement.result.rate_limited";
            case "REPLAYED_REQUEST" -> "quickcraft.entity_placement.result.replayed_request";
            case "INTERNAL_ERROR" -> "quickcraft.entity_placement.result.internal_error";
            default -> "quickcraft.entity_placement.result.unknown";
        };
    }

    static List<Candidate> collectCandidates(MinecraftClient client) {
        if (!canCollectCandidates(client)) {
            return List.of();
        }

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (placement == null || placement.ignoreEntities()) {
            return List.of();
        }

        LitematicaSchematic schematic = placement.getSchematic();
        List<Candidate> candidates = new ArrayList<>();
        for (var entry : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
            String region = entry.getKey();
            SubRegionPlacement subRegion = entry.getValue();
            if (subRegion.ignoreEntities()) {
                continue;
            }

            List<LitematicaSchematic.EntityInfo> entities = schematic.getEntityListForRegion(region);
            if (entities == null) {
                continue;
            }
            for (int index = 0; index < entities.size() && candidates.size() < MAX_SCAN_CANDIDATES; index++) {
                Candidate candidate = createCandidate(placement, subRegion, region, index, entities.get(index));
                if (candidate != null && DataManager.getRenderLayerRange().isPositionWithinRange(
                        (int) candidate.position.x,
                        (int) candidate.position.y,
                        (int) candidate.position.z
                )) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    static Map<Candidate, PlacementStatus> evaluatePlacementStatuses(
            MinecraftClient client,
            List<Candidate> candidates
    ) {
        return evaluatePlacement(client, candidates).statuses();
    }

    static PlacementEvaluation evaluatePlacement(
            MinecraftClient client,
            List<Candidate> candidates
    ) {
        if (client == null || client.world == null || candidates.isEmpty()) {
            return new PlacementEvaluation(Map.of(), List.of());
        }

        Set<Entity> claimedEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Candidate, PlacementStatus> statuses = new IdentityHashMap<>();
        Map<Candidate, List<Entity>> nearbyByCandidate = new IdentityHashMap<>();
        Box searchBox = candidates.getFirst().box.expand(Candidate.POSITION_TOLERANCE);
        for (int index = 1; index < candidates.size(); index++) {
            searchBox = searchBox.union(candidates.get(index).box.expand(Candidate.POSITION_TOLERANCE));
        }
        List<Entity> nearbyEntities = client.world.getOtherEntities(
                null,
                searchBox,
                entity -> entity.isAlive() && !(entity instanceof PlayerEntity)
        );
        for (Candidate candidate : candidates) {
            Box candidateBox = candidate.box.expand(Candidate.POSITION_TOLERANCE);
            List<Entity> nearby = new ArrayList<>(nearbyEntities.stream()
                    .filter(entity -> candidateBox.intersects(entity.getBoundingBox()))
                    .toList());
            nearby.sort(Comparator.comparingDouble(entity -> entity.getPos().squaredDistanceTo(candidate.position)));
            nearbyByCandidate.put(candidate, nearby);
        }

        assignPlacementStatus(candidates, nearbyByCandidate, claimedEntities, statuses, PlacementStatus.MATCHED);
        assignPlacementStatus(candidates, nearbyByCandidate, claimedEntities, statuses, PlacementStatus.MISMATCHED);
        assignPlacementStatus(candidates, nearbyByCandidate, claimedEntities, statuses, PlacementStatus.WRONG);
        candidates.forEach(candidate -> statuses.putIfAbsent(candidate, PlacementStatus.UNPLACED));
        Set<Entity> allNearby = Collections.newSetFromMap(new IdentityHashMap<>());
        nearbyByCandidate.values().forEach(allNearby::addAll);
        List<Entity> excess = allNearby.stream()
                .filter(entity -> !claimedEntities.contains(entity))
                .toList();
        return new PlacementEvaluation(statuses, excess);
    }

    private static void assignPlacementStatus(
            List<Candidate> candidates,
            Map<Candidate, List<Entity>> nearbyByCandidate,
            Set<Entity> claimedEntities,
            Map<Candidate, PlacementStatus> statuses,
            PlacementStatus wantedStatus
    ) {
        for (Candidate candidate : candidates) {
            if (statuses.containsKey(candidate)) {
                continue;
            }
            Entity assigned = nearbyByCandidate.getOrDefault(candidate, List.of()).stream()
                    .filter(entity -> !claimedEntities.contains(entity))
                    .filter(entity -> candidate.classifyEntity(entity) == wantedStatus)
                    .findFirst()
                    .orElse(null);
            if (assigned != null) {
                assigned.streamSelfAndPassengers().forEach(claimedEntities::add);
                statuses.put(candidate, wantedStatus);
            }
        }
    }

    static ExcessDisplay createExcessDisplay(Entity entity, List<Candidate> candidates) {
        List<ItemStack> actualMaterials = getMaterials(entity.getType(), writeEntityNbt(entity));
        if (!actualMaterials.isEmpty()) {
            return new ExcessDisplay(actualMaterials.getFirst().copy());
        }
        Candidate representative = candidates.stream()
                .filter(candidate -> Registries.ENTITY_TYPE.get(candidate.entityType) == entity.getType())
                .min(Comparator.comparingDouble(candidate -> candidate.position.squaredDistanceTo(entity.getPos())))
                .orElseGet(() -> candidates.stream()
                        .min(Comparator.comparingDouble(candidate -> candidate.position.squaredDistanceTo(entity.getPos())))
                        .orElse(null));
        return new ExcessDisplay(representative == null ? ItemStack.EMPTY : representative.material().copy());
    }

    private static List<Candidate> collectRayCandidates(MinecraftClient client) {
        if (client.player == null) {
            return List.of();
        }
        double reach = capability != null ? capability.reach : DEFAULT_REACH;
        Vec3d start = client.player.getCameraPosVec(1.0F);
        Vec3d end = start.add(client.player.getRotationVec(1.0F).multiply(reach));
        return collectCandidates(client).stream()
                .filter(candidate -> candidate.box.raycast(start, end).isPresent())
                .sorted(Comparator.comparingDouble(candidate -> candidate.position.squaredDistanceTo(start)))
                .limit(MAX_SELECTOR_CANDIDATES)
                .toList();
    }

    private static Candidate createCandidate(
            SchematicPlacement placement,
            SubRegionPlacement subRegion,
            String region,
            int index,
            LitematicaSchematic.EntityInfo entity
    ) {
        NbtCompound nbt = entity.nbt.copy();
        if (!normalizeEntityTreeIds(nbt, 0)) {
            return null;
        }
        Identifier entityId = Identifier.tryParse(nbt.getString("id", ""));
        EntityType<?> type = Registries.ENTITY_TYPE.get(entityId);
        List<ItemStack> materials = getMaterials(type, nbt);
        if (materials.isEmpty()) {
            return null;
        }

        Vec3d position = PositionUtils.getTransformedPosition(entity.posVec, placement.getMirror(), placement.getRotation());
        position = PositionUtils.getTransformedPosition(position, subRegion.getMirror(), subRegion.getRotation());
        BlockPos blockOffset = placement.getOrigin().add(
                PositionUtils.getTransformedBlockPos(subRegion.getPos(), placement.getMirror(), placement.getRotation())
        );
        position = position.add(Vec3d.of(blockOffset));
        transformEntityTreeState(nbt, placement, subRegion, blockOffset, 0);
        return new Candidate(
                region,
                index,
                entityId,
                position,
                type.getSpawnBox(position.x, position.y, position.z),
                nbt,
                materials,
                readRotation(nbt, 0),
                readRotation(nbt, 1),
                readMotion(nbt)
        );
    }

    private static List<ItemStack> getMaterials(EntityType<?> type, NbtCompound nbt) {
        List<ItemStack> materials = new ArrayList<>();
        int[] entityCount = {0};
        return appendEntityTreeMaterials(type, nbt, materials, 0, entityCount)
                ? mergeMaterials(materials)
                : List.of();
    }

    private static boolean appendEntityTreeMaterials(
            EntityType<?> type,
            NbtCompound nbt,
            List<ItemStack> materials,
            int depth,
            int[] entityCount
    ) {
        if (depth > MAX_ENTITY_TREE_DEPTH || ++entityCount[0] > MAX_ENTITY_TREE_SIZE) {
            return false;
        }
        ItemStack baseMaterial = getBaseMaterial(type, nbt);
        if (baseMaterial.isEmpty()) {
            return false;
        }
        materials.add(baseMaterial);

        if (type != EntityType.ITEM && !appendStoredItem(materials, nbt, "Item")) {
            return false;
        }
        for (String key : List.of("SaddleItem", "ArmorItem", "DecorItem", "body_armor_item")) {
            if (!appendStoredItem(materials, nbt, key)) {
                return false;
            }
        }
        if (isChestedHorse(type, nbt)) {
            materials.add(new ItemStack(Items.CHEST));
        }
        for (String key : List.of("Inventory", "ArmorItems", "HandItems")) {
            if (!appendStoredItems(materials, nbt, key)) {
                return false;
            }
        }
        int containerCapacity = containerCapacity(type, nbt);
        if (containerCapacity == Integer.MIN_VALUE
                || !appendStoredItems(materials, nbt, "Items", containerCapacity)) {
            return false;
        }

        NbtList passengers = nbt.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            NbtCompound passenger = passengers.getCompoundOrEmpty(index);
            Identifier id = Identifier.tryParse(passenger.getString("id", ""));
            if (id == null || !Registries.ENTITY_TYPE.containsId(id)
                    || !appendEntityTreeMaterials(Registries.ENTITY_TYPE.get(id), passenger,
                    materials, depth + 1, entityCount)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack getBaseMaterial(EntityType<?> type, NbtCompound nbt) {
        if (type == EntityType.ITEM && nbt.contains("Item")) {
            return decodeItemStack(nbt.getCompoundOrEmpty("Item"))
                    .orElse(ItemStack.EMPTY);
        }
        SpawnEggItem spawnEgg = SpawnEggItem.forEntity(type);
        if (spawnEgg != null) {
            return new ItemStack(spawnEgg);
        }
        Identifier entityId = Registries.ENTITY_TYPE.getId(type);
        String entityPath = entityId.getPath();
        Item item = switch (entityPath) {
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
            case "boat" -> getBoatItem(nbt.getString("Type", ""), false);
            case "chest_boat" -> getBoatItem(nbt.getString("Type", ""), true);
            default -> getSplitBoatItem(entityId);
        };
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static NbtCompound writeEntityNbt(Entity entity) {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, entity.getRegistryManager());
        entity.saveSelfData(view);
        return view.getNbt();
    }

    private static Optional<ItemStack> decodeItemStack(NbtCompound nbt) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return Optional.empty();
        }
        return ItemStack.CODEC.parse(client.world.getRegistryManager().getOps(NbtOps.INSTANCE), nbt).result();
    }

    private static boolean normalizeEntityTreeIds(NbtCompound nbt, int depth) {
        if (depth > MAX_ENTITY_TREE_DEPTH) {
            return false;
        }
        Identifier id = Identifier.tryParse(nbt.getString("id", ""));
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

        NbtList passengers = nbt.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            if (!normalizeEntityTreeIds(passengers.getCompoundOrEmpty(index), depth + 1)) {
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
        String path = getSplitBoatPath(nbt.getString("Type", ""), chestBoat);
        return path == null ? null : Identifier.ofVanilla(path);
    }

    private static Item getSplitBoatItem(Identifier entityId) {
        return isSplitBoatPath(entityId.getPath()) && Registries.ITEM.containsId(entityId)
                ? Registries.ITEM.get(entityId)
                : null;
    }

    private static boolean isSplitBoatPath(String path) {
        return path.endsWith("_boat") || path.endsWith("_chest_boat")
                || path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    private static Item getBoatItem(String type, boolean chestBoat) {
        String path = getSplitBoatPath(type, chestBoat);
        return path == null ? null : getSplitBoatItem(Identifier.ofVanilla(path));
    }

    private static String getSplitBoatPath(String type, boolean chestBoat) {
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

    private static boolean appendStoredItem(List<ItemStack> materials, NbtCompound entityNbt, String key) {
        if (!entityNbt.contains(key)) {
            return true;
        }
        NbtCompound itemNbt = entityNbt.getCompoundOrEmpty(key);
        if (itemNbt.isEmpty()) {
            return true;
        }
        return decodeItemStack(itemNbt).map(stack -> {
            materials.add(stack);
            return true;
        }).orElse(false);
    }

    private static boolean appendStoredItems(
            List<ItemStack> materials,
            NbtCompound entityNbt,
            String key
    ) {
        return appendStoredItems(materials, entityNbt, key, 0);
    }

    private static boolean appendStoredItems(
            List<ItemStack> materials,
            NbtCompound entityNbt,
            String key,
            int capacity
    ) {
        if (!entityNbt.contains(key)) {
            return true;
        }
        NbtList items = entityNbt.getListOrEmpty(key);
        if (capacity < 0 && !items.isEmpty()) {
            return false;
        }
        Set<Integer> slots = capacity > 0 ? new HashSet<>() : null;
        for (int index = 0; index < items.size(); index++) {
            NbtCompound itemNbt = items.getCompoundOrEmpty(index);
            if (itemNbt.isEmpty()) {
                continue;
            }
            if (capacity > 0) {
                int slot = itemNbt.getByte("Slot", (byte) 0) & 255;
                if (!itemNbt.contains("Slot") || slot >= capacity || !slots.add(slot)) {
                    return false;
                }
            }
            if (decodeItemStack(itemNbt).map(stack -> {
                materials.add(stack);
                return true;
            }).orElse(false) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean isChestedHorse(EntityType<?> type, NbtCompound nbt) {
        String path = Registries.ENTITY_TYPE.getId(type).getPath();
        return switch (path) {
            case "donkey", "mule", "llama", "trader_llama" -> nbt.getBoolean("ChestedHorse", false);
            default -> false;
        };
    }

    private static int containerCapacity(EntityType<?> type, NbtCompound nbt) {
        String path = Registries.ENTITY_TYPE.getId(type).getPath();
        if (path.endsWith("_chest_boat") || path.endsWith("_chest_raft")) {
            return 27;
        }
        return switch (path) {
            case "hopper_minecart" -> 5;
            case "chest_minecart", "chest_boat" -> 27;
            case "donkey", "mule" -> nbt.getBoolean("ChestedHorse", false) ? 15 : -1;
            case "llama", "trader_llama" -> llamaContainerCapacity(nbt);
            default -> -1;
        };
    }

    private static int llamaContainerCapacity(NbtCompound nbt) {
        if (!nbt.getBoolean("ChestedHorse", false)) {
            return -1;
        }
        int strength = nbt.getInt("Strength", 0);
        return strength >= 1 && strength <= 5 ? strength * 3 : Integer.MIN_VALUE;
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
        return List.copyOf(merged);
    }

    private static boolean canCollectCandidates(MinecraftClient client) {
        return QuickCraftConfigs.isEasyPlaceEntitiesEnabled()
                && client != null
                && client.player != null
                && client.world != null;
    }

    private static boolean sendRequest(MinecraftClient client, Candidate candidate) {
        if (client.world == null
                || client.player == null
                || capability == null
                || candidate.nbt.getSizeInBytes() > capability.maxNbtBytes) {
            return false;
        }
        long nonce = ThreadLocalRandom.current().nextLong();
        pendingRequests.put(nonce, new PendingRequest(candidate.key(), clientTick));
        ClientPlayNetworking.send(new QuickLitematicaEntityPlacementPayloads.RequestPayload(
                capability.sessionToken,
                nonce,
                client.world.getRegistryKey().getValue(),
                candidate.position,
                candidate.entityType,
                candidate.region,
                candidate.index,
                candidate.yaw,
                candidate.pitch,
                candidate.velocity,
                QuickCraftConfigs.isCreativeEntityPlacementAllowed() && client.player.isCreative(),
                candidate.nbt.copy()
        ));
        return true;
    }

    private static void clearSession() {
        capability = null;
        helloSent = false;
        pendingRequests.clear();
        confirmedEntityUuids.clear();
    }

    private static Vec3d transformVector(Vec3d vector, SchematicPlacement placement, SubRegionPlacement subRegion) {
        Vec3d transformed = transformVector(vector, placement.getMirror(), placement.getRotation());
        return transformVector(transformed, subRegion.getMirror(), subRegion.getRotation());
    }

    private static Vec3d transformVector(Vec3d vector, net.minecraft.util.BlockMirror mirror, net.minecraft.util.BlockRotation rotation) {
        return PositionUtils.getTransformedPosition(vector, mirror, rotation)
                .subtract(PositionUtils.getTransformedPosition(Vec3d.ZERO, mirror, rotation));
    }

    private static void transformEntityTreeState(
            NbtCompound nbt,
            SchematicPlacement placement,
            SubRegionPlacement subRegion,
            BlockPos blockOffset,
            int depth
    ) {
        if (depth > MAX_ENTITY_TREE_DEPTH) {
            return;
        }
        float yaw = readRotation(nbt, 0);
        Vec3d forward = transformVector(Vec3d.fromPolar(0.0F, yaw), placement, subRegion);
        float transformedYaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        NbtList rotation = new NbtList();
        rotation.add(NbtFloat.of(transformedYaw));
        rotation.add(NbtFloat.of(readRotation(nbt, 1)));
        nbt.put("Rotation", rotation);

        Vec3d velocity = transformVector(readMotion(nbt), placement, subRegion);
        NbtList motion = new NbtList();
        motion.add(NbtDouble.of(velocity.x));
        motion.add(NbtDouble.of(velocity.y));
        motion.add(NbtDouble.of(velocity.z));
        nbt.put("Motion", motion);
        transformFacing(nbt, "Facing", placement, subRegion);
        transformFacing(nbt, "facing", placement, subRegion);
        transformDecorationPosition(nbt, placement, subRegion, blockOffset);

        NbtList passengers = nbt.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            transformEntityTreeState(passengers.getCompoundOrEmpty(index), placement, subRegion, blockOffset, depth + 1);
        }
    }

    private static void transformDecorationPosition(
            NbtCompound nbt,
            SchematicPlacement placement,
            SubRegionPlacement subRegion,
            BlockPos blockOffset
    ) {
        if (!nbt.contains("TileX") || !nbt.contains("TileY") || !nbt.contains("TileZ")) {
            return;
        }
        BlockPos position = new BlockPos(
                nbt.getInt("TileX", 0),
                nbt.getInt("TileY", 0),
                nbt.getInt("TileZ", 0)
        );
        position = PositionUtils.getTransformedBlockPos(position, placement.getMirror(), placement.getRotation());
        position = PositionUtils.getTransformedBlockPos(position, subRegion.getMirror(), subRegion.getRotation());
        position = position.add(blockOffset);
        nbt.putInt("TileX", position.getX());
        nbt.putInt("TileY", position.getY());
        nbt.putInt("TileZ", position.getZ());
    }

    private static void transformFacing(
            NbtCompound nbt,
            String key,
            SchematicPlacement placement,
            SubRegionPlacement subRegion
    ) {
        if (!nbt.contains(key)) {
            return;
        }
        Direction direction = Direction.byIndex(nbt.getByte(key, (byte) 0));
        Vec3d transformed = transformVector(Vec3d.of(direction.getVector()), placement, subRegion);
        nbt.putByte(key, (byte) Direction.getFacing(transformed).getIndex());
    }

    private static float readRotation(NbtCompound nbt, int index) {
        if (!nbt.contains("Rotation")) {
            return 0.0F;
        }
        var rotation = nbt.getListOrEmpty("Rotation");
        return rotation.size() > index ? rotation.getFloat(index, 0.0F) : 0.0F;
    }

    private static Vec3d readMotion(NbtCompound nbt) {
        if (!nbt.contains("Motion")) {
            return Vec3d.ZERO;
        }
        var motion = nbt.getListOrEmpty("Motion");
        return motion.size() == 3
                ? new Vec3d(
                        motion.getDouble(0, 0.0D),
                        motion.getDouble(1, 0.0D),
                        motion.getDouble(2, 0.0D)
                )
                : Vec3d.ZERO;
    }

    static final class Candidate {
        static final double POSITION_TOLERANCE = 0.2D;
        private static final float ROTATION_TOLERANCE = 5.0F;
        private final String region;
        private final int index;
        private final Identifier entityType;
        private final Vec3d position;
        private final Box box;
        private final NbtCompound nbt;
        private final List<ItemStack> materials;
        private final float yaw;
        private final float pitch;
        private final Vec3d velocity;

        private Candidate(
                String region,
                int index,
                Identifier entityType,
                Vec3d position,
                Box box,
                NbtCompound nbt,
                List<ItemStack> materials,
                float yaw,
                float pitch,
                Vec3d velocity
        ) {
            this.region = region;
            this.index = index;
            this.entityType = entityType;
            this.position = position;
            this.box = box;
            this.nbt = nbt;
            this.materials = materials;
            this.yaw = yaw;
            this.pitch = pitch;
            this.velocity = velocity;
        }

        private boolean sameEntity(Candidate other) {
            return region.equals(other.region) && index == other.index && entityType.equals(other.entityType);
        }

        private String key() {
            return region + "#" + index + "#" + entityType + "@" + position;
        }

        ItemStack material() {
            return materials.getFirst();
        }

        Identifier entityType() {
            return entityType;
        }

        Box box() {
            return box;
        }

        boolean hasClientMaterials(MinecraftClient client) {
            if (client.player == null) {
                return false;
            }
            if (client.player.isCreative() && QuickCraftConfigs.isCreativeEntityPlacementAllowed()) {
                return true;
            }
            return materials.stream().allMatch(required -> client.player.getInventory().getMainStacks().stream()
                    .filter(stack -> ItemStack.areItemsAndComponentsEqual(stack, required))
                    .mapToInt(ItemStack::getCount)
                    .sum() >= required.getCount());
        }

        List<Text> getTooltip(PlacementStatus status) {
            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.translatable(switch (status) {
                case MATCHED -> "quickcraft.entity_placement.status.matched";
                case MISMATCHED -> "quickcraft.entity_placement.status.mismatched";
                case WRONG -> "quickcraft.entity_placement.status.wrong";
                case UNPLACED -> "quickcraft.entity_placement.status.unplaced";
            }));
            int passengerCount = countEntityTree(nbt) - 1;
            if (passengerCount > 0) {
                tooltip.add(Text.translatable("quickcraft.entity_placement.passengers", passengerCount));
            }
            for (ItemStack material : materials) {
                tooltip.add(Text.literal(material.getCount() + " × ").append(material.getName()));
            }
            return tooltip;
        }

        private static int countEntityTree(NbtCompound root) {
            int count = 1;
            NbtList passengers = root.getListOrEmpty("Passengers");
            for (int index = 0; index < passengers.size(); index++) {
                count += countEntityTree(passengers.getCompoundOrEmpty(index));
            }
            return count;
        }

        List<ItemStack> getStoredStacks(MinecraftClient client, int size) {
            List<ItemStack> stacks = new ArrayList<>(Collections.nCopies(size, ItemStack.EMPTY));
            if (client == null || client.world == null || !nbt.contains("Items")) {
                return stacks;
            }
            var items = nbt.getListOrEmpty("Items");
            for (int i = 0; i < items.size(); i++) {
                var itemNbt = items.getCompoundOrEmpty(i);
                int slot = itemNbt.getByte("Slot", (byte) 0) & 255;
                if (slot >= 0 && slot < size) {
                    decodeItemStack(itemNbt)
                            .ifPresent(stack -> stacks.set(slot, stack));
                }
            }
            return stacks;
        }

        ContainerPreview getContainerPreview() {
            String path = entityType.getPath();
            if (path.equals("hopper_minecart")) {
                return new ContainerPreview(ContainerPreviewType.HOPPER, 5);
            }
            if (path.equals("chest_minecart") || path.equals("chest_boat")
                    || path.endsWith("_chest_boat") || path.endsWith("_chest_raft")) {
                return new ContainerPreview(ContainerPreviewType.GENERIC, 27);
            }
            return null;
        }

        private PlacementStatus classifyEntity(Entity entity) {
            if (entity.getType() != Registries.ENTITY_TYPE.get(entityType)) {
                return PlacementStatus.WRONG;
            }
            if (entity.getPos().squaredDistanceTo(position) > POSITION_TOLERANCE * POSITION_TOLERANCE
                    || Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(entity.getYaw() - yaw)) > ROTATION_TOLERANCE
                    || Math.abs(entity.getPitch() - pitch) > ROTATION_TOLERANCE) {
                return PlacementStatus.MISMATCHED;
            }
            UUID confirmedUuid = confirmedEntityUuids.get(key());
            if (confirmedUuid != null && confirmedUuid.equals(entity.getUuid())) {
                return PlacementStatus.MATCHED;
            }

            NbtCompound actual = writeEntityNbt(entity);
            return containsProjectedData(normalizeForComparison(nbt), normalizeForComparison(actual))
                    && matchesPassengerTree(nbt, entity)
                    ? PlacementStatus.MATCHED
                    : PlacementStatus.MISMATCHED;
        }

        private static boolean matchesPassengerTree(NbtCompound expected, Entity actual) {
            NbtList expectedPassengers = expected.getListOrEmpty("Passengers");
            List<Entity> actualPassengers = actual.getPassengerList();
            if (expectedPassengers.size() != actualPassengers.size()) {
                return false;
            }
            for (int index = 0; index < expectedPassengers.size(); index++) {
                NbtCompound expectedPassenger = expectedPassengers.getCompoundOrEmpty(index);
                Entity actualPassenger = actualPassengers.get(index);
                Identifier expectedId = Identifier.tryParse(expectedPassenger.getString("id", ""));
                if (expectedId == null
                        || actualPassenger.getType() != Registries.ENTITY_TYPE.get(expectedId)
                        || !containsProjectedData(
                        normalizeForComparison(expectedPassenger),
                        normalizeForComparison(writeEntityNbt(actualPassenger)))
                        || !matchesPassengerTree(expectedPassenger, actualPassenger)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean containsProjectedData(NbtCompound expected, NbtCompound actual) {
            for (String key : expected.getKeys()) {
                if (expected.get(key).equals(actual.get(key)) == false) {
                    return false;
                }
            }
            return true;
        }

        private static NbtCompound normalizeForComparison(NbtCompound source) {
            NbtCompound normalized = source.copy();
            normalized.remove("Pos");
            normalized.remove("Rotation");
            normalized.remove("Motion");
            normalized.remove("UUID");
            normalized.remove("UUIDMost");
            normalized.remove("UUIDLeast");
            normalized.remove("FallDistance");
            normalized.remove("Fire");
            normalized.remove("Air");
            normalized.remove("OnGround");
            normalized.remove("PortalCooldown");
            normalized.remove("Health");
            normalized.remove("HurtTime");
            normalized.remove("HurtByTimestamp");
            normalized.remove("DeathTime");
            normalized.remove("Age");
            normalized.remove("PickupDelay");
            normalized.remove("Thrower");
            normalized.remove("Dimension");
            normalized.remove("Passengers");
            return normalized;
        }
    }

    enum PlacementStatus {
        UNPLACED,
        MATCHED,
        MISMATCHED,
        WRONG
    }

    enum ContainerPreviewType {
        HOPPER,
        GENERIC
    }

    record ContainerPreview(ContainerPreviewType type, int size) {
    }

    record PlacementEvaluation(
            Map<Candidate, PlacementStatus> statuses,
            List<Entity> excessEntities
    ) {
    }

    record ExcessDisplay(ItemStack stack) {
    }

    private record ServerCapability(int version, boolean enabled, double reach, int maxNbtBytes, String sessionToken) {
    }

    private record PendingRequest(String key, long createdTick) {
    }
}
