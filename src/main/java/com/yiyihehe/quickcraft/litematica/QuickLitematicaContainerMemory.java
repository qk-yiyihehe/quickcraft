package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 原始对照与实际投影数据分开保存；分别应用容器 Items 规则和方块状态规则，随后统一重建投影。 */
public final class QuickLitematicaContainerMemory {
    private static final Map<LitematicaSchematic, State> STATES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean schedulingRebuild;
    private static volatile long revision;

    private QuickLitematicaContainerMemory() { }

    public static LitematicaSchematic original(LitematicaSchematic schematic) {
        return STATES.computeIfAbsent(schematic, source -> new State(
                QuickLitematicaContainerMaterialsAccess.copySchematic(source))).original;
    }

    public static long revision() { return revision; }

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
        if (context.equals(state.context) && selectedRegions.equals(state.regions) && configured.equals(state.configured) && projectionConfigured.equals(state.projectionConfigured) && state.enabled == QuickLitematicaContainerReplacements.enabled()) { return; }
        boolean rewrite = hasRules || state.materialized || state.projectionMaterialized;
        if (rewrite) {
            var replaced = QuickLitematicaContainerMaterials.createContainerReplacementCopy(state.original, selectedRegions, context);
            QuickLitematicaProjectionMaterials.replaceBlocks(replaced, context);
            ((com.yiyihehe.quickcraft.mixin.LitematicaSchematicBlocksAccessor) schematic).quickcraft$getBlockContainers()
                    .putAll(((com.yiyihehe.quickcraft.mixin.LitematicaSchematicBlocksAccessor) replaced).quickcraft$getBlockContainers());
            for (String region : selectedRegions) {
                var live = QuickLitematicaContainerMaterialsAccess.getBlockEntities(schematic, region);
                var result = QuickLitematicaContainerMaterialsAccess.getBlockEntities(replaced, region);
                if (live != null && result != null) {
                    live.clear();
                    result.forEach((pos, nbt) -> live.put(pos, nbt.copy()));
                    QuickLitematicaContainerMaterialsAccess.setBlockEntities(schematic, region, live);
                }
            }
            copyContainerItems(replaced, schematic);
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
            var originalEntities = source.getEntityListForRegion(region);
            var liveEntities = target.getEntityListForRegion(region);
            if (originalEntities != null && liveEntities != null) {
                for (int index = 0; index < Math.min(originalEntities.size(), liveEntities.size()); index++) {
                    var nbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(originalEntities.get(index));
                    if (nbt.contains("Items")) {
                        var destination = QuickLitematicaContainerMaterialsAccess.getEntityNbt(liveEntities.get(index));
                        destination.put("Items", nbt.get("Items").copy());
                        QuickLitematicaContainerMaterialsAccess.setEntityNbt(liveEntities, index, destination);
                    }
                }
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
