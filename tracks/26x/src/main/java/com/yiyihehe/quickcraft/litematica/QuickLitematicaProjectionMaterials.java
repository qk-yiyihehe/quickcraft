package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaSchematicBlocksAccessor;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.malilib.util.StringUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** 普通方块编辑保留原始材料行；实际投影、HUD 和备货读取最终方块。 */
public final class QuickLitematicaProjectionMaterials {
    private static final Map<MaterialListBase, Map<ItemType, ItemStack>> SELECTIONS = new WeakHashMap<>();
    private static final Map<MaterialListBase, Counts> COUNTS = new WeakHashMap<>();
    private record Counts(Object2IntOpenHashMap<BlockState> total, Object2IntOpenHashMap<BlockState> missing,
                          Object2IntOpenHashMap<BlockState> mismatch, List<MaterialListEntry> entities) { }
    private record PendingRows(Counts counts, List<MaterialListEntry> originalBlocks, List<MaterialListEntry> actualBlocks) { }
    private static final Map<MaterialListBase, PendingRows> PENDING = new WeakHashMap<>();
    private static final Map<MaterialListBase, HudCache> HUD_CACHE = new WeakHashMap<>();
    private record HudCache(Counts counts, long revision, java.util.Set<String> ignored, List<MaterialListEntry> entries) { }
    private static boolean restoringEditor;
    private QuickLitematicaProjectionMaterials() { }
    public interface OriginalCountSource { void quickcraft$prepareOriginalRows(); }

    public static LitematicaSchematic schematic(MaterialListBase list) {
        if (list instanceof LitematicaMaterialListPlacementAccessor accessor) { return accessor.quickcraft$getPlacement().getSchematic(); }
        if (list instanceof LitematicaMaterialListSchematicAccessor accessor) { return accessor.quickcraft$getSchematic(); }
        return null;
    }

    public static Collection<String> regions(MaterialListBase list) {
        if (list instanceof LitematicaMaterialListPlacementAccessor accessor) {
            return accessor.quickcraft$getPlacement().getEnabledRelativeSubRegionPlacements().keySet();
        }
        if (list instanceof LitematicaMaterialListSchematicAccessor accessor) { return accessor.quickcraft$getRegions(); }
        return List.of();
    }

    public static void toggle(MaterialListBase list, ItemStack stack) {
        var selected = SELECTIONS.computeIfAbsent(list, ignored -> new LinkedHashMap<>());
        ItemType key = new ItemType(stack, true, true);
        if (selected.remove(key) == null) { selected.put(key, stack.copy()); }
    }

    public static boolean selected(MaterialListBase list, ItemStack stack) {
        return SELECTIONS.getOrDefault(list, Map.of()).containsKey(new ItemType(stack, true, true));
    }

    public static void clearSelection(MaterialListBase list) { SELECTIONS.remove(list); }
    public static ItemStack firstSelected(MaterialListBase list) {
        return SELECTIONS.getOrDefault(list, Map.of()).values().stream().findFirst().orElse(ItemStack.EMPTY);
    }

