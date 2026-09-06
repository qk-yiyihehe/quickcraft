package com.yiyihehe.quickcraft.futurecompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;

/**
 * 三类问题扫描（方块 id / 物品 id / 状态可构造性），映射感知：已有可用映射的条目不再报，
 * 表里存在但目标当前不存在的条目报为失效映射。只读，不改 NBT。
 */
public final class SchematicScanner {
    private SchematicScanner() {
    }

    public static ScanResult scan(CompoundTag root, RegistrySnapshot registries, FutureCompatMappings mappings) {
        Map<String, Integer> unknownBlocks = new TreeMap<>();
        Map<String, Integer> unknownItems = new TreeMap<>();
        Map<String, Map<String, String>> mismatchSamples = new TreeMap<>();
        Map<String, String> mismatchIds = new TreeMap<>();
        Map<String, Integer> mismatchCounts = new TreeMap<>();
        Map<String, ScanResult.InvalidMapping> invalidMappings = new LinkedHashMap<>();
        Map<String, ScanResult.AutoMapped> autoMapped = new TreeMap<>();

        SchematicNbt.forEachRegion(root, region -> {
            SchematicNbt.forEachPaletteEntry(region, entry -> {
                if (entry instanceof CompoundTag compound) {
                    scanPaletteCompound(compound, registries, mappings, unknownBlocks,
                            mismatchIds, mismatchSamples, mismatchCounts, invalidMappings, autoMapped);
                } else if (entry instanceof StringTag legacy) {
                    scanLegacyPaletteId(Ids.normalize(legacy.asString().orElse("")),
                            registries, mappings, unknownBlocks, invalidMappings, autoMapped);
                }
            });
            scanItemLists(region, registries, mappings, unknownItems, invalidMappings, autoMapped);
        });

        return new ScanResult(
                toCountList(unknownBlocks, ScanResult.UnknownBlock::new),
                toCountList(unknownItems, ScanResult.UnknownItem::new),
                stateMismatchList(mismatchIds, mismatchSamples, mismatchCounts),
                List.copyOf(invalidMappings.values()),
                List.copyOf(autoMapped.values()));
    }

    private static void scanPaletteCompound(
            CompoundTag compound,
            RegistrySnapshot registries,
            FutureCompatMappings mappings,
            Map<String, Integer> unknownBlocks,
            Map<String, String> mismatchIds,
            Map<String, Map<String, String>> mismatchSamples,
            Map<String, Integer> mismatchCounts,
            Map<String, ScanResult.InvalidMapping> invalidMappings,
            Map<String, ScanResult.AutoMapped> autoMapped
    ) {
        String id = Ids.normalize(compound.getString(SchematicNbt.NAME_KEY).orElse(""));
        if (id.isEmpty()) {
            return;
        }
        Map<String, String> properties = SchematicNbt.readProperties(compound);

        if (!registries.hasBlock(id)) {
            String target = mappings.blockTarget(id);
            if (target == null) {
                unknownBlocks.merge(id, 1, Integer::sum);
            } else if (registries.hasBlock(target)) {
                autoMapped.putIfAbsent(autoKey("block", id),
                        new ScanResult.AutoMapped("block", id, target));
            } else {
                invalidMappings.putIfAbsent(invalidKey("block", id),
                        new ScanResult.InvalidMapping("block", id, target, "target_missing"));
            }
            return;
        }

        if (registries.blockStateExists(id, properties)) {
            return;
        }
        if (mappings.stateTarget(id, properties).isPresent()) {
            return;
        }
        String key = StateMapping.keyOf(id, properties);
        mismatchIds.putIfAbsent(key, id);
        mismatchSamples.putIfAbsent(key, properties);
        mismatchCounts.merge(key, 1, Integer::sum);
    }

    private static void scanLegacyPaletteId(
            String id,
            RegistrySnapshot registries,
            FutureCompatMappings mappings,
            Map<String, Integer> unknownBlocks,
            Map<String, ScanResult.InvalidMapping> invalidMappings,
            Map<String, ScanResult.AutoMapped> autoMapped
    ) {
        if (id.isEmpty() || registries.hasBlock(id)) {
            return;
        }
        String target = mappings.blockTarget(id);
        if (target == null) {
            unknownBlocks.merge(id, 1, Integer::sum);
        } else if (registries.hasBlock(target)) {
            autoMapped.putIfAbsent(autoKey("block", id),
                    new ScanResult.AutoMapped("block", id, target));
        } else {
            invalidMappings.putIfAbsent(invalidKey("block", id),
                    new ScanResult.InvalidMapping("block", id, target, "target_missing"));
        }
    }

    private static void scanItemLists(
            CompoundTag region,
            RegistrySnapshot registries,
            FutureCompatMappings mappings,
            Map<String, Integer> unknownItems,
            Map<String, ScanResult.InvalidMapping> invalidMappings,
            Map<String, ScanResult.AutoMapped> autoMapped
    ) {
        for (String listKey : new String[]{SchematicNbt.TILE_ENTITIES_KEY, SchematicNbt.ENTITIES_KEY}) {
            SchematicNbt.forEachItemList(region, listKey, list ->
                    SchematicNbt.forEachItemStack(list, stack -> {
                        String id = Ids.normalize(stack.getString(SchematicNbt.ITEM_ID_KEY).orElse(""));
                        if (id.isEmpty() || registries.hasItem(id)) {
                            return;
                        }
                        String target = mappings.itemTarget(id);
                        if (target == null) {
                            unknownItems.merge(id, 1, Integer::sum);
                        } else if (registries.hasItem(target)) {
                            autoMapped.putIfAbsent(autoKey("item", id),
                                    new ScanResult.AutoMapped("item", id, target));
                        } else {
                            invalidMappings.putIfAbsent(invalidKey("item", id),
                                    new ScanResult.InvalidMapping("item", id, target, "target_missing"));
                        }
                    }));
        }
    }

    private static String invalidKey(String kind, String sourceId) {
        return kind + "|" + sourceId;
    }

    private static String autoKey(String kind, String sourceId) {
        return kind + "|" + sourceId;
    }

    private static <R> List<R> toCountList(Map<String, Integer> counts, java.util.function.BiFunction<String, Integer, R> factory) {
        List<R> result = new ArrayList<>();
        counts.forEach((id, count) -> result.add(factory.apply(id, count)));
        return result;
    }

    private static List<ScanResult.StateMismatch> stateMismatchList(
            Map<String, String> ids, Map<String, Map<String, String>> samples, Map<String, Integer> counts) {
        List<ScanResult.StateMismatch> result = new ArrayList<>();
        counts.forEach((key, count) -> result.add(new ScanResult.StateMismatch(
                ids.get(key), samples.get(key), count)));
        return result;
    }
}
