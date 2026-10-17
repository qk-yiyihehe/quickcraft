package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerReplacements.Scope;

/** 全局结果只供投影世界使用；原理图对象、材料需求和导出继续使用手动替换结果。 */
public final class QuickLitematicaGlobalReplacements {
    private static final Map<LitematicaSchematic, RuntimeCopy> COPIES = new WeakHashMap<>();
    private static final Map<Scope, ParsedRules> RULES = new EnumMap<>(Scope.class);
    private static final Map<QuickLitematicaContainerReplacements.ReplacementRule, ItemStack> SOURCES = new WeakHashMap<>();
    private static ReplacementSettings appliedSettings;

    private QuickLitematicaGlobalReplacements() { }

    public static ConfigStringList config(Scope scope) {
        return scope == Scope.CONTAINER ? QuickCraftConfigs.ProjectionTools.GLOBAL_CONTAINER_REPLACEMENTS
                : QuickCraftConfigs.ProjectionTools.GLOBAL_PROJECTION_MATERIAL_REPLACEMENTS;
    }

    public static synchronized List<QuickLitematicaContainerReplacements.ReplacementRule> rules(Scope scope) {
        var entries = config(scope).getStrings();
        var lookup = QuickLitematicaContainerReplacements.registryLookup();
        var cached = RULES.get(scope);
        if (cached != null && cached.entries.equals(entries) && cached.lookup == lookup) { return cached.rules; }
        var parsed = new ArrayList<QuickLitematicaContainerReplacements.ReplacementRule>();
        for (String entry : entries) {
            var rule = QuickLitematicaContainerReplacements.parseRule(entry, scope, "", "", -1, lookup);
            if (rule != null && rule.contextKey().isEmpty() && rule.groupKey().isEmpty() && rule.slotIndex() < 0) {
                parsed.add(rule);
            }
        }
        var result = List.copyOf(parsed);
        RULES.put(scope, new ParsedRules(List.copyOf(entries), lookup, result));
        return result;
    }

    public static synchronized ItemStack source(QuickLitematicaContainerReplacements.ReplacementRule rule) {
        return rule.exactSource() != null ? rule.exactSource().copy()
                : SOURCES.computeIfAbsent(rule, entry -> QuickLitematicaContainerReplacements.resolveConfiguredItem(entry.source())).copy();
    }

    public static Optional<ItemStack> target(ItemStack source, Scope scope) {
        if (!QuickLitematicaContainerReplacements.enabled() || source.isEmpty()) { return Optional.empty(); }
        for (var rule : rules(scope)) {
            if (!QuickLitematicaContainerReplacements.matchesSource(source, rule)) { continue; }
            ItemStack result = proportionalTarget(source, rule.target());
            if (scope == Scope.PROJECTION && !QuickLitematicaContainerReplacements.isReplacementAllowed(source, result, scope)) {
                return Optional.empty();
            }
            return Optional.of(result);
        }
        return Optional.empty();
    }

    static ItemStack proportionalTarget(ItemStack source, ItemStack target) {
        ItemStack result = target.copy();
        if (!result.isEmpty()) { result.setCount(QuickLitematicaContainerReplacements.getReplacementCount(source, result)); }
        return result;
    }

    public static void set(ItemStack source, ItemStack target, Scope scope) {
        set(source, target, scope, ItemStack.EMPTY);
    }

    public static void set(ItemStack source, ItemStack target, Scope scope, ItemStack previousSource) {
        var entries = new ArrayList<>(config(scope).getStrings());
        entries.removeIf(entry -> {
            var rule = QuickLitematicaContainerReplacements.parseRule(entry, scope, "", "", -1,
                    QuickLitematicaContainerReplacements.registryLookup());
            return rule != null && (QuickLitematicaContainerReplacements.matchesSource(source, rule)
                    || (!previousSource.isEmpty() && QuickLitematicaContainerReplacements.matchesSource(previousSource, rule)));
        });
        if (target != null && !ItemStack.areItemsAndComponentsEqual(source, target)) {
            entries.add(QuickLitematicaContainerReplacements.encodeContainerRule(
                    new QuickLitematicaContainerReplacements.ContainerReplacement(source.copyWithCount(1),
                            target.copyWithCount(1), "", "", -1, true), QuickLitematicaContainerReplacements.registryLookup()));
        }
        synchronized (QuickLitematicaGlobalReplacements.class) {
            config(scope).setStrings(entries);
        }
        QuickCraftConfigs.saveToFile();
        // 新版 MaLiLib 的 setStrings 只标记修改，不再调用 onValueChanged。
        changed();
    }

