package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.compat.voidwarehouse.QuickLitematicaVoidWarehouseMaterials;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaSchematicBlocksAccessor;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.ItemType;
import fi.dy.masa.malilib.util.StringUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
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
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** 普通方块编辑保留原始材料行；实际投影、HUD 和备货读取最终方块。 */
public final class QuickLitematicaProjectionMaterials {
    private static final Map<MaterialListBase, Map<ItemType, ItemStack>> SELECTIONS = new WeakHashMap<>();
    private static final Map<MaterialListBase, Counts> COUNTS = new WeakHashMap<>();
    private static final Map<MaterialListBase, Long> FILE_LIST_VERSIONS = new WeakHashMap<>();

    private record Counts(
            Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing,
            Object2IntOpenHashMap<BlockState> mismatch
    ) {
    }

    private static final Map<MaterialListBase, List<MaterialListEntry>> PENDING = new WeakHashMap<>();
    private static final Map<MaterialListBase, HudCache> HUD_CACHE = new WeakHashMap<>();

    private record HudCache(
            Counts counts,
            long revision,
            java.util.Set<String> ignored,
            String context,
            boolean hideAvailable,
            int multiplier,
            Set<String> hidden,
            List<MaterialListEntry> entries,
            List<MaterialListEntry> missingEntries
    ) {
    }

    private static boolean restoringEditor;

    private QuickLitematicaProjectionMaterials() {}

    public interface OriginalCountSource {
        void quickcraft$prepareOriginalRows();
    }

    public interface WarehouseAvailability extends QuickLitematicaVoidWarehouseMaterials.MaterialTargets {
        void quickcraft$setWarehouseTarget(ItemStack target);
    }

    public static LitematicaSchematic schematic(MaterialListBase list) {
        if (list instanceof LitematicaMaterialListPlacementAccessor accessor) {
            return accessor.quickcraft$getPlacement().getSchematic();
        }
        if (list instanceof LitematicaMaterialListSchematicAccessor accessor) {
            return accessor.quickcraft$getSchematic();
        }
        return null;
    }

    public static Collection<String> regions(MaterialListBase list) {
        if (list instanceof LitematicaMaterialListPlacementAccessor accessor) {
            return accessor.quickcraft$getPlacement().getEnabledRelativeSubRegionPlacements().keySet();
        }
        if (list instanceof LitematicaMaterialListSchematicAccessor accessor) {
            return accessor.quickcraft$getRegions();
        }
        return List.of();
    }

    public static void toggle(MaterialListBase list, ItemStack stack) {
        var selected = SELECTIONS.computeIfAbsent(list, ignored -> new LinkedHashMap<>());
        ItemType key = new ItemType(stack, true, true);
        if (selected.remove(key) == null) {
            selected.put(key, stack.copy());
        }
    }

    public static boolean selected(MaterialListBase list, ItemStack stack) {
        return SELECTIONS.getOrDefault(list, Map.of()).containsKey(new ItemType(stack, true, true));
    }

    public static void clearSelection(MaterialListBase list) {
        SELECTIONS.remove(list);
    }

    public static ItemStack firstSelected(MaterialListBase list) {
        return SELECTIONS.getOrDefault(list, Map.of()).values().stream().findFirst().orElse(ItemStack.EMPTY);
    }

    public static QuickLitematicaMaterialReplacementScreen.ReplacementBinding binding(
            MaterialListBase list,
            ItemStack source
    ) {
        var sources = selected(list, source) ? List.copyOf(SELECTIONS.get(list).values()) : List.of(source.copy());
        return binding(
                schematic(list),
                regions(list),
                QuickLitematicaMaterialLists.getReplacementContextKey(list),
                source,
                sources
        );
    }

