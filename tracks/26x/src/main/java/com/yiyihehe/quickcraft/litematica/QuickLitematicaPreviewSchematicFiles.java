package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import fi.dy.masa.litematica.util.FileType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import org.jetbrains.annotations.Nullable;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;

/** 预览专用原理图读取、可见数据保留与转换文件写入；取消传播和临时文件清理集中处理。 */
final class QuickLitematicaPreviewSchematicFiles {
    private QuickLitematicaPreviewSchematicFiles() {
    }

    static void writeConvertedSchematic(LitematicaSchematic schematic, Path convertedPath, String cacheSlot) {
        try (var phase = QuickLitematicaPreviewLog.phase("转换原理图缓存保存")) {
            Path parent = convertedPath.getParent();
            Path fileName = convertedPath.getFileName();
            if (parent == null || fileName == null) {
                return;
            }

            Path temporary = convertedPath.resolveSibling(cacheSlot + ".converted.tmp.litematic");
            Path temporaryName = temporary.getFileName();
            if (temporaryName == null) {
                return;
            }

            try {
                deleteQuietly(temporary);
                if (!schematic.writeToFile(parent, temporaryName.toString(), true)) {
                    throw new IOException("Litematica rejected the converted cache write");
                }
                moveCacheFile(temporary, convertedPath);
                QuickLitematicaPreviewLog.file("转换原理图缓存提交", convertedPath);
            } catch (Exception failure) {
                LOGGER.warn("转换原理图缓存保存失败：槽={}，临时={}，最终={}", cacheSlot, temporary, convertedPath, failure);
                deleteQuietly(temporary);
                deleteQuietly(convertedPath);
            }
        }
    }
    static final Set<String> NON_VISUAL_INVENTORY_IDS = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:shulker_box", "minecraft:hopper", "minecraft:dispenser",
            "minecraft:dropper", "minecraft:crafter", "minecraft:furnace",
            "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand");

    static final Set<String> NON_RENDERED_DFU_CANDIDATES = Set.of("minecraft:hopper", "minecraft:dropper",
            "minecraft:dispenser", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:barrel", "minecraft:crafter", "minecraft:brewing_stand", "minecraft:comparator",
            "minecraft:daylight_detector", "minecraft:bed", "minecraft:beehive");

    private static final Set<String> DEFAULT_RENDERED_CONTAINER_IDS = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest", "minecraft:shulker_box");
    private static final Set<String> BLOCK_ENTITY_IDENTITY_KEYS = Set.of("id", "x", "y", "z");
    private static final Set<String> HIVE_NON_VISUAL_KEYS = Set.of("id", "x", "y", "z", "Bees", "bees", "FlowerPos", "flower_pos");

    static String canonicalBlockEntityId(String id) {
        // 原版 BlockEntityIdFix 的旧名称，以及旧调色板的熔炉/红石变体。
        return switch (id) {
            case "Furnace", "minecraft:lit_furnace" -> "minecraft:furnace";
            case "Hopper" -> "minecraft:hopper";
            case "Dropper" -> "minecraft:dropper";
            case "Trap" -> "minecraft:dispenser";
            case "Cauldron" -> "minecraft:brewing_stand";
            case "Comparator", "minecraft:powered_comparator", "minecraft:unpowered_comparator" -> "minecraft:comparator";
            case "DLDetector", "minecraft:daylight_detector_inverted" -> "minecraft:daylight_detector";
            case "Chest" -> "minecraft:chest";
            default -> id;
        };
    }

    record PreparationStats(int blockEntities, int removedInventories, int missingInventoryIds,
                            int inferredInventoryIds, int restoredBlockEntityIds, int defaultContainerNbt, Map<String, Integer> skippedByType) {
        int skippedBlockEntities() { return skippedByType.values().stream().mapToInt(Integer::intValue).sum(); }
        boolean modified() { return removedInventories > 0 || restoredBlockEntityIds > 0 || !skippedByType.isEmpty(); }
    }

    static PreparationStats preparePreviewNbt(CompoundTag root, AtomicBoolean cancelled, Predicate<String> hasRenderer,
                                              Function<String, String> inferBlockEntityId, Predicate<String> inventoryHidden) {
        int blockEntities = 0;
        int removedInventories = 0;
        int missingInventoryIds = 0;
        int inferredInventoryIds = 0;
        int restoredBlockEntityIds = 0;
        int defaultContainerNbt = 0;
        Map<String, String> inferredTypes = new HashMap<>();
        Map<String, Integer> skippedByType = new HashMap<>();
        Map<String, Boolean> skipType = new HashMap<>();
        Map<String, Boolean> inventoryTrim = new HashMap<>();
        int formatVersion = root.getIntOr("Version", -1);
        // 未知格式交给上游读取，不能假定其坐标和方块实体布局。
        if (formatVersion < 1 || formatVersion > LitematicaSchematic.SCHEMATIC_VERSION) {
            return new PreparationStats(0, 0, 0, 0, 0, 0, Map.of());
        }
        boolean wrappedFormat = formatVersion == 1;
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        for (String key : regions.keySet()) {
            CompoundTag reg = regions.getCompoundOrEmpty(key);
            ListTag teList = reg.getListOrEmpty("TileEntities");
            InventoryPalette inventoryPalette = null;
            boolean paletteRead = false;
            // 倒序删记录，保留其余记录的顺序；只修改预览临时 NBT。
            for (int i = teList.size() - 1; i >= 0; i--) {
                throwIfCancelled(cancelled);
                CompoundTag entry = teList.getCompoundOrEmpty(i);
                CompoundTag te = wrappedFormat ? entry.getCompoundOrEmpty("TileNBT") : entry;
                CompoundTag position = wrappedFormat ? entry : te;
                blockEntities++;
                String rawId = te.getStringOr("id", "");
                String id = canonicalBlockEntityId(rawId);
                boolean hasInventory = te.contains("Items") || te.contains("Inventory");
                boolean needsBlockType = rawId.isEmpty() || id.equals("minecraft:bed")
                        || isNonVisualInventory(id) || NON_RENDERED_DFU_CANDIDATES.contains(id)
                        || DEFAULT_RENDERED_CONTAINER_IDS.contains(id);
                if (needsBlockType && !paletteRead) {
                    inventoryPalette = InventoryPalette.from(reg);
                    paletteRead = true;
                }
                String blockId = needsBlockType && inventoryPalette != null ? inventoryPalette.idAt(position) : "";
                if (rawId.isEmpty()) {
                    if (hasInventory) missingInventoryIds++;
                    id = blockId.isEmpty() ? "" : inferredTypes.computeIfAbsent(blockId, inferBlockEntityId);
                    // 必须在删库存前写回真实类型，否则上游失去推断线索后会默认成活塞。
                    if (!id.isEmpty()) {
                        te.putString("id", id);
                        restoredBlockEntityIds++;
                    }
                    if (hasInventory && isNonVisualInventory(id)) inferredInventoryIds++;
                }
                String actualType = blockId.isEmpty() ? "" : inferredTypes.computeIfAbsent(blockId, inferBlockEntityId);
                boolean typeMatches = !id.isEmpty() && id.equals(actualType);
                // 实际方块必须能确认类型；自定义渲染器可能显示库存，不能只信 NBT 中的原版 id。
                if (typeMatches && isNonVisualInventory(id)
                        && inventoryTrim.computeIfAbsent(blockId, inventoryHidden::test)) {
                    if (te.contains("Items")) { te.remove("Items"); removedInventories++; }
                    if (te.contains("Inventory")) { te.remove("Inventory"); removedInventories++; }
                }
                // 床在部分游戏版本已无方块实体；旧 minecraft:bed 仍可能需要 NBT 转换颜色。
                boolean coloredBed = blockId.startsWith("minecraft:") && blockId.endsWith("_bed");
                if (id.isEmpty() && coloredBed) id = "minecraft:bed";
                String rendererBlockId = blockId;
                // 蜂巢的朝向/蜜量在方块状态中；仅无渲染器且没有额外负载时跳过内部蜜蜂递归。
                boolean withoutRenderer = NON_RENDERED_DFU_CANDIDATES.contains(id)
                        && (!id.equals("minecraft:beehive") || hasOnlyKeysAndEmptyComponents(te, HIVE_NON_VISUAL_KEYS))
                        && (typeMatches || (id.equals("minecraft:bed") && coloredBed)) && !rendererBlockId.isEmpty()
                        && skipType.computeIfAbsent(rendererBlockId, type -> !hasRenderer.test(type));
                // 原版空组件与缺省组件均为 EMPTY；非空组件、额外字段和自定义渲染器仍保留。
                // 动态阶段仍由实际方块状态创建默认容器，包括颜色和双箱。
                boolean defaultContainer = DEFAULT_RENDERED_CONTAINER_IDS.contains(id)
                        && hasOnlyKeysAndEmptyComponents(te, BLOCK_ENTITY_IDENTITY_KEYS) && !blockId.isEmpty()
                        && typeMatches && inventoryTrim.computeIfAbsent(blockId, inventoryHidden::test);
                if (withoutRenderer || defaultContainer) {
                    if (defaultContainer && !withoutRenderer) defaultContainerNbt++;
                    teList.remove(i);
                    skippedByType.merge(id, 1, Integer::sum);
                }
            }
        }
        return new PreparationStats(blockEntities, removedInventories, missingInventoryIds,
                inferredInventoryIds, restoredBlockEntityIds, defaultContainerNbt, Map.copyOf(skippedByType));
    }

    static boolean isNonVisualInventory(String id) {
        return NON_VISUAL_INVENTORY_IDS.contains(id)
                || (id.startsWith("minecraft:") && id.endsWith("_shulker_box"));
    }

    private static boolean hasOnlyKeysAndEmptyComponents(CompoundTag tag, Set<String> allowedKeys) {
        for (String key : tag.keySet()) {
            if (!allowedKeys.contains(key)
                    && !(key.equals("components") && tag.get(key) instanceof CompoundTag components && components.isEmpty())) {
                return false;
            }
        }
        return true;
    }

    // 旧原理图可以省略方块实体 id；直接查原始调色板，不为删库存提前进行 DFU。
    record InventoryPalette(ListTag palette, LitematicaBitArray blocks, int sizeX, int sizeY, int sizeZ) {
        @Nullable
        static InventoryPalette from(CompoundTag region) {
            ListTag palette = region.getListOrEmpty("BlockStatePalette");
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int x = Math.abs(size.getIntOr("x", 0));
            int y = Math.abs(size.getIntOr("y", 0));
            int z = Math.abs(size.getIntOr("z", 0));
            long[] packed = region.getLongArray("BlockStates").orElse(new long[0]);
            if (palette.isEmpty() || x <= 0 || y <= 0 || z <= 0) return null;
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
            long volume;
            try {
                volume = Math.multiplyExact(Math.multiplyExact((long) x, y), z);
            } catch (ArithmeticException invalidSize) {
                return null;
            }
            if (volume > packed.length * 64L / bits) return null;
            return new InventoryPalette(palette, new LitematicaBitArray(bits, volume, packed), x, y, z);
        }

        String idAt(CompoundTag blockEntity) {
            int x = blockEntity.getIntOr("x", -1);
            int y = blockEntity.getIntOr("y", -1);
            int z = blockEntity.getIntOr("z", -1);
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return "";
            // TileEntities 坐标相对区域最小角；负 Size 仅表示选区方向。
            int index = blocks.getAt(((long) y * sizeZ + z) * sizeX + x);
            return index < palette.size() ? palette.getCompoundOrEmpty(index).getStringOr("Name", "") : "";
        }
    }

    @Nullable
    static LitematicaSchematic readSchematic(
            Path path,
            AtomicBoolean cancelled,
            boolean required
    ) {
        try (var phase = QuickLitematicaPreviewLog.phase("原理图预处理/读取/转换总入口")) {
            QuickLitematicaPreviewLog.file("原理图读取入口", path);
            if (QuickLitematicaPreviewLog.enabled()) LOGGER.info("DFU 配置快照：模式={}，缺省源数据版本={}，实际调用和版本另见 DFU 钩子",
                    fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_MODE.getOptionListValue(),
                    fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue());
            if (!Files.isRegularFile(path)) {
                LOGGER.info("原理图读取返回空：原因=file_missing，路径={}，必需={}", path, required);
                return null;
            }

            Path directory = path.getParent();
            Path fileName = path.getFileName();
            if (directory == null || fileName == null) {
                return null;
            }

            LitematicaSchematic schematic = readPreprocessedSchematic(path, cancelled);

            if (schematic == null) {
                throwIfCancelled(cancelled);
                LOGGER.info("原理图普通读取/回退：路径={}", path);
                schematic = LitematicaSchematic.createFromFile(
                        directory,
                        fileName.toString(),
                        FileType.LITEMATICA_SCHEMATIC
                );
            }

            throwIfCancelled(cancelled);
            if (schematic == null && required) {
                throw new IllegalStateException("Cannot read litematic file");
            }
            if (schematic != null) LOGGER.info("原理图读取完成：路径={}，数据版本={}，区域数={}，metadata={}", path,
                    schematic.getMetadata().getMinecraftDataVersion(), schematic.getAreas().size(), schematic.getMetadata().getName());
            else LOGGER.warn("原理图读取返回空：原因=upstream_returned_null，路径={}，必需={}，具体异常需上游观测", path, required);
            return schematic;
        }
    }

    @Nullable
    static LitematicaSchematic readPreprocessedSchematic(Path sourcePath, AtomicBoolean cancelled) {
        throwIfCancelled(cancelled);
        String name = sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : "";
        if (!name.endsWith(".litematic")) return null;
        try {
            CompoundTag root;
            PreparationStats stats;
            try (var phase = QuickLitematicaPreviewLog.phase("原理图快速预处理")) {
                try (InputStream is = Files.newInputStream(sourcePath);
                     BufferedInputStream bis = new BufferedInputStream(is);
                     GZIPInputStream gis = new GZIPInputStream(bis);
                     DataInputStream dis = new DataInputStream(gis)) {
                    root = NbtIo.read(dis, NbtAccounter.create(256L * 1024L * 1024L));
                }
                throwIfCancelled(cancelled);
                if (root == null || !root.contains("Regions")) return null;
                int formatVersion = root.getIntOr("Version", -1);
                // 未知格式继续使用上游带验证的文件入口。
                if (formatVersion < 1 || formatVersion > LitematicaSchematic.SCHEMATIC_VERSION) return null;
                int declaredSourceVersion = root.getIntOr("MinecraftDataVersion", 0);
                LOGGER.info("预处理 NBT 读取完成：源={}，数据版本={}，文件格式版本={}", sourcePath,
                        declaredSourceVersion, formatVersion);
                int effectiveSourceVersion = Math.max(declaredSourceVersion,
                        fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue());
                stats = preparePreviewNbt(root, cancelled,
                        QuickLitematicaPreview3D::hasPreviewRendererForBlockEntity,
                        blockId -> QuickLitematicaPreview3D.inferPreviewBlockEntityId(blockId, effectiveSourceVersion),
                        QuickLitematicaPreview3D::isPreviewInventoryHidden);
                LOGGER.info("预处理删减结果：源={}，删减库存字段数={}，缺少 id 的库存记录={}，调色板识别普通容器={}，补全方块实体 id={}，方块实体原数={}，跳过无渲染器/无负载 NBT DFU={}，其中默认容器 NBT={}，保留={}，跳过类型统计={}，NBT 已修改={}",
                        sourcePath, stats.removedInventories(), stats.missingInventoryIds(), stats.inferredInventoryIds(),
                        stats.restoredBlockEntityIds(), stats.blockEntities(), stats.skippedBlockEntities(), stats.defaultContainerNbt(),
                        stats.blockEntities() - stats.skippedBlockEntities(), stats.skippedByType(), stats.modified());
            }
            throwIfCancelled(cancelled);
            if (!stats.modified()) return null;
            Path cacheDir = cacheDirectory();
            if (cacheDir == null) return null;
            Path temporary = cacheDir.resolve("fast-" + Long.toUnsignedString(System.nanoTime()) + ".fast.tmp.litematic");
            // 上游 NBT 构造器先解析、后初始化 converter，旧数据后处理会抛异常；文件入口初始化顺序正确。
            try (var phase = QuickLitematicaPreviewLog.phase("预处理临时文件写入/上游解析")) {
                try (OutputStream os = Files.newOutputStream(temporary);
                     BufferedOutputStream bos = new BufferedOutputStream(os);
                     GZIPOutputStream gos = new GZIPOutputStream(bos);
                     DataOutputStream dos = new DataOutputStream(gos)) {
                    NbtIo.write(root, dos);
                }
                throwIfCancelled(cancelled);
                LOGGER.info("预处理投影读取：源={}，临时={}，已裁剪数据交给上游文件入口", sourcePath, temporary);
                return LitematicaSchematic.createFromFile(cacheDir, temporary.getFileName().toString(), FileType.LITEMATICA_SCHEMATIC);
            } finally {
                deleteQuietly(temporary);
            }
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (Throwable failure) {
            LOGGER.warn("预处理投影读取失败：源={}，回退上游文件读取", sourcePath, failure);
            return null;
        }
    }

    static void throwIfCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
