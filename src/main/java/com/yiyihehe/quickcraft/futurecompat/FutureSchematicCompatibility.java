package com.yiyihehe.quickcraft.futurecompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 未来版本 .litematic 兼容编排：映射装载（内置表，Phase 5a 起并入玩家表）+ 两条改写入口。
 * 扫描/改写核心在 {@link SchematicScanner}/{@link SchematicRewriter}（纯函数），
 * 本类只做环境判断（开关/世界）与 Litematica 加载衔接。
 */
public final class FutureSchematicCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger(FutureSchematicCompatibility.class);
    private static final String MAPPINGS_RESOURCE = "/assets/quickcraft/litematica_compat.json";
    private static final AtomicBoolean MAPPINGS_LOADED = new AtomicBoolean();
    private static final Object MAPPINGS_LOCK = new Object();
    private static volatile FutureCompatMappings mappings = FutureCompatMappings.EMPTY;
    private static volatile FutureCompatMappings builtin = FutureCompatMappings.EMPTY;

    private FutureSchematicCompatibility() {
    }

    /** 供 LitematicaSchematicFutureCompatMixin 在 readFromNBT HEAD 调用；幂等，未命中零改动。 */
    public static boolean rewriteForLitematicaLoad(NbtCompound root) {
        if (!QuickCraftConfigs.isMapFutureLitematicIdsEnabled()) {
            return false;
        }
        RegistrySnapshot registries = liveSnapshot();
        if (registries == null) {
            return false;
        }
        FutureCompatMappings current = currentMappings();
        if (current.isEmpty()) {
            return false;
        }

        SchematicRewriter.RewriteResult result = SchematicRewriter.rewrite(root, registries, current);
        if (result.changed()) {
            LOGGER.info("Applied future-version compatibility to a Litematica schematic load: renamed {} block id(s), {} item id(s), {} full state(s); skipped {} invalid mapping(s)",
                    result.blocks(), result.items(), result.states(), result.invalidSkipped());
        }
        return result.changed();
    }

    /** QuickCraft 3D 预览的加载入口：读一次 NBT → 扫描/改写在内存完成 → invoker mixin 直接解析（方案 v2.1 §6）。 */
    public static LitematicaSchematic loadSchematic(DirectoryEntry entry) {
        if (!QuickCraftConfigs.isMapFutureLitematicIdsEnabled()) {
            return createFromFile(entry);
        }

        FutureCompatMappings current = currentMappings();
        RegistrySnapshot registries = liveSnapshot();
        if (current.isEmpty() || registries == null) {
            return createFromFile(entry);
        }

        Path file = entry.getFullPath();
        NbtCompound root;
        try (InputStream input = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(input, NbtSizeTracker.of(Long.MAX_VALUE));
        } catch (IOException e) {
            LOGGER.warn("Failed to read '{}' for future-version compatibility, using the default parse path", file, e);
            return createFromFile(entry);
        }

        // 版本门（方案 v2.1 §3）：仅在"Schema 支持且为未来版本"时走内存改写解析；
        // 其余场合回退 createFromFile，保留 Litematica 原生行为与报错
        SchematicVersionGate.ProbeDecision decision = SchematicVersionGate.vanilla().classify(
                root.getInt("Version", -1), root.getInt("MinecraftDataVersion", 0));
        if (decision != SchematicVersionGate.ProbeDecision.FUTURE) {
            return createFromFile(entry);
        }

        SchematicRewriter.RewriteResult result = SchematicRewriter.rewrite(root, registries, current);
        if (!result.changed()) {
            return createFromFile(entry);
        }

        LOGGER.info("Applied future-version compatibility to '{}': renamed {} block id(s), {} item id(s), {} full state(s); skipped {} invalid mapping(s)",
                entry.getName(), result.blocks(), result.items(), result.states(), result.invalidSkipped());

        // 内存解析：公开构造器 LitematicaSchematic(Path, NbtCompound, FileType) 内部即 readFromNBT
        try {
            LitematicaSchematic schematic = new LitematicaSchematic(file, root, FileType.LITEMATICA_SCHEMATIC);
            if (schematic.getSubRegionCount() > 0) {
                return schematic;
            }
            LOGGER.warn("In-memory parse produced no regions for '{}'; retrying via the default parse path", file);
        } catch (Throwable t) {
            LOGGER.warn("In-memory parse threw for '{}'; retrying via the default parse path", file, t);
        }
        return createFromFile(entry);
    }

    private static LitematicaSchematic createFromFile(DirectoryEntry entry) {
        return LitematicaSchematic.createFromFile(entry.getDirectory(), entry.getName(), FileType.LITEMATICA_SCHEMATIC);
    }

    @Nullable
    static RegistrySnapshot liveSnapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null ? new VanillaRegistrySnapshot(client.world.getRegistryManager()) : null;
    }

    static FutureCompatMappings currentMappings() {
        loadMappingsIfNeeded();
        return mappings;
    }

    static Path userMappingsLegacyPath() {
        return FileUtils.getConfigDirectoryAsPath().resolve("litematica_user_mappings.json");
    }

    /** 用户映射：优先读 quickcraft.json 的 FutureCompat 节；旧独立文件一次性迁移进来。 */
    static FutureCompatUserMappings loadUserMappings() {
        JsonObject section = QuickCraftConfigs.futureCompatSection;
        if (section != null) {
            return FutureCompatUserMappings.parse(section);
        }

        Path legacy = userMappingsLegacyPath();
        if (Files.isRegularFile(legacy)) {
            try {
                JsonElement element = JsonUtils.parseJsonFileAsPath(legacy);
                if (element != null && element.isJsonObject()) {
                    FutureCompatUserMappings user = FutureCompatUserMappings.parse(element.getAsJsonObject());
                    QuickCraftConfigs.futureCompatSection = user.toJson();
                    QuickCraftConfigs.saveToFile();
                    Files.deleteIfExists(legacy);
                    LOGGER.info("Migrated legacy user future-compat mappings from {} into the FutureCompat section of quickcraft.json", legacy);
                    return user;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to migrate legacy user future-compat mappings from {}", legacy, e);
            }
        }
        return FutureCompatUserMappings.empty();
    }

    /** 编辑器保存后调用：写回 quickcraft.json 的 FutureCompat 节（走作者自己的配置读写链）。 */
    static void saveUserMappingsToConfig(FutureCompatUserMappings user) {
        QuickCraftConfigs.futureCompatSection = user.toJson();
        QuickCraftConfigs.saveToFile();
    }

    /** 管理视图用：内置映射表（不含用户覆盖）。 */
    static FutureCompatMappings builtinMappings() {
        loadMappingsIfNeeded();
        return builtin;
    }

    /** 编辑器保存后调用：丢弃合并表，下次访问按 内置+用户 重新装载；期间短暂空表由指纹机制自愈。 */
    static void reloadMappings() {
        mappings = FutureCompatMappings.EMPTY;
        MAPPINGS_LOADED.set(false);
    }

    /** 供预览缓存令牌使用：合并表（内置+用户）内容指纹，映射变化即整体失效预览缓存。 */
    public static String mappingsFingerprint() {
        return currentMappings().fingerprint();
    }

    private static void loadMappingsIfNeeded() {
        if (MAPPINGS_LOADED.get()) {
            return;
        }

        synchronized (MAPPINGS_LOCK) {
            if (MAPPINGS_LOADED.get()) {
                return;
            }

                try (InputStream in = FutureSchematicCompatibility.class.getResourceAsStream(MAPPINGS_RESOURCE)) {
                    if (in == null) {
                        LOGGER.warn("Missing {}; future-version .litematic compatibility is disabled", MAPPINGS_RESOURCE);
                    } else {
                        JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        builtin = parseMappings(root);
                        FutureCompatUserMappings user = loadUserMappings();
                        mappings = builtin.mergedWith(user.toMappings());
                        LOGGER.info("Loaded future-version mappings from {}: {} block, {} item, {} state builtin + {} user file entries",
                                MAPPINGS_RESOURCE,
                                builtin.blockMappingCount(), builtin.itemMappingCount(), builtin.stateMappingCount(),
                                user.isEmpty() ? 0 : 1);
                    }
                } catch (Exception e) {
                LOGGER.warn("Failed to load {}; future-version .litematic compatibility is disabled", MAPPINGS_RESOURCE, e);
            } finally {
                MAPPINGS_LOADED.set(true);
            }
        }
    }

    static FutureCompatMappings parseMappings(JsonObject root) {
        Map<String, String> blocks = readIdMappings(root, "blocks");
        Map<String, String> items = readIdMappings(root, "items");
        List<StateMapping> states = new ArrayList<>();
        if (root.get("states") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonObject entry)) {
                    continue;
                }
                StateMapping mapping = readStateMapping(entry);
                if (mapping != null) {
                    states.add(mapping);
                }
            }
        }
        return FutureCompatMappings.of(blocks, items, states);
    }

    @Nullable
    private static StateMapping readStateMapping(JsonObject entry) {
        JsonObject source = entry.getAsJsonObject("source");
        JsonObject target = entry.getAsJsonObject("target");
        if (source == null || target == null) {
            return null;
        }
        String sourceName = source.has("name") ? source.get("name").getAsString() : "";
        String targetName = target.has("name") ? target.get("name").getAsString() : "";
        return new StateMapping(sourceName, readProperties(source), targetName, readProperties(target));
    }

    private static Map<String, String> readProperties(JsonObject owner) {
        Map<String, String> properties = new HashMap<>();
        if (owner.get("properties") instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> property : object.entrySet()) {
                if (property.getValue().isJsonPrimitive()) {
                    properties.put(property.getKey(), property.getValue().getAsString());
                }
            }
        }
        return properties;
    }

    private static Map<String, String> readIdMappings(JsonObject root, String key) {
        if (!(root.get(key) instanceof JsonObject entries)) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> mapping : entries.entrySet()) {
            if (mapping.getValue().isJsonPrimitive()) {
                result.put(mapping.getKey(), mapping.getValue().getAsString());
            }
        }
        return result;
    }
}
