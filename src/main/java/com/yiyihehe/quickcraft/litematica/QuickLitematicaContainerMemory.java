package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.LitematicaSchematicBlocksAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;

/** 原始对照与实际投影数据分开保存；分别应用容器 Items 规则和方块状态规则，随后统一重建投影。 */
public final class QuickLitematicaContainerMemory {
    private static final Map<LitematicaSchematic, State> STATES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean schedulingRebuild;
    private static volatile long revision;

    private QuickLitematicaContainerMemory() { }

    public static LitematicaSchematic original(LitematicaSchematic schematic) {
        return originalState(schematic).original;
    }

    private static State originalState(LitematicaSchematic schematic) {
        return STATES.computeIfAbsent(schematic, source -> new State(
                QuickLitematicaContainerMaterialsAccess.copySchematic(source)));
    }

    static long originalVersion(LitematicaSchematic schematic) {
        return originalState(schematic).sourceVersion;
    }

    static long[] originalBlockCounts(LitematicaSchematic schematic, String region) {
        var state = originalState(schematic);
        return state.blockCounts.computeIfAbsent(region, name ->
                QuickLitematicaProjectionMaterials.countPaletteStates(state.original.getSubRegionContainer(name)));
    }

    public static long revision() { return revision; }

    public static void schematicReloaded(LitematicaSchematic schematic) {
        synchronized (QuickLitematicaGlobalReplacements.class) {
            if (STATES.remove(schematic) != null) {
                revision++;
            }
        }
    }

    public static final class Mode9Edit {
        private final LitematicaSchematic schematic;
        private final LitematicaSchematic displayed;
        private final State state;
        private LitematicaBlockStateContainer displayedRegion;
        private LitematicaBlockStateContainer originalRegion;
        private boolean changed;

        public Mode9Edit(LitematicaSchematic schematic) {
            this.schematic = schematic;
            this.displayed = QuickLitematicaGlobalReplacements.runtime(schematic);
            this.state = STATES.get(schematic);
        }

        public void selectRegion(String region) {
            this.displayedRegion = this.displayed.getSubRegionContainer(region);
            this.originalRegion = this.state == null ? null : this.state.original.getSubRegionContainer(region);
        }

        public LitematicaBlockStateContainer displayedRegion(LitematicaBlockStateContainer live) {
            return this.displayedRegion == null ? live : this.displayedRegion;
        }

        public void record(int x, int y, int z, BlockState target, boolean liveChanged) {
            if (this.originalRegion == null) { return; }
            synchronized (QuickLitematicaGlobalReplacements.class) {
                // 只同步模式 9 写入的位置，避免把其他格子上的规则结果固化进来源。
                if (this.originalRegion.get(x, y, z) != target) {
                    this.originalRegion.set(x, y, z, target);
                    this.changed = true;
                }
                this.changed |= liveChanged;
            }
        }

        public void finish(int totalBlocks) {
            if (!this.changed) { return; }
            synchronized (QuickLitematicaGlobalReplacements.class) {
                var original = this.state.original.getMetadata();
                original.setTotalBlocks(totalBlocks);
                original.setTimeModifiedToNow();
                this.state.blockCounts.clear();
                this.state.sourceVersion = ++revision;
            }
            if (this.state.materialized || this.state.projectionMaterialized) {
                update(this.schematic, this.state.context, true);
            }
        }
    }

    public static String appliedContext(LitematicaSchematic schematic, String fallback) {
        State state = STATES.get(schematic);
        return state != null && state.configured != null ? state.context : fallback;
    }

    public static String effectiveContext(LitematicaSchematic schematic, String requested) {
        State state = STATES.get(schematic);
        return state != null && state.materialized
                && !QuickLitematicaContainerReplacements.hasValidRules(QuickLitematicaContainerReplacements.Scope.CONTAINER, requested)
                ? state.context : requested;
    }

