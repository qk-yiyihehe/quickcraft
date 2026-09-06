package com.yiyihehe.quickcraft.futurecompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家在游戏内编辑的未来版本映射。内容持久化在 quickcraft.json 的 "FutureCompat" 节
 * （随作者的配置读写链一起保存，方案对齐作者 QuickCraftConfigs 的分节格式）。
 * JSON 结构：{"blocks":{源:目标},"items":{源:目标},"states":[{source:{name,properties},target:{...}}]}。
 */
public final class FutureCompatUserMappings {
    private final Map<String, String> blocks;
    private final Map<String, String> items;
    private final Map<String, StateMapping> states; // key = StateMapping.sourceKey()

    private FutureCompatUserMappings(Map<String, String> blocks,
                                     Map<String, String> items,
                                     Map<String, StateMapping> states) {
        this.blocks = new TreeMap<>(blocks);
        this.items = new TreeMap<>(items);
        this.states = states;
    }

    public static FutureCompatUserMappings empty() {
        return new FutureCompatUserMappings(Map.of(), Map.of(), new LinkedHashMap<>());
    }

    public static FutureCompatUserMappings parse(JsonObject root) {
        Map<String, String> blocks = readIdMappings(root, "blocks");
        Map<String, String> items = readIdMappings(root, "items");
        Map<String, StateMapping> states = new LinkedHashMap<>();
        if (root.get("states") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonObject entry)) {
                    continue;
                }
                StateMapping mapping = readStateMapping(entry);
                if (mapping != null) {
                    states.put(new StateMapping(
                            mapping.sourceName(), mapping.sourceProperties(),
                            mapping.targetName(), mapping.targetProperties()).sourceKey(), mapping);
                }
            }
        }
        return new FutureCompatUserMappings(blocks, items, states);
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject blocksJson = new JsonObject();
        this.blocks.forEach(blocksJson::addProperty);
        root.add("blocks", blocksJson);
        JsonObject itemsJson = new JsonObject();
        this.items.forEach(itemsJson::addProperty);
        root.add("items", itemsJson);
        JsonArray statesJson = new JsonArray();
        for (StateMapping mapping : this.states.values()) {
            JsonObject entry = new JsonObject();
            entry.add("source", stateJson(mapping.sourceName(), mapping.sourceProperties()));
            entry.add("target", stateJson(mapping.targetName(), mapping.targetProperties()));
            statesJson.add(entry);
        }
        root.add("states", statesJson);
        return root;
    }

    private static JsonObject stateJson(String name, Map<String, String> properties) {
        JsonObject state = new JsonObject();
        state.addProperty("name", name);
        JsonObject propertiesJson = new JsonObject();
        properties.forEach(propertiesJson::addProperty);
        state.add("properties", propertiesJson);
        return state;
    }

    // ---- 编辑操作（编辑器保存用） ----

    public void putBlock(String sourceId, String targetId) {
        this.blocks.put(sourceId, targetId);
    }

    public void putItem(String sourceId, String targetId) {
        this.items.put(sourceId, targetId);
    }

    public void putState(StateMapping mapping) {
        this.states.put(mapping.sourceKey(), mapping);
    }

    public boolean removeBlock(String sourceId) {
        return this.blocks.remove(sourceId) != null;
    }

    public boolean removeItem(String sourceId) {
        return this.items.remove(sourceId) != null;
    }

    public boolean removeState(String sourceKey) {
        return this.states.remove(sourceKey) != null;
    }

    public boolean containsBlock(String sourceId) {
        return this.blocks.containsKey(sourceId);
    }

    public boolean containsItem(String sourceId) {
        return this.items.containsKey(sourceId);
    }

    public boolean containsState(String sourceKey) {
        return this.states.containsKey(sourceKey);
    }

    /** 编辑器管理视图：某条用户方块映射的当前目标；不存在返回 null。 */
    @Nullable
    public String blockTargetOf(String sourceId) {
        return this.blocks.get(sourceId);
    }

    @Nullable
    public String itemTargetOf(String sourceId) {
        return this.items.get(sourceId);
    }

    @Nullable
    public String stateTargetOf(String sourceKey) {
        StateMapping mapping = this.states.get(sourceKey);
        return mapping == null ? null : mapping.targetName();
    }

    public Map<String, String> blockEntries() {
        return Map.copyOf(this.blocks);
    }

    public Map<String, String> itemEntries() {
        return Map.copyOf(this.items);
    }

    public List<StateMapping> stateEntries() {
        return List.copyOf(this.states.values());
    }

    public FutureCompatMappings toMappings() {
        return FutureCompatMappings.of(this.blocks, this.items, List.copyOf(this.states.values()));
    }

    public boolean isEmpty() {
        return this.blocks.isEmpty() && this.items.isEmpty() && this.states.isEmpty();
    }

    // ---- JSON 辅助（与内置表同构） ----

    private static Map<String, String> readIdMappings(JsonObject root, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        if (root.get(key) instanceof JsonObject entries) {
            for (Map.Entry<String, JsonElement> mapping : entries.entrySet()) {
                if (mapping.getValue().isJsonPrimitive()) {
                    result.put(mapping.getKey(), mapping.getValue().getAsString());
                }
            }
        }
        return result;
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
        Map<String, String> properties = new LinkedHashMap<>();
        if (owner.get("properties") instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> property : object.entrySet()) {
                if (property.getValue().isJsonPrimitive()) {
                    properties.put(property.getKey(), property.getValue().getAsString());
                }
            }
        }
        return properties;
    }
}
