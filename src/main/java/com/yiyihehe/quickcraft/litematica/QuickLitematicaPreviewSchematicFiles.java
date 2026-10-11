package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtCompound;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.Nullable;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.util.HashSet;
import java.util.function.BiFunction;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtTypes;
import net.minecraft.nbt.NbtSizeValidationException;
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
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;

/** 预览专用原理图读取、可见数据保留与转换文件写入；取消传播和临时文件清理集中处理。 */
final class QuickLitematicaPreviewSchematicFiles {
    private QuickLitematicaPreviewSchematicFiles() {
    }

    static boolean writeConvertedSchematic(LitematicaSchematic schematic, Path convertedPath, String cacheSlot) {
        try (var phase = QuickLitematicaPreviewLog.phase("转换原理图缓存保存")) {
            Path parent = convertedPath.getParent();
            Path fileName = convertedPath.getFileName();
            if (parent == null || fileName == null) {
                return false;
            }

            Path temporary = convertedPath.resolveSibling(cacheSlot + ".converted.tmp.litematic");
            Path temporaryName = temporary.getFileName();
            if (temporaryName == null) {
                return false;
            }

            try {
                deleteQuietly(temporary);
                if (!writeSchematic(schematic, parent, temporaryName.toString())) {
                    throw new IOException("Litematica rejected the converted cache write");
                }
                moveCacheFile(temporary, convertedPath);
                QuickLitematicaPreviewLog.file("转换原理图缓存提交", convertedPath);
                return true;
            } catch (Exception failure) {
                LOGGER.warn("转换原理图缓存保存失败：槽={}，临时={}，最终={}", cacheSlot, temporary, convertedPath, failure);
                deleteQuietly(temporary);
                deleteQuietly(convertedPath);
                return false;
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

    static PreparationStats preparePreviewNbt(NbtCompound root, AtomicBoolean cancelled, Predicate<String> hasRenderer,
                                              Function<String, String> inferBlockEntityId, Predicate<String> inventoryHidden) {
        int formatVersion = (root.get("Version") instanceof AbstractNbtNumber version ? version.intValue() : -1);
        if (formatVersion < 1 || formatVersion > LitematicaSchematic.SCHEMATIC_VERSION) {
            return new PreparationStats(0, 0, 0, 0, 0, 0, Map.of());
        }
        Preparation preparation = new Preparation(formatVersion, cancelled, hasRenderer, inferBlockEntityId, inventoryHidden);
        NbtCompound regions = readCompound(root, "Regions");
        for (String key : regions.getKeys()) {
            NbtCompound reg = readCompound(regions, key);
            NbtList teList = readCompoundList(reg, "TileEntities");
            InventoryPalette palette = teList.isEmpty() ? null : InventoryPalette.from(reg);
            // 倒序删除仅用于已装入内存的小文件；流式入口逐条复用同一套核验规则。
            for (int i = teList.size() - 1; i >= 0; i--) {
                if (preparation.remove(readListCompound(teList, i), palette)) teList.remove(i);
            }
        }
        return preparation.stats();
    }

    private static final class Preparation {
        private final boolean wrappedFormat;
        private final AtomicBoolean cancelled;
        private final Predicate<String> hasRenderer;
        private final Function<String, String> inferBlockEntityId;
        private final Predicate<String> inventoryHidden;
        private int blockEntities, removedInventories, missingInventoryIds, inferredInventoryIds;
        private int restoredBlockEntityIds, defaultContainerNbt;
        private final Map<String, String> inferredTypes = new HashMap<>();
        private final Map<String, Integer> skippedByType = new HashMap<>();
        private final Map<String, Boolean> skipType = new HashMap<>();
        private final Map<String, Boolean> inventoryTrim = new HashMap<>();

        private Preparation(int formatVersion, AtomicBoolean cancelled, Predicate<String> hasRenderer,
                            Function<String, String> inferBlockEntityId, Predicate<String> inventoryHidden) {
            this.wrappedFormat = formatVersion == 1;
            this.cancelled = cancelled;
            this.hasRenderer = hasRenderer;
            this.inferBlockEntityId = inferBlockEntityId;
            this.inventoryHidden = inventoryHidden;
        }

        private boolean remove(NbtCompound entry, InventoryPalette inventoryPalette) {
            throwIfCancelled(cancelled);
            NbtCompound te = wrappedFormat ? readCompound(entry, "TileNBT") : entry;
            NbtCompound position = wrappedFormat ? entry : te;
            blockEntities++;
            String rawId = readString(te, "id");
            String id = canonicalBlockEntityId(rawId);
            boolean hasInventory = te.contains("Items") || te.contains("Inventory");
            boolean needsBlockType = rawId.isEmpty() || id.equals("minecraft:bed")
                    || isNonVisualInventory(id) || NON_RENDERED_DFU_CANDIDATES.contains(id)
                    || DEFAULT_RENDERED_CONTAINER_IDS.contains(id);
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
                skippedByType.merge(id, 1, Integer::sum);
                return true;
            }
            return false;
        }

        private PreparationStats stats() {
            return new PreparationStats(blockEntities, removedInventories, missingInventoryIds,
                    inferredInventoryIds, restoredBlockEntityIds, defaultContainerNbt, Map.copyOf(skippedByType));
        }
    }

    static boolean isNonVisualInventory(String id) {
        return NON_VISUAL_INVENTORY_IDS.contains(id)
                || (id.startsWith("minecraft:") && id.endsWith("_shulker_box"));
    }

    private static boolean hasOnlyKeysAndEmptyComponents(NbtCompound tag, Set<String> allowedKeys) {
        for (String key : tag.getKeys()) {
            if (!allowedKeys.contains(key)
                    && !(key.equals("components") && tag.get(key) instanceof NbtCompound components && components.isEmpty())) {
                return false;
            }
        }
        return true;
    }

    // 旧原理图可以省略方块实体 id；直接查原始调色板，不为删库存提前进行 DFU。
    record InventoryPalette(NbtList palette, LitematicaBitArray blocks, int sizeX, int sizeY, int sizeZ) {
        @Nullable
        static InventoryPalette from(NbtCompound region) {
            NbtList palette = readCompoundList(region, "BlockStatePalette");
            NbtCompound size = readCompound(region, "Size");
            if (!(region.get("BlockStates") instanceof NbtLongArray packedTag)
                    || !(size.get("x") instanceof AbstractNbtNumber sx)
                    || !(size.get("y") instanceof AbstractNbtNumber sy)
                    || !(size.get("z") instanceof AbstractNbtNumber sz)) return null;
            int x = Math.abs(sx.intValue());
            int y = Math.abs(sy.intValue());
            int z = Math.abs(sz.intValue());
            long[] packed = packedTag.getLongArray();
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

        String idAt(NbtCompound blockEntity) {
            if (!(blockEntity.get("x") instanceof AbstractNbtNumber nx)
                    || !(blockEntity.get("y") instanceof AbstractNbtNumber ny)
                    || !(blockEntity.get("z") instanceof AbstractNbtNumber nz)) return "";
            int x = nx.intValue();
            int y = ny.intValue();
            int z = nz.intValue();
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return "";
            // TileEntities 坐标相对区域最小角；负 Size 仅表示选区方向。
            int index = blocks.getAt(((long) y * sizeZ + z) * sizeX + x);
            return index < palette.size() ? readString(readListCompound(palette, index), "Name") : "";
        }
    }

    private static final long PREVIEW_NBT_BYTES = 256L * 1024L * 1024L;
    // NBT 对象计量通常远大于原始字节；大文件直接逐条读取，避免先分配到配额再重读。
    private static final long STREAMING_RAW_BYTES = 16L * 1024L * 1024L;

    static long gzipExpandedSizeHint(Path source) throws IOException {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(source.toFile(), "r")) {
            if (input.length() < 18 || input.readUnsignedShort() != 0x1f8b) return 0L;
            input.seek(input.length() - 4);
            // ISIZE 仅用于选择读取路径（多成员/超过 4 GiB 时不可靠），不替代解压、校验或预算。
            return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
        }
    }

    static int sourceDataVersion(NbtCompound root, int defaultDataVersion) {
        // 上游仅在缺少有效版本字段时使用缺省值；明确声明的旧版本仍需按旧格式解释。
        return root.get("MinecraftDataVersion") instanceof AbstractNbtNumber version ? version.intValue() : defaultDataVersion;
    }

    record StreamingPreparation(NbtCompound root, PreparationStats stats) {}

    @Nullable
    static StreamingPreparation readStreamingPreviewNbt(Path source, AtomicBoolean cancelled, int defaultDataVersion,
                                                        Predicate<String> hasRenderer,
                                                        BiFunction<String, Integer, String> inferBlockEntityId,
                                                        Predicate<String> inventoryHidden) throws IOException {
        throwIfCancelled(cancelled);
        long sourceSize = Files.size(source);
        var sourceTime = Files.getLastModifiedTime(source);
        NbtSizeTracker retained = NbtSizeTracker.of(QuickLitematicaPreviewVertexBudget.nbtByteLimit(PREVIEW_NBT_BYTES));
        NbtCompound root;
        // 调色板、尺寸、数据版本可能排在 TileEntities 后面，先独立读取这些信息。
        try (DataInputStream input = previewInput(source, cancelled)) {
            readRootHeader(input);
            root = readStreamingMetadata(input, 0, retained, cancelled);
        }
        retained = NbtSizeTracker.of(QuickLitematicaPreviewVertexBudget.nbtByteLimit(PREVIEW_NBT_BYTES));
        retained.add(root.getSizeInBytes());
        int formatVersion = (root.get("Version") instanceof AbstractNbtNumber version ? version.intValue() : -1);
        if (formatVersion < 1 || formatVersion > LitematicaSchematic.SCHEMATIC_VERSION) return null;
        int sourceVersion = sourceDataVersion(root, defaultDataVersion);
        Preparation preparation = new Preparation(formatVersion, cancelled, hasRenderer,
                blockId -> inferBlockEntityId.apply(blockId, sourceVersion), inventoryHidden);
        // 逐条核验后才保留记录；单条负载和最终保留的数据仍各受原内存预算约束。
        try (DataInputStream input = previewInput(source, cancelled)) {
            readRootHeader(input);
            readStreamingEntries(input, root, 0, preparation, retained, cancelled);
        }
        if (sourceSize != Files.size(source) || !sourceTime.equals(Files.getLastModifiedTime(source))) {
            throw new IOException("Schematic changed between streaming passes");
        }
        return new StreamingPreparation(root, preparation.stats());
    }

    private static DataInputStream previewInput(Path source, AtomicBoolean cancelled) throws IOException {
        InputStream buffered = new BufferedInputStream(Files.newInputStream(source));
        InputStream compressed;
        try { compressed = new GZIPInputStream(buffered); }
        catch (IOException | RuntimeException | Error failure) { buffered.close(); throw failure; }
        return new DataInputStream(new FilterInputStream(compressed) {
            @Override public int read() throws IOException {
                throwIfCancelled(cancelled);
                return in.read();
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                throwIfCancelled(cancelled);
                return in.read(bytes, offset, length);
            }
            @Override public long skip(long length) throws IOException {
                throwIfCancelled(cancelled);
                return in.skip(length);
            }
        });
    }

    private static void readRootHeader(DataInputStream input) throws IOException {
        if (input.readUnsignedByte() != 10) throw new IOException("Schematic root is not a compound");
        input.readUTF();
    }

    private static NbtCompound readStreamingMetadata(DataInputStream input, int level, NbtSizeTracker retained,
                                                     AtomicBoolean cancelled) throws IOException {
        NbtCompound result = new NbtCompound();
        Set<String> seen = new HashSet<>();
        retained.pushStack();
        try {
            retained.add(48);
            int type;
            while ((type = input.readUnsignedByte()) != 0) {
                throwIfCancelled(cancelled);
                String name = input.readUTF();
                if (!seen.add(name)) throw new IOException("Duplicate schematic field: " + name);
                retained.add(36L + 2L * name.length());
                if (type == 10 && ((level == 0 && name.equals("Regions")) || level == 1)) {
                    result.put(name, readStreamingMetadata(input, level + 1, retained, cancelled));
                } else if (level == 2 && name.equals("TileEntities") && type == 9) {
                    NbtTypes.byId(type).skip(input, retained);
                } else {
                    result.put(name, NbtTypes.byId(type).read(input, retained));
                }
            }
            return result;
        } finally {
            retained.popStack();
        }
    }

    private static void readStreamingEntries(DataInputStream input, NbtCompound target, int level,
                                             Preparation preparation, NbtSizeTracker retained,
                                             AtomicBoolean cancelled) throws IOException {
        InventoryPalette palette = level == 2 ? InventoryPalette.from(target) : null;
        retained.pushStack();
        try {
            int type;
            while ((type = input.readUnsignedByte()) != 0) {
                throwIfCancelled(cancelled);
                String name = input.readUTF();
                if (type == 10 && ((level == 0 && name.equals("Regions")) || level == 1)
                        && target.get(name) instanceof NbtCompound child) {
                    readStreamingEntries(input, child, level + 1, preparation, retained, cancelled);
                } else if (level == 2 && name.equals("TileEntities") && type == 9) {
                    int elementType = input.readUnsignedByte();
                    int count = input.readInt();
                    if (count < 0 || (elementType == 0 && count != 0)) throw new IOException("Invalid block entity list");
                    NbtList entries = new NbtList();
                    retained.add(36);
                    for (int i = 0; i < count; i++) {
                        throwIfCancelled(cancelled);
                        NbtSizeTracker entryBudget = NbtSizeTracker.of(QuickLitematicaPreviewVertexBudget.nbtByteLimit(PREVIEW_NBT_BYTES));
                        for (int depth = 0; depth <= retained.getDepth(); depth++) entryBudget.pushStack();
                        NbtElement entry = NbtTypes.byId(elementType).read(input, entryBudget);
                        if (entry instanceof NbtCompound compound && preparation.remove(compound, palette)) continue;
                        retained.add(4L + entry.getSizeInBytes());
                        entries.add(entry);
                    }
                    target.put(name, entries);
                } else {
                    NbtTypes.byId(type).skip(input, retained);
                }
            }
        } finally {
            retained.popStack();
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
            NbtCompound root = null;
            PreparationStats stats;
            try (var phase = QuickLitematicaPreviewLog.phase("原理图快速预处理")) {
                StreamingPreparation streaming = null;
                long expandedHint = gzipExpandedSizeHint(sourcePath);
                if (expandedHint >= STREAMING_RAW_BYTES) {
                    LOGGER.info("预处理直接逐条读取：源={}，GZIP 解压体积提示={} B，避免完整读取触限后重读", sourcePath, expandedHint);
                    streaming = readStreamingPreviewNbt(sourcePath, cancelled,
                            fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue(),
                            QuickLitematicaPreview3D::hasPreviewRendererForBlockEntity,
                            QuickLitematicaPreview3D::inferPreviewBlockEntityId,
                            QuickLitematicaPreview3D::isPreviewInventoryHidden);
                    if (streaming == null) return null;
                    root = streaming.root();
                } else try (DataInputStream dis = previewInput(sourcePath, cancelled)) {
                    root = NbtIo.readCompound(dis, NbtSizeTracker.of(PREVIEW_NBT_BYTES));
                } catch (NbtSizeValidationException limit) {
                    LOGGER.info("预处理完整 NBT 达到安全限制，尝试逐条读取：源={}，预算={} B，原因={}", sourcePath, PREVIEW_NBT_BYTES, limit.getMessage());
                    streaming = readStreamingPreviewNbt(sourcePath, cancelled,
                            fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue(),
                            QuickLitematicaPreview3D::hasPreviewRendererForBlockEntity,
                            QuickLitematicaPreview3D::inferPreviewBlockEntityId,
                            QuickLitematicaPreview3D::isPreviewInventoryHidden);
                    if (streaming == null) return null;
                    root = streaming.root();
                }
                throwIfCancelled(cancelled);
                if (root == null || !root.contains("Regions")) return null;
                int formatVersion = root.get("Version") instanceof AbstractNbtNumber version ? version.intValue() : -1;
                // 未知格式继续使用上游带验证的文件入口。
                if (formatVersion < 1 || formatVersion > LitematicaSchematic.SCHEMATIC_VERSION) return null;
                int declaredSourceVersion = root.get("MinecraftDataVersion") instanceof AbstractNbtNumber version ? version.intValue() : 0;
                LOGGER.info("预处理 NBT 读取完成：源={}，数据版本={}，文件格式版本={}", sourcePath,
                        declaredSourceVersion, formatVersion);
                int effectiveSourceVersion = sourceDataVersion(root,
                        fi.dy.masa.litematica.config.Configs.Generic.DATAFIXER_DEFAULT_SCHEMA.getIntegerValue());
                stats = streaming != null ? streaming.stats() : preparePreviewNbt(root, cancelled,
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
                    NbtIo.writeCompound(root, dos);
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
