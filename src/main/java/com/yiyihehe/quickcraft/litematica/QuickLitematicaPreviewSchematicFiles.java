package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtCompound;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCache.*;

/** 预览专用原理图读取、可见数据保留与转换文件写入；取消传播和临时文件清理集中处理。 */
final class QuickLitematicaPreviewSchematicFiles {
    private QuickLitematicaPreviewSchematicFiles() {
    }

    static void writeConvertedSchematic(LitematicaSchematic schematic, Path convertedPath, String cacheSlot) {
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
            if (!writeSchematic(schematic, parent, temporaryName.toString())) {
                throw new IOException("Litematica rejected the converted cache write");
            }
            moveCacheFile(temporary, convertedPath);
        } catch (Exception ignored) {
            deleteQuietly(temporary);
            deleteQuietly(convertedPath);
        }
    }
    static final Set<String> NON_VISUAL_INVENTORY_IDS = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:shulker_box", "minecraft:hopper", "minecraft:dispenser",
            "minecraft:dropper", "minecraft:crafter", "minecraft:furnace",
            "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand");

    @Nullable
    static LitematicaSchematic readSchematic(
            Path path,
            AtomicBoolean cancelled,
            boolean required
    ) {
        if (!Files.isRegularFile(path)) {
            return null;
        }

        Path directory = path.getParent();
        Path fileName = path.getFileName();
        if (directory == null || fileName == null) {
            return null;
        }

        LitematicaSchematic schematic = null;
        Path fastPath = null;
        try {
            fastPath = prepareFastSchematic(path, cancelled);
            if (fastPath != null) {
                Path fastDir = fastPath.getParent();
                Path fastFile = fastPath.getFileName();
                if (fastDir != null && fastFile != null) {
                    throwIfCancelled(cancelled);
                    schematic = LitematicaSchematic.createFromFile(
                            fastDir,
                            fastFile.toString(),
                            FileType.LITEMATICA_SCHEMATIC
                    );
                }
            }
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (Throwable t) {
            LOGGER.debug("QuickCraft fast schematic preparation skipped: {}", t.getMessage());
        } finally {
            if (fastPath != null) {
                deleteQuietly(fastPath);
            }
        }

        if (schematic == null) {
            throwIfCancelled(cancelled);
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
        return schematic;
    }

    @Nullable
    static Path prepareFastSchematic(Path sourcePath, AtomicBoolean cancelled) {
        throwIfCancelled(cancelled);
        String name = sourcePath.getFileName() != null ? sourcePath.getFileName().toString() : "";
        if (!name.endsWith(".litematic")) {
            return null;
        }
        Path cacheDir = cacheDirectory();
        if (cacheDir == null) {
            return null;
        }

        Path fastPath = null;
        try (InputStream is = Files.newInputStream(sourcePath);
             BufferedInputStream bis = new BufferedInputStream(is);
             GZIPInputStream gis = new GZIPInputStream(bis);
             DataInputStream dis = new DataInputStream(gis)) {
            NbtCompound root = NbtIo.readCompound(dis, NbtSizeTracker.of(256L * 1024L * 1024L));
            throwIfCancelled(cancelled);
            if (root == null || !containsCompound(root, "Regions")) {
                return null;
            }

            boolean modified = false;
            NbtCompound regions = readCompound(root, "Regions");
            for (String key : regions.getKeys()) {
                if (!containsCompound(regions, key)) {
                    continue;
                }
                NbtCompound reg = readCompound(regions, key);
                if (containsList(reg, "TileEntities")) {
                    NbtList teList = readCompoundList(reg, "TileEntities");
                    for (int i = 0; i < teList.size(); i++) {
                        NbtCompound te = readListCompound(teList, i);
                        // 火堆物品和实体装备参与渲染，不能作为普通库存删减。
                        if (!NON_VISUAL_INVENTORY_IDS.contains(readString(te, "id"))) {
                            continue;
                        }
                        if (te.contains("Items")) {
                            te.remove("Items");
                            modified = true;
                        }
                        if (te.contains("Inventory")) {
                            te.remove("Inventory");
                            modified = true;
                        }
                    }
                }

            }

            if (!modified) {
                return null;
            }

            throwIfCancelled(cancelled);
            fastPath = cacheDir.resolve("fast-" + Long.toUnsignedString(System.nanoTime()) + ".fast.tmp.litematic");
            try (OutputStream os = Files.newOutputStream(fastPath);
                 BufferedOutputStream bos = new BufferedOutputStream(os);
                 GZIPOutputStream gos = new GZIPOutputStream(bos);
                 DataOutputStream dos = new DataOutputStream(gos)) {
                NbtIo.writeCompound(root, dos);
            }
            return fastPath;
        } catch (CancellationException cancellation) {
            if (fastPath != null) {
                deleteQuietly(fastPath);
            }
            throw cancellation;
        } catch (Throwable t) {
            if (fastPath != null) {
                deleteQuietly(fastPath);
            }
            return null;
        }
    }

    static void throwIfCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