    public static void update(SchematicPlacement placement) {
        if (schedulingRebuild) { return; }
        QuickLitematicaContainerReplacements.migrateContainerContext(
                QuickLitematicaMaterialLists.getReplacementContextKey(placement),
                QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement));
        QuickLitematicaContainerReplacements.migrateProjectionContext(
                QuickLitematicaMaterialLists.getReplacementContextKey(placement),
                QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement));
        update(placement.getSchematic(), placement.getEnabledRelativeSubRegionPlacements().keySet(),
                QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement));
    }

    public static void update(LitematicaSchematic schematic, Collection<String> regions, String context) {
        update(schematic, context, false);
    }

    private static void update(LitematicaSchematic schematic, String context, boolean sourceEdited) {
        if (net.minecraft.client.MinecraftClient.getInstance().world == null) { return; }
        boolean hasContainerRules = !context.isBlank() && QuickLitematicaContainerReplacements.hasValidRules(
                QuickLitematicaContainerReplacements.Scope.CONTAINER, context);
        boolean hasProjectionRules = QuickLitematicaContainerReplacements.hasValidRules(
                QuickLitematicaContainerReplacements.Scope.PROJECTION, context);
        boolean hasRules = hasContainerRules || hasProjectionRules;
        if (!hasRules && !STATES.containsKey(schematic)) { return; }
        original(schematic);
        State state = STATES.get(schematic);
        // 同一原理图对象被多个投影共用时，查看没有独立规则的投影不能撤销已写入的内容。
        if (QuickLitematicaContainerReplacements.enabled() && state.materialized && !context.equals(state.context) && !hasRules) { return; }
        List<String> configured = List.copyOf(QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS.getStrings());
        List<String> projectionConfigured = List.copyOf(QuickCraftConfigs.ProjectionTools.PROJECTION_MATERIAL_REPLACEMENTS.getStrings());
        List<String> selectedRegions = new ArrayList<>(schematic.getAreas().keySet());
        selectedRegions.sort(String::compareTo);
        if (!sourceEdited && context.equals(state.context) && selectedRegions.equals(state.regions) && configured.equals(state.configured) && projectionConfigured.equals(state.projectionConfigured) && state.enabled == QuickLitematicaContainerReplacements.enabled()) { return; }
        boolean rewrite = hasRules || state.materialized || state.projectionMaterialized;
        if (rewrite) {
            applyReplacements(schematic, state, selectedRegions, context, hasContainerRules, hasProjectionRules, sourceEdited);
            revision++;
        }
        state.enabled = QuickLitematicaContainerReplacements.enabled();
        state.context = context;
        state.regions = selectedRegions;
        state.configured = configured;
        state.projectionConfigured = projectionConfigured;
        state.materialized = hasContainerRules;
        state.projectionMaterialized = hasProjectionRules;
        if (rewrite) {
            schedulingRebuild = true;
            try {
                for (var placement : DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic)) {
                    if (placement.hasVerifier()) { placement.getSchematicVerifier().reset(); }
                }
                DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(schematic);
            } finally { schedulingRebuild = false; }
        }
    }

    private static void applyReplacements(LitematicaSchematic schematic, State state, List<String> regions,
                                          String context, boolean replaceItems, boolean replaceBlocks, boolean sourceEdited) {
        Map<BlockState, Optional<ItemStack>> targets = new HashMap<>();
        Map<String, RegionUpdate> updates = new LinkedHashMap<>();
        Set<String> replacedRegions = new HashSet<>();
        for (String region : regions) {
            var original = state.original.getSubRegionContainer(region);
            var blockEntities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(state.original, region);
            if (blockEntities != null) {
                var copies = new HashMap<BlockPos, NbtCompound>();
                blockEntities.forEach((pos, nbt) -> copies.put(pos, nbt.copy()));
                blockEntities = copies;
            }
            var originalEntities = state.original.getEntityListForRegion(region);
            var entities = replaceItems && originalEntities != null ? new ArrayList<>(originalEntities) : originalEntities;
            if (replaceItems) {
                QuickLitematicaContainerMaterials.replaceContainerItems(original, blockEntities, entities, region, context, false);
            }
            LitematicaBlockStateContainer blocks = null;
            if (original != null) {
                var mapped = replaceBlocks ? QuickLitematicaProjectionMaterials.replaceRegionBlocks(original, blockEntities,
                        block -> targets.computeIfAbsent(block, key -> QuickLitematicaProjectionMaterials.projectionTarget(key, context)))
                        : original;
                if (mapped != original) {
                    blocks = mapped;
                    replacedRegions.add(region);
                } else if (sourceEdited || state.replacedBlockRegions.contains(region)) {
                    // 撤销规则时恢复独立的方块存储，避免投影编辑改写原始对照。
                    blocks = QuickLitematicaProjectionMaterials.mapStates(original, block -> block);
                }
            }
            updates.put(region, new RegionUpdate(blocks, blockEntities, entities));
        }

        var containers = ((LitematicaSchematicBlocksAccessor) schematic).quickcraft$getBlockContainers();
        updates.forEach((region, update) -> {
            if (update.blocks() != null) { containers.put(region, update.blocks()); }
            if (update.blockEntities() != null) {
                QuickLitematicaContainerMaterialsAccess.setBlockEntities(schematic, region, update.blockEntities());
            }
            copyEntityItems(update.entities(), schematic.getEntityListForRegion(region));
        });
        state.replacedBlockRegions = replacedRegions;
    }

    private record RegionUpdate(LitematicaBlockStateContainer blocks, Map<BlockPos, NbtCompound> blockEntities,
                                List<EntityInfo> entities) { }

    static void copyContainerItems(LitematicaSchematic source, LitematicaSchematic target) {
        for (String region : source.getAreas().keySet()) {
            var originals = QuickLitematicaContainerMaterialsAccess.getBlockEntities(source, region);
            var live = QuickLitematicaContainerMaterialsAccess.getBlockEntities(target, region);
            if (originals != null && live != null) {
                for (var entry : originals.entrySet()) {
                    var destination = live.get(entry.getKey());
                    if (destination != null && entry.getValue().contains("Items")) {
                        destination.put("Items", entry.getValue().get("Items").copy());
                    }
                }
            }
            if (live != null) { QuickLitematicaContainerMaterialsAccess.setBlockEntities(target, region, live); }
            copyEntityItems(source.getEntityListForRegion(region), target.getEntityListForRegion(region));
        }
    }

    private static void copyEntityItems(List<EntityInfo> source, List<EntityInfo> target) {
        if (source == null || target == null) { return; }
        for (int index = 0; index < Math.min(source.size(), target.size()); index++) {
            var nbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(source.get(index));
            if (!nbt.contains("Items")) { continue; }
            var destination = QuickLitematicaContainerMaterialsAccess.getEntityNbt(target.get(index));
            var items = nbt.get("Items");
            if (!items.equals(destination.get("Items"))) {
                destination.put("Items", items.copy());
                QuickLitematicaContainerMaterialsAccess.setEntityNbt(target, index, destination);
            }
        }
    }

    public static void refreshAll() {
        for (var entry : snapshotStates()) {
            update(entry.getKey(), entry.getValue().regions, entry.getValue().context);
        }
        revision++;
    }

    public static void rulesChanged(String context) {
        // 文件材料表可能使用另一个对象，尚未替换的已加载对象也必须同步。
        for (var schematic : new ArrayList<>(SchematicHolder.getInstance().getAllSchematics())) {
            if (context.equals(QuickLitematicaMaterialLists.getContainerReplacementContextKey(schematic))) {
                update(schematic, schematic.getAreas().keySet(), context);
            }
        }
        for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (context.equals(QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement))) {
                update(placement);
            }
        }
        for (var entry : snapshotStates()) {
            if (context.equals(entry.getValue().context)) {
                update(entry.getKey(), entry.getValue().regions, context);
            }
        }
    }

    public static boolean isMaterialized(String context) {
        for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (context.equals(QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement))) {
                State state = STATES.get(placement.getSchematic());
                if (state != null && state.materialized) { return true; }
            }
        }
        return snapshotStates().stream().anyMatch(entry -> entry.getValue().materialized && context.equals(entry.getValue().context));
    }

    private static List<Map.Entry<LitematicaSchematic, State>> snapshotStates() {
        synchronized (STATES) {
            return STATES.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
        }
    }

    private static final class State {
        private final LitematicaSchematic original;
        private final Map<String, long[]> blockCounts = new HashMap<>();
        private long sourceVersion = revision;
        private Set<String> replacedBlockRegions = Set.of();
        private String context = "";
        private List<String> regions = List.of();
        private List<String> configured;
        private List<String> projectionConfigured;
        private boolean enabled;
        private boolean materialized;
        private boolean projectionMaterialized;
        private State(LitematicaSchematic original) { this.original = original; }
    }
}