    static QuickLitematicaMaterialReplacementScreen.ReplacementBinding binding(
            SchematicPlacement placement,
            ItemStack source
    ) {
        return binding(
                placement.getSchematic(),
                placement.getEnabledRelativeSubRegionPlacements().keySet(),
                QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement),
                source,
                List.of(source.copy())
        );
    }

    private static QuickLitematicaMaterialReplacementScreen.ReplacementBinding binding(
            LitematicaSchematic live,
            Collection<String> regions,
            String context,
            ItemStack source,
            List<ItemStack> sources
    ) {
        var originalSchematic = QuickLitematicaContainerMemory.original(live);
        Map<ItemType, Set<BlockState>> states = new LinkedHashMap<>();
        for (String region : originalSchematic.getAreas().keySet()) {
            var container = originalSchematic.getSubRegionContainer(region);
            if (container == null) {
                continue;
            }
            long[] recordedCounts = QuickLitematicaContainerMemory.originalBlockCounts(live, region);
            for (int index = 0; index < recordedCounts.length; index++) {
                if (recordedCounts[index] == 0) {
                    continue;
                }
                BlockState state = container.getPalette().getBlockState(index);
                if (state == null || state.isAir()) {
                    continue;
                }
                ItemStack material = replacementSource(state);
                states.computeIfAbsent(new ItemType(material, true, false), ignored -> new HashSet<>()).add(state);
            }
        }
        return new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
            @Override
            public String scopeDescription() {
                return StringUtils.translate("quickcraft.litematica.label.projection_batch_scope", sources.size());
            }

            @Override
            public String emptyTargetLabel() {
                return QuickLitematicaMaterialReplacementScreen.emptyTargetLabel(
                        QuickLitematicaContainerReplacements.Scope.PROJECTION
                );
            }

            @Override
            public boolean isTargetAllowed(ItemStack ignored, ItemStack target) {
                if (target == null || (!target.isEmpty() && !(target.getItem() instanceof BlockItem))) {
                    return false;
                }
                for (ItemStack original : sources) {
                    if (!(original.getItem() instanceof BlockItem)) {
                        return false;
                    }
                    var recorded = states.get(new ItemType(original, true, false));
                    if (recorded == null || recorded.isEmpty()) {
                        return false;
                    }
                    if (recorded.stream().anyMatch(
                            state -> QuickLitematicaProjectionReplacementCompatibility.replacementState(
                                    state,
                                    target
                            ) == null
                    )) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Optional<ItemStack> getCurrentTarget(ItemStack ignored) {
                return sources.size() == 1 ? QuickLitematicaContainerReplacements.getReplacementTarget(
                        source,
                        QuickLitematicaContainerReplacements.Scope.PROJECTION,
                        context
                ) : Optional.empty();
            }

            @Override
            public void apply(ItemStack ignored, ItemStack target) {
                if (!this.isTargetAllowed(source, target)) {
                    return;
                }
                QuickLitematicaContainerReplacements.setProjectionReplacementRules(sources, target, context);
                QuickLitematicaContainerMemory.update(live, regions, context);
            }

            @Override
            public void clear(ItemStack ignored) {
                for (ItemStack original : sources) {
                    QuickLitematicaContainerReplacements.removeReplacementRule(
                            original,
                            QuickLitematicaContainerReplacements.Scope.PROJECTION,
                            context
                    );
                }
                QuickLitematicaContainerMemory.update(live, regions, context);
            }
        };
    }

    static ItemStack replacementSource(BlockState state) {
        // 材料缓存省略多格结构的附属部分；仅归一化规则来源，替换和计数仍使用原状态。
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TallPlantBlock) {
            state = state.with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        } else if (state.getBlock() instanceof BedBlock) {
            state = state.with(BedBlock.PART, BedPart.FOOT);
        }
        return MaterialCache.getInstance().getRequiredBuildItemForState(state);
    }

    public static BlockState targetState(BlockState original, String context) {
        if (original == null || original.isAir()) {
            return original;
        }
        ItemStack material = replacementSource(original);
        var target = QuickLitematicaContainerReplacements.getReplacementTarget(
                material,
                QuickLitematicaContainerReplacements.Scope.PROJECTION,
                context
        );
        if (target.isEmpty()) {
            return original;
        }
        BlockState state = QuickLitematicaProjectionReplacementCompatibility.replacementState(original, target.get());
        return state == null ? original : state;
    }

    public static void replaceBlocks(LitematicaSchematic copy, String context) {
        if (!QuickLitematicaContainerReplacements.hasValidRules(
                QuickLitematicaContainerReplacements.Scope.PROJECTION,
                context
        )) {
            return;
        }
        replaceBlocks(copy, state -> projectionTarget(state, context));
    }

    static Optional<ItemStack> projectionTarget(BlockState state, String context) {
        return QuickLitematicaContainerReplacements.getReplacementTarget(
                replacementSource(state),
                QuickLitematicaContainerReplacements.Scope.PROJECTION,
                context
        );
    }

    static void replaceBlocks(LitematicaSchematic copy, Function<BlockState, Optional<ItemStack>> targets) {
        var containers = ((LitematicaSchematicBlocksAccessor) copy).quickcraft$getBlockContainers();
        Map<BlockState, Optional<ItemStack>> selected = new HashMap<>();
        long removedBlocks = 0;
        for (String region : copy.getAreas().keySet()) {
            var old = containers.get(region);
            if (old == null) {
                continue;
            }
            var entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(copy, region);
            var replacement = replaceRegionBlocks(old, entities, state -> selected.computeIfAbsent(state, targets));
            if (replacement != old) {
                containers.put(region, replacement);
                long[] counts = null;
                for (int index = 0; index < old.getPalette().getPaletteSize(); index++) {
                    BlockState before = old.getPalette().getBlockState(index);
                    BlockState after = replacement.getPalette().getBlockState(index);
                    if (!before.isAir() && after.isAir()) {
                        if (counts == null) {
                            counts = countPaletteStates(old);
                        }
                        removedBlocks += counts[index];
                    }
                }
            }
            if (entities != null) {
                QuickLitematicaContainerMaterialsAccess.setBlockEntities(copy, region, entities);
            }
        }
        if (removedBlocks > 0) {
            var metadata = copy.getMetadata();
            metadata.setTotalBlocks((int) Math.max(0, metadata.getTotalBlocks() - removedBlocks));
        }
    }

    static LitematicaBlockStateContainer replaceRegionBlocks(
            LitematicaBlockStateContainer original,
            Map<BlockPos, NbtCompound> entities,
            Function<BlockState, Optional<ItemStack>> targets
    ) {
        var palette = original.getPalette();
        List<BlockState> mapping = new ArrayList<>(palette.getPaletteSize());
        Map<BlockState, BlockState> addedEntityStates = new HashMap<>();
        boolean changed = false;
        boolean updatesEntities = false;
        for (int index = 0; index < palette.getPaletteSize(); index++) {
            BlockState before = palette.getBlockState(index);
            var target = targets.apply(before);
            BlockState after = before;
            if (target.isPresent()) {
                BlockState mapped = QuickLitematicaProjectionReplacementCompatibility.replacementState(
                        before,
                        target.get()
                );
                if (mapped != null) {
                    after = mapped;
                }
                if (before.hasBlockEntity()) {
                    updatesEntities = true;
                } else if (after.hasBlockEntity()) {
                    addedEntityStates.put(before, after);
                }
            }
            changed |= before != after;
            mapping.add(after);
        }

        var replacement = changed ? copyMappedStates(original, mapping) : original;
        if (entities == null || (!updatesEntities && addedEntityStates.isEmpty())) {
            return replacement;
        }
        if (!addedEntityStates.isEmpty()) {
            var size = original.getSize();
            var lookup = MinecraftClient.getInstance().world.getRegistryManager();
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockState before = original.get(x, y, z);
                        BlockState after = addedEntityStates.get(before);
                        if (after == null) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(x, y, z);
                        var entity = ((BlockEntityProvider) after.getBlock()).createBlockEntity(pos, after);
                        if (entity != null) {
                            var target = targets.apply(before);
                            target.ifPresent(entity::readComponents);
                            entities.put(pos, QuickLitematicaContainerMaterialsAccess.blockEntityNbt(entity, lookup));
                        }
                    }
                }
            }
        }
        for (var entry : new ArrayList<>(entities.entrySet())) {
            BlockPos pos = entry.getKey();
            BlockState previous = original.get(pos.getX(), pos.getY(), pos.getZ());
            if (!previous.hasBlockEntity()) {
                continue;
            }
            BlockState current = replacement.get(pos.getX(), pos.getY(), pos.getZ());
            var selected = targets.apply(previous);
            if (selected.isEmpty()) {
                continue;
            }
            if (!current.hasBlockEntity()) {
                entities.remove(pos);
                continue;
            }
            var entity = ((BlockEntityProvider) current.getBlock()).createBlockEntity(pos, current);
            if (entity != null) {
                var lookup = MinecraftClient.getInstance().world.getRegistryManager();
                QuickLitematicaContainerMaterialsAccess.readBlockEntity(entity, entry.getValue().copy(), lookup);
                // 外壳替换只叠加目标显式组件，避免默认组件清空原有文字、图案等实体内容。
                entity.readComponents(entity.createComponentMap(), selected.get().getComponentChanges());
                var nbt = QuickLitematicaContainerMaterialsAccess.blockEntityNbt(entity, lookup);
                if (entry.getValue().contains("Items") && nbt.contains("Items")) {
                    nbt.put("Items", entry.getValue().get("Items").copy());
                }
                entities.put(pos, nbt);
            } else {
                entities.remove(pos);
            }
        }
        return replacement;
    }

    static long[] countPaletteStates(LitematicaBlockStateContainer container) {
        // 原生 getBlockCounts 只在 Sponge 导入时填充，且 set 不更新缓存；统计实际存储中的索引。
        long[] counts = new long[container.getPalette().getPaletteSize()];
        var storage = container.getArray();
        for (long index = 0; index < storage.size(); index++) {
            int paletteIndex = storage.getAt(index);
            if (paletteIndex >= 0 && paletteIndex < counts.length) {
                counts[paletteIndex]++;
            }
        }
        return counts;
    }

    static LitematicaBlockStateContainer mapStates(
            LitematicaBlockStateContainer original,
            UnaryOperator<BlockState> mapper
    ) {
        List<BlockState> mapping = new ArrayList<>();
        for (int index = 0; index < original.getPalette().getPaletteSize(); index++) {
            mapping.add(mapper.apply(original.getPalette().getBlockState(index)));
        }
        return copyMappedStates(original, mapping);
    }

    private static LitematicaBlockStateContainer copyMappedStates(
            LitematicaBlockStateContainer original,
            List<BlockState> mapping
    ) {
        // 保留调色板索引，允许多个索引指向同一目标状态；不共享原始方块数组。
        var result = LitematicaBlockStateContainer.createFrom(
                original.getPalette().writeToNBT(),
                original.getBackingLongArray().clone(),
                new BlockPos(original.getSize())
        );
        if (!result.getPalette().setMapping(mapping)) {
            throw new IllegalStateException("Unable to map replacement palette");
        }
        return result;
    }

    public static void refreshFileList(MaterialListBase list) {
        LitematicaSchematic live = schematic(list);
        if (live == null || restoringEditor) {
            return;
        }
        var original = QuickLitematicaContainerMemory.original(live);
        Object2IntOpenHashMap<BlockState> totals = new Object2IntOpenHashMap<>();
        for (String region : regions(list)) {
            var container = original.getSubRegionContainer(region);
            if (container == null) {
                continue;
            }
            long[] counts = QuickLitematicaContainerMemory.originalBlockCounts(live, region);
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] > 0) {
                    totals.addTo(container.getPalette().getBlockState(index), Math.toIntExact(counts[index]));
                }
            }
        }
        var counts = new Counts(totals, totals, new Object2IntOpenHashMap<>());
        COUNTS.put(list, counts);
        FILE_LIST_VERSIONS.put(list, QuickLitematicaContainerMemory.originalVersion(live));
        restoreFileRows(list, counts);
    }

    static boolean refreshFileAfterReplacement(MaterialListBase list) {
        if (!(list instanceof LitematicaMaterialListSchematicAccessor accessor)) {
            return false;
        }
        var counts = COUNTS.get(list);
        var version = FILE_LIST_VERSIONS.get(list);
        if (counts == null || version == null || version != QuickLitematicaContainerMemory.originalVersion(
                accessor.quickcraft$getSchematic()
        )) {
            return false;
        }
        restoreFileRows(list, counts);
        return true;
    }

    private static void restoreFileRows(MaterialListBase list, Counts counts) {
        restoringEditor = true;
        try {
            var entries = MaterialListUtils.getMaterialList(
                    counts.total(),
                    counts.missing(),
                    counts.mismatch(),
                    MinecraftClient.getInstance().player
            );
            updateEditorAvailability(list, entries);
            list.setMaterialListEntries(entries);
        } finally {
            restoringEditor = false;
        }
    }

    public static void prepareEditor(
            MaterialListBase list,
            Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing,
            Object2IntOpenHashMap<BlockState> mismatch
    ) {
        COUNTS.put(list, new Counts(total.clone(), missing.clone(), mismatch.clone()));
        var entries = MaterialListUtils.getMaterialList(total, missing, mismatch, MinecraftClient.getInstance().player);
        updateEditorAvailability(list, entries);
        PENDING.put(list, entries);
    }

    public static List<MaterialListEntry> editorEntries(MaterialListBase list, List<MaterialListEntry> actual) {
        var originals = PENDING.remove(list);
        if (restoringEditor) {
            return actual;
        }
        if (originals != null) {
            return originals;
        }
        if (QuickLitematicaVoidWarehouseMaterials.isInstalled()
                && (list instanceof LitematicaMaterialListPlacementAccessor
                        || list instanceof LitematicaMaterialListSchematicAccessor)) {
            for (var entry : actual) {
                setWarehouseTarget(entry, entry.getStack());
            }
        }
        return actual;
    }

    private static Object2IntOpenHashMap<ItemType> finalItems(
            Object2IntOpenHashMap<BlockState> original,
            String context,
            java.util.Set<String> ignored
    ) {
        Object2IntOpenHashMap<ItemType> result = new Object2IntOpenHashMap<>();
        original.forEach((state, count) -> {
            ItemStack source = replacementSource(state);
            if (ignored.contains(new ItemType(source, true, true).toString())) {
                return;
            }
            BlockState finalState = targetState(state, context);
            if (finalState.isAir()) {
                return;
            }
            var replacement = QuickLitematicaContainerReplacements.getReplacementTarget(
                    source,
                    QuickLitematicaContainerReplacements.Scope.PROJECTION,
                    context
            );
            Object2IntOpenHashMap<BlockState> singleton = new Object2IntOpenHashMap<>();
            singleton.put(finalState, (int) count);
            for (var entry : MaterialListUtils.getMaterialList(
                    singleton,
                    singleton,
                    new Object2IntOpenHashMap<>(),
                    MinecraftClient.getInstance().player
            )) {
                ItemStack template = entry.getStack();
                if (replacement.isPresent() && template.getItem() == replacement.get().getItem()) {
                    template = replacement.get().copyWithCount(1);
                }
                if (!(template.getItem() instanceof BlockItem) && ignored.contains(
                        new ItemType(template, true, true).toString()
                )) {
                    continue;
                }
                result.addTo(new ItemType(template, true, true), entry.getCountTotal());
            }
        });
        return result;
    }

    private static void updateEditorAvailability(MaterialListBase list, List<MaterialListEntry> entries) {
        String context = QuickLitematicaMaterialLists.getReplacementContextKey(list);
        boolean warehouseInstalled = QuickLitematicaVoidWarehouseMaterials.isInstalled();
        for (var entry : entries) {
            var target = QuickLitematicaContainerReplacements.getReplacementTarget(
                    entry.getStack(),
                    QuickLitematicaContainerReplacements.Scope.PROJECTION,
                    context
            ).orElse(entry.getStack());
            entry.setCountAvailable(QuickLitematicaMaterialInventory.countAvailable(List.of(target)));
            if (warehouseInstalled) {
                setWarehouseTarget(entry, target);
            }
        }
    }

    private static void setWarehouseTarget(MaterialListEntry entry, ItemStack target) {
        if (entry instanceof WarehouseAvailability availability) {
            availability.quickcraft$setWarehouseTarget(target);
        }
    }

    private static void updateAvailable(List<MaterialListEntry> entries) {
        List<ItemStack> targets = new ArrayList<>(entries.size());
        for (MaterialListEntry entry : entries) {
            targets.add(entry.getStack());
        }
        int[] counts = QuickLitematicaMaterialInventory.countAvailableFor(targets);
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setCountAvailable(counts[i]);
        }
    }

    public static List<MaterialListEntry> hud(MaterialListBase list, boolean missing) {
        return hud(list, missing, missing, () -> missing ? list.getMaterialsMissingOnly(true) : list.getMaterialsAll());
    }

    public static void resetHud(MaterialListBase list) {
        HUD_CACHE.remove(list);
        QuickLitematicaEnderChestMaterials.resetHud(list);
    }

    public static List<MaterialListEntry> hud(
            MaterialListBase list,
            boolean missing,
            boolean refresh,
            java.util.function.Supplier<? extends List<MaterialListEntry>> original
    ) {
        Counts counts = COUNTS.get(list);
        var ignored = QuickLitematicaMaterialIgnores.effective(list);
        if (counts == null) {
            var nativeEntries = original.get();
            // Litematica 的 HUD 会原地排序，不能返回 Stream.toList() 的只读列表。
            return new ArrayList<>(
                    nativeEntries.stream().filter(
                            entry -> !ignored.contains(new ItemType(entry.getStack(), true, true).toString())
                    ).toList()
            );
        }
        String context = QuickLitematicaMaterialLists.getReplacementContextKey(list);
        HudCache cached = HUD_CACHE.get(list);
        if (cached == null
                || cached.counts() != counts
                || cached.revision() != QuickLitematicaContainerMemory.revision()
                || !cached.ignored().equals(ignored)
                || !cached.context().equals(context)) {
            var totalItems = finalItems(counts.total(), context, ignored);
            var missingItems = finalItems(counts.missing(), context, ignored);
            var mismatchItems = finalItems(counts.mismatch(), context, ignored);
            List<MaterialListEntry> entries = new ArrayList<>();
            for (ItemType type : totalItems.keySet()) {
                entries.add(
                        new MaterialListEntry(
                                type.getStack().copyWithCount(1),
                                totalItems.getInt(type),
                                missingItems.getInt(type),
                                mismatchItems.getInt(type),
                                0
                        )
                );
            }
            cached = new HudCache(
                    counts,
                    QuickLitematicaContainerMemory.revision(),
                    java.util.Set.copyOf(ignored),
                    context,
                    list.getHideAvailable(),
                    list.getMultiplier(),
                    cached != null && cached.counts() == counts && cached.context().equals(context) && cached
                            .revision() == QuickLitematicaContainerMemory.revision() ? cached.hidden() : Set.of(),
                    entries,
                    null
            );
            HUD_CACHE.put(list, cached);
        }
        if (!missing) {
            return cached.entries();
        }

        boolean stateChanged = cached.hideAvailable() != list.getHideAvailable() || cached.multiplier() != list
                .getMultiplier();
        boolean refreshSnapshot = refresh || cached.missingEntries() == null || stateChanged;
        if (refreshSnapshot) {
            updateAvailable(cached.entries());
            Set<String> hidden = hiddenSnapshot(list, cached.entries(), cached.hidden(), refresh || stateChanged);
            List<MaterialListEntry> filtered = buildMissingEntries(list, cached.entries(), hidden);
            cached = new HudCache(
                    cached.counts(),
                    cached.revision(),
                    cached.ignored(),
                    cached.context(),
                    list.getHideAvailable(),
                    list.getMultiplier(),
                    Set.copyOf(hidden),
                    cached.entries(),
                    filtered
            );
            HUD_CACHE.put(list, cached);
        }
        return cached.missingEntries();
    }

    static List<MaterialListEntry> buildMissingEntries(
            MaterialListBase list,
            List<MaterialListEntry> entries,
            Set<String> hidden
    ) {
        int multiplier = list.getMultiplier();
        List<MaterialListEntry> filtered = new ArrayList<>(
                entries.stream().filter(
                        entry -> !hidden.contains(new ItemType(entry.getStack(), true, true).toString())
                ).filter(
                        entry -> (multiplier == 1 ? entry.getCountMissing()
                                : multiplier * entry.getCountTotal()) > entry.getCountAvailable()
                ).toList()
        );
        filtered.sort(new fi.dy.masa.litematica.materials.MaterialListSorter(list));
        return filtered;
    }

    static Set<String> hiddenSnapshot(
            MaterialListBase list,
            List<MaterialListEntry> entries,
            Set<String> previous,
            boolean refresh
    ) {
        if (!list.getHideAvailable()) {
            return Set.of();
        }
        Set<String> present = new HashSet<>();
        for (MaterialListEntry entry : entries) {
            present.add(new ItemType(entry.getStack(), true, true).toString());
        }
        Set<String> hidden = new HashSet<>(previous);
        hidden.retainAll(present);
        if (refresh) {
            int multiplier = list.getMultiplier();
            for (MaterialListEntry entry : entries) {
                int required = multiplier == 1 ? entry.getCountMissing() : multiplier * entry.getCountTotal();
                if (entry.getCountAvailable() >= required) {
                    hidden.add(new ItemType(entry.getStack(), true, true).toString());
                }
            }
        }
        return hidden;
    }

    public static BlockState originalAt(SchematicPlacement placement, BlockPos pos) {
        var original = QuickLitematicaContainerMemory.original(placement.getSchematic());
        for (var entry : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
            String region = entry.getKey();
            var sub = entry.getValue();
            var container = original.getSubRegionContainer(region);
            if (container == null) {
                continue;
            }
            BlockPos offset = PositionUtils.getTransformedBlockPos(
                    sub.getPos(),
                    placement.getMirror(),
                    placement.getRotation()
            ).add(placement.getOrigin());
            BlockPos local = PositionUtils.getReverseTransformedBlockPos(
                    pos.subtract(offset),
                    sub.getMirror(),
                    sub.getRotation()
            );
            local = PositionUtils.getReverseTransformedBlockPos(local, placement.getMirror(), placement.getRotation());
            BlockPos min = PositionUtils.getMinCorner(
                    sub.getPos(),
                    sub.getPos().add(PositionUtils.getRelativeEndPositionFromAreaSize(original.getAreaSize(region)))
            );
            local = local.subtract(min.subtract(sub.getPos()));
            var size = container.getSize();
            if (local.getX() < 0
                    || local.getY() < 0
                    || local.getZ() < 0
                    || local.getX() >= size.getX()
                    || local.getY() >= size.getY()
                    || local.getZ() >= size.getZ()) {
                continue;
            }
            return container.get(local.getX(), local.getY(), local.getZ());
        }
        return net.minecraft.block.Blocks.AIR.getDefaultState();
    }
}
