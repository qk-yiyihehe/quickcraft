package com.yiyihehe.quickcraft.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QuickCraft 内置物品别名表。
 * 整理先使用这套解析，后续其他支持中文物品输入的功能可以复用同一入口。
 */
public final class QuickCraftItemAliases {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickCraftItemAliases.class);
    private static final String RESOURCE_PATH = "/assets/quickcraft/mappings/item_aliases.json";
    private static final AliasTable TABLE = loadBundledTable();
    private static final Map<String, Map<String, String>> DISPLAY_NAME_INDEXES = new HashMap<>();

    private QuickCraftItemAliases() {
    }

    public static List<String> getDefaultPriorityAliases() {
        return TABLE.getDefaultAliases(currentLanguage());
    }

    static List<String> getDefaultPriorityAliases(String language) {
        return TABLE.getDefaultAliases(language);
    }

    public static boolean matches(
            String configured,
            String itemId,
            String itemPath,
            String displayName,
            boolean enchanted
    ) {
        if (configured == null || itemId == null) {
            return false;
        }

        String value = normalize(configured);
        if (value.isEmpty()) {
            return false;
        }

        boolean explicitEnchantedOnly = value.startsWith("enchanted:");
        if (explicitEnchantedOnly) {
            value = normalize(value.substring("enchanted:".length()));
        }

        AliasEntry alias = TABLE.aliases().get(value);
        boolean directId = alias == null
                && (value.equals(normalize(itemId)) || value.equals(normalize(itemPath)));
        if (directId && explicitEnchantedOnly && !enchanted) {
            return false;
        }
        if (directId) {
            return true;
        }

        String targetId = alias != null ? alias.itemId() : resolveDisplayName(value);
        if (targetId.isEmpty()) {
            targetId = value;
        }
        boolean enchantedOnly = explicitEnchantedOnly || (alias != null && alias.enchantedOnly());
        if (enchantedOnly && !enchanted) {
            return false;
        }
        return targetId.equals(normalize(itemId))
                || targetId.equals(normalize(itemPath))
                || (alias == null && targetId.equals(normalize(displayName)));
    }

    private static String resolveDisplayName(String value) {
        String language = currentLanguage();
        Map<String, String> index;
        synchronized (DISPLAY_NAME_INDEXES) {
            index = DISPLAY_NAME_INDEXES.computeIfAbsent(language, ignored -> buildDisplayNameIndex());
        }
        return index.getOrDefault(value, "");
    }

    private static Map<String, String> buildDisplayNameIndex() {
        try {
            Map<String, String> index = new HashMap<>();
            for (Item item : Registries.ITEM) {
                if (item == net.minecraft.item.Items.AIR) {
                    continue;
                }
                String displayName = normalize(item.getDefaultStack().getName().getString());
                if (!displayName.isEmpty()) {
                    index.putIfAbsent(displayName, Registries.ITEM.getId(item).toString());
                }
            }
            return Map.copyOf(index);
        } catch (Throwable throwable) {
            LOGGER.warn("Could not build item display-name index", throwable);
            return Map.of();
        }
    }

    private static String currentLanguage() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.options != null && client.options.language != null) {
                return client.options.language;
            }
            return Language.DEFAULT_LANGUAGE;
        } catch (Throwable throwable) {
            return Language.DEFAULT_LANGUAGE;
        }
    }

    private static AliasTable loadBundledTable() {
        try (InputStream stream = QuickCraftItemAliases.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                LOGGER.warn("Could not find bundled item alias mapping {}", RESOURCE_PATH);
                return AliasTable.empty();
            }
            return AliasTable.read(stream);
        } catch (Exception exception) {
            LOGGER.warn("Could not load bundled item alias mapping {}", RESOURCE_PATH, exception);
            return AliasTable.empty();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record AliasEntry(String itemId, boolean enchantedOnly, Map<String, List<String>> aliasesByLanguage) {
        private String aliasFor(String language) {
            List<String> localized = aliasesByLanguage.get(language);
            if (localized != null && !localized.isEmpty()) {
                return localized.get(0);
            }

            List<String> english = aliasesByLanguage.get("en_us");
            if (english != null && !english.isEmpty()) {
                return english.get(0);
            }

            for (List<String> aliases : aliasesByLanguage.values()) {
                if (!aliases.isEmpty()) {
                    return aliases.get(0);
                }
            }

            return itemId;
        }
    }

    private record AliasTable(List<AliasEntry> entries, Map<String, AliasEntry> aliases) {
        private static AliasTable empty() {
            return new AliasTable(List.of(), Map.of());
        }

        private static AliasTable read(InputStream stream) {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                List<AliasEntry> entries = new ArrayList<>();
                Map<String, AliasEntry> aliases = new HashMap<>();
                List<AliasDefinition> definitions = new ArrayList<>();
                JsonArray legacyEntries = root.getAsJsonArray("entries");
                if (legacyEntries != null) {
                    for (JsonElement element : legacyEntries) {
                        definitions.add(new AliasDefinition(element, null));
                    }
                } else {
                    addDefinitions(root.getAsJsonArray("enchantedOnly"), true, definitions);
                    addDefinitions(root.getAsJsonArray("unrestricted"), false, definitions);
                }

                for (AliasDefinition definition : definitions) {
                    JsonElement element = definition.element();
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject jsonEntry = element.getAsJsonObject();
                    String itemId = normalize(jsonEntry.get("id").getAsString());
                    if (itemId.isEmpty()) {
                        continue;
                    }

                    Map<String, List<String>> aliasesByLanguage = readAliases(jsonEntry.getAsJsonObject("aliases"));
                    AliasEntry entry = new AliasEntry(
                            itemId,
                            definition.forcedEnchantedOnly() != null
                                    ? definition.forcedEnchantedOnly()
                                    : jsonEntry.has("enchantedOnly") && jsonEntry.get("enchantedOnly").getAsBoolean(),
                            aliasesByLanguage
                    );
                    entries.add(entry);
                    aliasesByLanguage.values().stream()
                            .flatMap(List::stream)
                            .map(QuickCraftItemAliases::normalize)
                            .filter(alias -> !alias.isEmpty())
                            .forEach(alias -> aliases.putIfAbsent(alias, entry));
                }

                return new AliasTable(List.copyOf(entries), Map.copyOf(aliases));
            } catch (Exception exception) {
                LOGGER.warn("Invalid item alias mapping JSON", exception);
                return empty();
            }
        }

        private static void addDefinitions(
                JsonArray jsonEntries,
                boolean enchantedOnly,
                List<AliasDefinition> definitions
        ) {
            if (jsonEntries == null) {
                return;
            }
            for (JsonElement element : jsonEntries) {
                definitions.add(new AliasDefinition(element, enchantedOnly));
            }
        }

        private static Map<String, List<String>> readAliases(JsonObject jsonAliases) {
            Map<String, List<String>> aliases = new HashMap<>();
            if (jsonAliases == null) {
                return aliases;
            }

            for (Map.Entry<String, JsonElement> languageEntry : jsonAliases.entrySet()) {
                if (!languageEntry.getValue().isJsonArray()) {
                    continue;
                }

                List<String> values = new ArrayList<>();
                for (JsonElement value : languageEntry.getValue().getAsJsonArray()) {
                    if (value.isJsonPrimitive()) {
                        String alias = value.getAsString().trim();
                        if (!alias.isEmpty()) {
                            values.add(alias);
                        }
                    }
                }
                aliases.put(normalize(languageEntry.getKey()), List.copyOf(values));
            }
            return Map.copyOf(aliases);
        }

        private List<String> getDefaultAliases(String language) {
            List<String> values = new ArrayList<>(entries.size());
            for (AliasEntry entry : entries) {
                values.add(entry.aliasFor(normalize(language)));
            }
            return List.copyOf(values);
        }

        private record AliasDefinition(JsonElement element, Boolean forcedEnchantedOnly) {
        }
    }
}