    public static QuickLitematicaMaterialReplacementScreen.ReplacementBinding binding(MaterialListBase list, ItemStack source) {
        var sources = selected(list, source) ? List.copyOf(SELECTIONS.get(list).values()) : List.of(source.copy());
        String context = QuickLitematicaMaterialLists.getReplacementContextKey(list);
        LitematicaSchematic live = schematic(list);
        QuickLitematicaContainerMemory.original(live);
        Map<ItemType, List<BlockState>> states = new LinkedHashMap<>();
        for (String region : QuickLitematicaContainerMemory.original(live).getAreas().keySet()) {
            var container = QuickLitematicaContainerMemory.original(live).getSubRegionContainer(region);
            if (container == null) { continue; }
            long[] recordedCounts = countPaletteStates(container);
            for (int index = 0; index < container.getPalette().getPaletteSize(); index++) {
                if (index >= recordedCounts.length || recordedCounts[index] == 0) { continue; }
                BlockState state = container.getPalette().getBlockState(index);
                if (state == null || state.isAir()) { continue; }
                ItemStack material = MaterialCache.getInstance().getRequiredBuildItemForState(state);
                states.computeIfAbsent(new ItemType(material, true, false), ignored -> new ArrayList<>()).add(state);
            }
        }
        return new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
            @Override
            public String scopeDescription() { return StringUtils.translate("quickcraft.litematica.label.projection_batch_scope", sources.size()); }
            @Override
            public boolean isTargetAllowed(ItemStack ignored, ItemStack target) {
                if (!(target.getItem() instanceof BlockItem) || target.isEmpty()) { return false; }
                for (ItemStack original : sources) {
                    if (!(original.getItem() instanceof BlockItem)) { return false; }
                    var recorded = states.get(new ItemType(original, true, false));
                    if (recorded == null || recorded.isEmpty()) { return false; }
                    if (recorded.stream().anyMatch(state -> QuickLitematicaProjectionReplacementCompatibility.replacementState(state, target) == null)) { return false; }
                }
                return true;
            }
            @Override
            public Optional<ItemStack> getCurrentTarget(ItemStack ignored) {
                return sources.size() == 1 ? QuickLitematicaContainerReplacements.getReplacementTarget(source,
                        QuickLitematicaContainerReplacements.Scope.PROJECTION, context) : Optional.empty();
            }
            @Override
            public void apply(ItemStack ignored, ItemStack target) {
                if (!this.isTargetAllowed(source, target)) { return; }
                QuickLitematicaContainerReplacements.setProjectionReplacementRules(sources, target, context);
                QuickLitematicaContainerMemory.update(live, regions(list), context);
            }
            @Override
            public void clear(ItemStack ignored) {
                for (ItemStack original : sources) { QuickLitematicaContainerReplacements.removeReplacementRule(original,
                        QuickLitematicaContainerReplacements.Scope.PROJECTION, context); }
                QuickLitematicaContainerMemory.update(live, regions(list), context);
            }
        };
    }

    public static BlockState targetState(BlockState original, String context) {
        if (original == null || original.isAir()) { return original; }
        ItemStack material = MaterialCache.getInstance().getRequiredBuildItemForState(original);
        var target = QuickLitematicaContainerReplacements.getReplacementTarget(material,
                QuickLitematicaContainerReplacements.Scope.PROJECTION, context);
        if (target.isEmpty()) { return original; }
        BlockState state = QuickLitematicaProjectionReplacementCompatibility.replacementState(original, target.get());
        return state == null ? original : state;
    }

    public static void replaceBlocks(LitematicaSchematic copy, String context) {
        if (!QuickLitematicaContainerReplacements.hasValidRules(QuickLitematicaContainerReplacements.Scope.PROJECTION, context)) { return; }
        replaceBlocks(copy, state -> QuickLitematicaContainerReplacements.getReplacementTarget(
                MaterialCache.getInstance().getRequiredBuildItemForState(state),
                QuickLitematicaContainerReplacements.Scope.PROJECTION, context));
    }

    static void replaceBlocks(LitematicaSchematic copy, java.util.function.Function<BlockState, Optional<ItemStack>> targets) {
        var containers = ((LitematicaSchematicBlocksAccessor) copy).quickcraft$getBlockContainers();
        for (String region : copy.getAreas().keySet()) {
            var old = containers.get(region);
            if (old == null) { continue; }
            var replacement = mapStates(old, state -> {
                var target = targets.apply(state);
                if (target.isEmpty()) { return state; }
                var mapped = QuickLitematicaProjectionReplacementCompatibility.replacementState(state, target.get());
                return mapped == null ? state : mapped;
            });
            containers.put(region, replacement);
            var entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(copy, region);
            if (entities == null) { continue; }
            boolean addsEntities = false;
            for (int index = 0; index < old.getPalette().getPaletteSize(); index++) {
                BlockState before = old.getPalette().getBlockState(index);
                if (!before.hasBlockEntity() && replacement.getPalette().getBlockState(index).hasBlockEntity()) { addsEntities = true; break; }
            }
            if (addsEntities) {
                var size = old.getSize();
                var lookup = Minecraft.getInstance().level.registryAccess();
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        for (int x = 0; x < size.getX(); x++) {
                            BlockState before = old.get(x, y, z);
                            BlockState after = replacement.get(x, y, z);
                            if (before.hasBlockEntity() || !after.hasBlockEntity()) { continue; }
                            BlockPos pos = new BlockPos(x, y, z);
                            var entity = ((EntityBlock) after.getBlock()).newBlockEntity(pos, after);
                            if (entity != null) {
                                var target = targets.apply(before);
                                target.ifPresent(entity::applyComponentsFromItemStack);
                                entities.put(pos, QuickLitematicaContainerMaterialsAccess.blockEntityNbt(entity, lookup));
                            }
                        }
                    }
                }
            }
            for (var entry : new ArrayList<>(entities.entrySet())) {
                BlockPos pos = entry.getKey();
                BlockState previous = old.get(pos.getX(), pos.getY(), pos.getZ());
                if (!previous.hasBlockEntity()) { continue; }
                BlockState current = replacement.get(pos.getX(), pos.getY(), pos.getZ());
                var selected = targets.apply(previous);
                if (selected.isEmpty()) { continue; }
                if (!current.hasBlockEntity()) { entities.remove(pos); continue; }
                var entity = ((EntityBlock) current.getBlock()).newBlockEntity(pos, current);
                if (entity != null) {
                    var lookup = Minecraft.getInstance().level.registryAccess();
                    QuickLitematicaContainerMaterialsAccess.readBlockEntity(entity, entry.getValue().copy(), lookup);
                    // 外壳替换只叠加目标显式组件，避免默认组件清空原有文字、图案等实体内容。
                    entity.applyComponents(entity.collectComponents(), selected.get().getComponentsPatch());
                    var nbt = QuickLitematicaContainerMaterialsAccess.blockEntityNbt(entity, lookup);
                    if (entry.getValue().contains("Items") && nbt.contains("Items")) {
                        nbt.put("Items", entry.getValue().get("Items").copy());
                    }
                    entities.put(pos, nbt);
                } else { entities.remove(pos); }
            }
            QuickLitematicaContainerMaterialsAccess.setBlockEntities(copy, region, entities);
        }
    }

    static long[] countPaletteStates(LitematicaBlockStateContainer container) {
        // 原生 getBlockCounts 只在 Sponge 导入时填充，且 set 不更新缓存；统计实际存储中的索引。
        long[] counts = new long[container.getPalette().getPaletteSize()];
        var storage = container.getArray();
        for (long index = 0; index < storage.size(); index++) {
            int paletteIndex = storage.getAt(index);
            if (paletteIndex >= 0 && paletteIndex < counts.length) { counts[paletteIndex]++; }
        }
        return counts;
    }

    static LitematicaBlockStateContainer mapStates(LitematicaBlockStateContainer original,
                                                   java.util.function.UnaryOperator<BlockState> mapper) {
        List<BlockState> mapping = new ArrayList<>();
        for (int index = 0; index < original.getPalette().getPaletteSize(); index++) {
            mapping.add(mapper.apply(original.getPalette().getBlockState(index)));
        }
        // 保留调色板索引，允许多个索引指向同一目标状态；不共享原始方块数组。
        var result = QuickLitematicaDataCompat.copyBlockContainer(original);
        if (!result.getPalette().setMapping(mapping)) { throw new IllegalStateException("Unable to map replacement palette"); }
        return result;
    }

    public static void refreshFileList(MaterialListBase list) {
        LitematicaSchematic live = schematic(list);
        if (live == null || restoringEditor) { return; }
        var totals = blockTotals(QuickLitematicaContainerMemory.original(live), regions(list));
        var actualTotals = blockTotals(live, regions(list));
        var entities = combineEntries(list.getMaterialsAll(), QuickLitematicaMaterialCountAccess.blocks(
                actualTotals, actualTotals, new Object2IntOpenHashMap<>(), Minecraft.getInstance().player), -1);
        COUNTS.put(list, new Counts(totals, totals, new Object2IntOpenHashMap<>(), entities));
        restoringEditor = true;
        try {
            var entries = combineEntries(QuickLitematicaMaterialCountAccess.blocks(totals, totals,
                    new Object2IntOpenHashMap<>(), Minecraft.getInstance().player), entities, 1);
            updateEditorAvailability(list, entries);
            list.setMaterialListEntries(entries);
        }
        finally { restoringEditor = false; }
    }

    private static Object2IntOpenHashMap<BlockState> blockTotals(LitematicaSchematic schematic, Collection<String> regions) {
        Object2IntOpenHashMap<BlockState> totals = new Object2IntOpenHashMap<>();
        for (String region : regions) {
            var container = schematic.getSubRegionContainer(region);
            if (container == null) { continue; }
            long[] counts = countPaletteStates(container);
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] > 0) { totals.addTo(container.getPalette().getBlockState(index), Math.toIntExact(counts[index])); }
            }
        }
        return totals;
    }

    public static void prepareEditor(MaterialListBase list, Object2IntOpenHashMap<BlockState> total,
                                     Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch,
                                     Object2IntOpenHashMap<BlockState> actualTotal, Object2IntOpenHashMap<BlockState> actualMissing,
                                     Object2IntOpenHashMap<BlockState> actualMismatch) {
        var player = Minecraft.getInstance().player;
        PENDING.put(list, new PendingRows(new Counts(total.clone(), missing.clone(), mismatch.clone(), List.of()),
                QuickLitematicaMaterialCountAccess.blocks(total, missing, mismatch, player),
                QuickLitematicaMaterialCountAccess.blocks(actualTotal, actualMissing, actualMismatch, player)));
    }

    public static List<MaterialListEntry> editorEntries(MaterialListBase list, List<MaterialListEntry> actual) {
        var pending = PENDING.remove(list);
        if (pending == null || restoringEditor) { return actual; }
        // 原生结果包含实体及其内容；只扣除实际方块贡献，实体与方块需要同一物品时也不能漏掉。
        var entities = combineEntries(actual, pending.actualBlocks(), -1);
        var counts = pending.counts();
        COUNTS.put(list, new Counts(counts.total(), counts.missing(), counts.mismatch(), entities));
        var entries = combineEntries(pending.originalBlocks(), entities, 1);
        updateEditorAvailability(list, entries);
        return entries;
    }

    static List<MaterialListEntry> combineEntries(List<MaterialListEntry> first, List<MaterialListEntry> second, int sign) {
        Map<ItemType, int[]> counts = new LinkedHashMap<>();
        var lists = List.of(first, second);
        for (int index = 0; index < lists.size(); index++) {
            var entries = lists.get(index);
            int factor = index == 0 ? 1 : sign;
            for (var entry : entries) {
                // 与原生统计采用同一物品键，数量相加，背包可用量在合并后重新读取。
                var value = counts.computeIfAbsent(new ItemType(entry.getStack(), true, false), ignored -> new int[3]);
                value[0] += factor * entry.getCountTotal();
                value[1] += factor * entry.getCountMissing();
                value[2] += factor * entry.getCountMismatched();
            }
        }
        List<MaterialListEntry> result = new ArrayList<>();
        counts.forEach((type, value) -> {
            if (value[0] > 0) { result.add(new MaterialListEntry(type.getStack().copyWithCount(1), value[0], value[1], value[2], 0)); }
        });
        return result;
    }

    private static Object2IntOpenHashMap<ItemType> finalItems(Object2IntOpenHashMap<BlockState> original, String context,
                                                              java.util.Set<String> ignored) {
        Object2IntOpenHashMap<ItemType> result = new Object2IntOpenHashMap<>();
        original.forEach((state, count) -> {
            ItemStack source = MaterialCache.getInstance().getRequiredBuildItemForState(state);
            if (ignored.contains(new ItemType(source, true, true).toString())) { return; }
            BlockState finalState = targetState(state, context);
            var replacement = QuickLitematicaContainerReplacements.getReplacementTarget(source,
                    QuickLitematicaContainerReplacements.Scope.PROJECTION, context);
            Object2IntOpenHashMap<BlockState> singleton = new Object2IntOpenHashMap<>();
            singleton.put(finalState, (int) count);
            for (var entry : QuickLitematicaMaterialCountAccess.blocks(singleton, singleton,
                    new Object2IntOpenHashMap<>(), Minecraft.getInstance().player)) {
                ItemStack template = entry.getStack();
                if (replacement.isPresent() && template.getItem() == replacement.get().getItem()) {
                    template = replacement.get().copyWithCount(1);
                }
                if (!(template.getItem() instanceof BlockItem) && ignored.contains(new ItemType(template, true, true).toString())) { continue; }
                result.addTo(new ItemType(template, true, true), entry.getCountTotal());
            }
        });
        return result;
    }

    private static int available(ItemStack template) {
        int count = 0;
        var inventory = Minecraft.getInstance().player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(template, stack)) { count += stack.getCount(); }
        }
        return count;
    }

    private static void updateEditorAvailability(MaterialListBase list, List<MaterialListEntry> entries) {
        String context = QuickLitematicaMaterialLists.getReplacementContextKey(list);
        for (var entry : entries) {
            var target = QuickLitematicaContainerReplacements.getReplacementTarget(entry.getStack(),
                    QuickLitematicaContainerReplacements.Scope.PROJECTION, context);
            entry.setCountAvailable(available(target.orElse(entry.getStack())));
        }
    }

    private static void updateAvailable(List<MaterialListEntry> entries) {
        for (MaterialListEntry entry : entries) { entry.setCountAvailable(available(entry.getStack())); }
    }

    public static List<MaterialListEntry> hud(MaterialListBase list, boolean missing) {
        return hud(list, missing, () -> missing ? list.getMaterialsMissingOnly(true) : list.getMaterialsAll());
    }

    public static List<MaterialListEntry> hud(MaterialListBase list, boolean missing,
                                             java.util.function.Supplier<? extends List<MaterialListEntry>> original) {
        Counts counts = COUNTS.get(list);
        var ignored = QuickLitematicaMaterialIgnores.effective(list);
        if (counts == null) {
            var entries = original.get();
            // Litematica 的 HUD 会原地排序，不能返回 Stream.toList() 的只读列表。
            return new ArrayList<>(entries.stream().filter(entry -> !ignored.contains(new ItemType(entry.getStack(), true, true).toString())).toList());
        }
        String context = QuickLitematicaMaterialLists.getReplacementContextKey(list);
        HudCache cached = HUD_CACHE.get(list);
        if (cached == null || cached.counts() != counts || cached.revision() != QuickLitematicaContainerMemory.revision()
                || !cached.ignored().equals(ignored)) {
            var totalItems = finalItems(counts.total(), context, ignored);
            var missingItems = finalItems(counts.missing(), context, ignored);
            var mismatchItems = finalItems(counts.mismatch(), context, ignored);
            for (var entity : counts.entities()) {
                ItemType type = new ItemType(entity.getStack(), true, true);
                if (ignored.contains(type.toString())) { continue; }
                totalItems.addTo(type, entity.getCountTotal());
                missingItems.addTo(type, entity.getCountMissing());
                mismatchItems.addTo(type, entity.getCountMismatched());
            }
            List<MaterialListEntry> entries = new ArrayList<>();
            for (ItemType type : totalItems.keySet()) {
                entries.add(new MaterialListEntry(type.getStack().copyWithCount(1), totalItems.getInt(type),
                        missingItems.getInt(type), mismatchItems.getInt(type), 0));
            }
            cached = new HudCache(counts, QuickLitematicaContainerMemory.revision(), java.util.Set.copyOf(ignored), entries);
            HUD_CACHE.put(list, cached);
        }
        var actual = cached.entries();
        updateAvailable(actual);
        if (!missing) { return actual; }
        List<MaterialListEntry> filtered = new ArrayList<>(actual.stream().filter(entry -> (list.getMultiplier() == 1
                ? entry.getCountMissing() : list.getMultiplier() * entry.getCountTotal()) > entry.getCountAvailable()).toList());
        filtered.sort(new fi.dy.masa.litematica.materials.MaterialListSorter(list));
        return filtered;
    }

    public static BlockState originalAt(SchematicPlacement placement, BlockPos pos) {
        var original = QuickLitematicaContainerMemory.original(placement.getSchematic());
        for (var entry : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
            String region = entry.getKey();
            var sub = entry.getValue();
            var container = original.getSubRegionContainer(region);
            if (container == null) { continue; }
            BlockPos offset = PositionUtils.getTransformedBlockPos(sub.getPos(), placement.getMirror(), placement.getRotation()).offset(placement.getOrigin());
            BlockPos local = PositionUtils.getReverseTransformedBlockPos(pos.subtract(offset), sub.getMirror(), sub.getRotation());
            local = PositionUtils.getReverseTransformedBlockPos(local, placement.getMirror(), placement.getRotation());
            BlockPos min = PositionUtils.getMinCorner(sub.getPos(), sub.getPos().offset(PositionUtils.getRelativeEndPositionFromAreaSize(original.getAreaSize(region))));
            local = local.subtract(min.subtract(sub.getPos()));
            var size = container.getSize();
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0 || local.getX() >= size.getX() || local.getY() >= size.getY() || local.getZ() >= size.getZ()) { continue; }
            return container.get(local.getX(), local.getY(), local.getZ());
        }
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }
}