    static List<ItemStack> containerSlots(List<ItemStack> slots, String context, String group) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack source = slots.get(slot);
            var manual = QuickLitematicaContainerReplacements.getReplacementTarget(source, Scope.CONTAINER, context, group, slot);
            // 手动保持原始的规则同样优先；每次都匹配原始来源，禁止 A→B→C 连锁。
            result.add(manual.or(() -> target(source, Scope.CONTAINER)).orElseGet(source::copy));
        }
        return result;
    }

    private static Optional<ItemStack> blockTarget(BlockState source, String context) {
        ItemStack material = QuickLitematicaProjectionMaterials.replacementSource(source);
        return QuickLitematicaContainerReplacements.getReplacementTarget(material, Scope.PROJECTION, context)
                .or(() -> target(material, Scope.PROJECTION));
    }

    public static synchronized LitematicaSchematic runtime(SchematicPlacement placement) {
        return runtime(placement, placement.getSchematic());
    }

    public static synchronized LitematicaSchematic runtime(SchematicPlacement placement, LitematicaSchematic baseline) {
        if (!hasRuntimeRules()) { return baseline; }
        var context = QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement);
        var containerRules = List.copyOf(config(Scope.CONTAINER).getStrings());
        var blockRules = List.copyOf(config(Scope.PROJECTION).getStrings());
        long revision = QuickLitematicaContainerMemory.revision();
        var cached = COPIES.get(baseline);
        if (cached != null && cached.revision == revision && cached.context.equals(context)
                && cached.containerRules.equals(containerRules) && cached.blockRules.equals(blockRules)) { return cached.schematic; }
        var original = QuickLitematicaContainerMemory.original(baseline);
        var copy = QuickLitematicaContainerMaterials.createContainerReplacementCopy(original, original.getAreas().keySet(), context, true);
        QuickLitematicaProjectionMaterials.replaceBlocks(copy, state -> blockTarget(state, context));
        COPIES.put(baseline, new RuntimeCopy(copy, context, revision, containerRules, blockRules));
        return copy;
    }

    public static boolean hasRuntimeRules() {
        return QuickLitematicaContainerReplacements.enabled()
                && (!rules(Scope.CONTAINER).isEmpty() || !rules(Scope.PROJECTION).isEmpty());
    }

    public static void changed() {
        synchronized (QuickLitematicaGlobalReplacements.class) {
            var settings = new ReplacementSettings(QuickLitematicaContainerReplacements.enabled(),
                    List.copyOf(QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS.getStrings()),
                    List.copyOf(QuickCraftConfigs.ProjectionTools.PROJECTION_MATERIAL_REPLACEMENTS.getStrings()),
                    List.copyOf(config(Scope.CONTAINER).getStrings()),
                    List.copyOf(config(Scope.PROJECTION).getStrings()));
            // 配置界面会保存后重载；无关设置变化不能清除已有验证结果。
            if (settings.equals(appliedSettings)) { return; }
            appliedSettings = settings;
            COPIES.clear();
            RULES.clear();
            SOURCES.clear();
        }
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.world == null) { return; }
        QuickLitematicaContainerMemory.refreshAll();
        for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            QuickLitematicaContainerMemory.update(placement);
            if (placement.hasVerifier()) { placement.getSchematicVerifier().reset(); }
        }
        for (var schematic : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .map(SchematicPlacement::getSchematic).distinct().toList()) {
            DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(schematic);
        }
        if (client.currentScreen instanceof fi.dy.masa.litematica.gui.GuiMaterialList list) { list.initGui(); }
        else if (client.currentScreen instanceof QuickLitematicaMaterialReplacementScreen.RefreshableParent screen) {
            screen.quickcraft$refreshAfterReplacement();
        }
    }

    public static BlockState baselineState(SchematicPlacement placement, BlockPos pos) {
        var location = baselineLocation(placement, pos);
        return location == null ? net.minecraft.block.Blocks.AIR.getDefaultState() : location.state;
    }

    public static BlockState originalState(SchematicPlacement placement, BlockPos pos) {
        var location = baselineLocation(placement, pos);
        if (location == null) { return net.minecraft.block.Blocks.AIR.getDefaultState(); }
        var container = QuickLitematicaContainerMemory.original(placement.getSchematic()).getSubRegionContainer(location.region);
        return container.get(location.local.getX(), location.local.getY(), location.local.getZ());
    }

    public static BlockState baselineWorldState(BlockPos pos) {
        var location = baselineLocation(pos);
        return location == null ? net.minecraft.block.Blocks.AIR.getDefaultState() : location.state;
    }

    public static BlockEntity baselineWorldEntity(BlockPos pos, net.minecraft.world.World world) {
        var location = baselineLocation(pos);
        if (location == null) { return null; }
        var entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(location.placement.getSchematic(), location.region);
        var nbt = entities == null ? null : entities.get(location.local);
        if (nbt == null) { return null; }
        var entity = BlockEntity.createFromNbt(pos, location.state, nbt.copy(), world.getRegistryManager());
        return entity;
    }

    private static Location baselineLocation(BlockPos pos) {
        Location result = null;
        for (var part : DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos)) {
            if (!part.getBox().containsPos(pos) || !part.getPlacement().isEnabled()) { continue; }
            var location = baselineLocation(part.getPlacement(), pos);
            if (location != null && !location.state.isAir()) { result = location; }
        }
        return result;
    }

    public static void restoreSavedEntityItems(net.minecraft.entity.Entity entity, net.minecraft.nbt.NbtCompound output) {
        if (!output.contains("Items")) { return; }
        String entityId = QuickLitematicaContainerMaterialsAccess.readString(output, "id");
        if (entityId.isBlank()) { entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString(); }
        var placements = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        for (var placement : placements) {
            if (!placement.isEnabled() || placement.ignoreEntities()) { continue; }
            for (var sub : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
                if (sub.getValue().ignoreEntities()) { continue; }
                var entities = placement.getSchematic().getEntityListForRegion(sub.getKey());
                if (entities == null) { continue; }
                var offset = fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos(
                        sub.getValue().getPos(), placement.getMirror(), placement.getRotation()).add(placement.getOrigin());
                for (var info : entities) {
                    var nbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(info);
                    if (!nbt.contains("Items") || !QuickLitematicaContainerMaterialsAccess.readString(nbt, "id").equals(entityId)) { continue; }
                    var pos = fi.dy.masa.litematica.util.PositionUtils.getTransformedPosition(QuickLitematicaContainerMaterialsAccess.getEntityPos(info),
                            placement.getMirror(), placement.getRotation());
                    pos = fi.dy.masa.litematica.util.PositionUtils.getTransformedPosition(pos,
                            sub.getValue().getMirror(), sub.getValue().getRotation()).add(offset.getX(), offset.getY(), offset.getZ());
                    if (pos.squaredDistanceTo(QuickLitematicaContainerMaterialsAccess.entityPosition(entity)) < 0.000001) {
                        output.put("Items", nbt.get("Items").copy());
                        return;
                    }
                }
            }
        }
    }

    private static Location baselineLocation(SchematicPlacement placement, BlockPos pos) {
        var schematic = placement.getSchematic();
        Location result = null;
        for (var entry : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
            var region = entry.getKey();
            var container = schematic.getSubRegionContainer(region);
            if (container == null) { continue; }
            var sub = entry.getValue();
            var offset = fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos(sub.getPos(),
                    placement.getMirror(), placement.getRotation()).add(placement.getOrigin());
            var local = fi.dy.masa.litematica.util.PositionUtils.getReverseTransformedBlockPos(pos.subtract(offset), sub.getMirror(), sub.getRotation());
            local = fi.dy.masa.litematica.util.PositionUtils.getReverseTransformedBlockPos(local, placement.getMirror(), placement.getRotation());
            var min = fi.dy.masa.litematica.util.PositionUtils.getMinCorner(sub.getPos(),
                    sub.getPos().add(fi.dy.masa.litematica.util.PositionUtils.getRelativeEndPositionFromAreaSize(schematic.getAreaSize(region))));
            local = local.subtract(min.subtract(sub.getPos()));
            var size = container.getSize();
            // 原生坐标工具会把区域外坐标夹到边缘；材料统计和保存必须先检查真正的区域边界。
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0 || local.getX() >= size.getX()
                    || local.getY() >= size.getY() || local.getZ() >= size.getZ()) { continue; }
            var state = container.get(local.getX(), local.getY(), local.getZ());
            if (!state.isAir()) {
                result = new Location(placement, region, local,
                        QuickLitematicaContainerVerifier.getTransformedWorldState(state, placement, region));
            }
        }
        return result;
    }

    private record Location(SchematicPlacement placement, String region, BlockPos local, BlockState state) { }

    private record ReplacementSettings(boolean enabled, List<String> containerRules, List<String> blockRules,
                                       List<String> globalContainerRules, List<String> globalBlockRules) { }

    private record RuntimeCopy(LitematicaSchematic schematic, String context, long revision,
                               List<String> containerRules, List<String> blockRules) { }
    private record ParsedRules(List<String> entries, net.minecraft.registry.RegistryWrapper.WrapperLookup lookup,
                               List<QuickLitematicaContainerReplacements.ReplacementRule> rules) { }
}
