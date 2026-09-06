package com.yiyihehe.quickcraft.futurecompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * 不可变映射表：id 级（方块/物品）+ 状态级（完整状态对）。键值统一归一化为完整 id。
 * 玩家映射经 {@link #mergedWith} 覆盖并扩展内置表。
 */
public final class FutureCompatMappings {
    public static final FutureCompatMappings EMPTY =
            new FutureCompatMappings(Map.of(), Map.of(), Map.of());

    private final Map<String, String> blockMappings;
    private final Map<String, String> itemMappings;
    private final Map<String, StateMapping> stateMappings;

    private FutureCompatMappings(Map<String, String> blockMappings,
                                 Map<String, String> itemMappings,
                                 Map<String, StateMapping> stateMappings) {
        this.blockMappings = blockMappings;
        this.itemMappings = itemMappings;
        this.stateMappings = stateMappings;
    }

    public static FutureCompatMappings of(Map<String, String> blocks,
                                          Map<String, String> items,
                                          List<StateMapping> states) {
        Map<String, String> normalizedBlocks = new HashMap<>();
        blocks.forEach((source, target) -> putIfBothValid(normalizedBlocks, source, target));
        Map<String, String> normalizedItems = new HashMap<>();
        items.forEach((source, target) -> putIfBothValid(normalizedItems, source, target));

        Map<String, StateMapping> normalizedStates = new LinkedHashMap<>();
        for (StateMapping mapping : states) {
            String sourceName = Ids.normalize(mapping.sourceName());
            String targetName = Ids.normalize(mapping.targetName());
            if (sourceName.isEmpty() || targetName.isEmpty()) {
                continue;
            }
            StateMapping normalized = new StateMapping(
                    sourceName, mapping.sourceProperties(), targetName, mapping.targetProperties());
            normalizedStates.put(normalized.sourceKey(), normalized);
        }
        return new FutureCompatMappings(
                Map.copyOf(normalizedBlocks), Map.copyOf(normalizedItems), Map.copyOf(normalizedStates));
    }

    private static void putIfBothValid(Map<String, String> target, String source, String mappedTarget) {
        String sourceId = Ids.normalize(source);
        String targetId = Ids.normalize(mappedTarget);
        if (!sourceId.isEmpty() && !targetId.isEmpty()) {
            target.put(sourceId, targetId);
        }
    }

    public boolean isEmpty() {
        return this.blockMappings.isEmpty() && this.itemMappings.isEmpty() && this.stateMappings.isEmpty();
    }

    public int blockMappingCount() {
        return this.blockMappings.size();
    }

    public int itemMappingCount() {
        return this.itemMappings.size();
    }

    public int stateMappingCount() {
        return this.stateMappings.size();
    }

    public Map<String, String> blockEntries() {
        return Map.copyOf(this.blockMappings);
    }

    public Map<String, String> itemEntries() {
        return Map.copyOf(this.itemMappings);
    }

    public List<StateMapping> stateEntries() {
        return List.copyOf(this.stateMappings.values());
    }

    @Nullable
    public String blockTarget(String sourceId) {
        return this.blockMappings.get(Ids.normalize(sourceId));
    }

    @Nullable
    public String itemTarget(String sourceId) {
        return this.itemMappings.get(Ids.normalize(sourceId));
    }

    public Optional<StateMapping> stateTarget(String id, Map<String, String> properties) {
        return Optional.ofNullable(this.stateMappings.get(StateMapping.keyOf(Ids.normalize(id), properties)));
    }

    /** 玩家映射覆盖并扩展内置表；状态级按源状态对键替换。 */
    public FutureCompatMappings mergedWith(FutureCompatMappings user) {
        if (user.isEmpty()) {
            return this;
        }
        Map<String, String> blocks = new HashMap<>(this.blockMappings);
        blocks.putAll(user.blockMappings);
        Map<String, String> items = new HashMap<>(this.itemMappings);
        items.putAll(user.itemMappings);
        Map<String, StateMapping> states = new LinkedHashMap<>(this.stateMappings);
        states.putAll(user.stateMappings);
        return new FutureCompatMappings(Map.copyOf(blocks), Map.copyOf(items), Map.copyOf(states));
    }

    /** 缓存失效指纹：内容 canonical（键排序）后 SHA-256，避免 JSON 顺序抖动导致缓存常失效。 */
    public String fingerprint() {
        if (isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder line = new StringBuilder();
            this.blockMappings.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> line.append("b|").append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
            this.itemMappings.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> line.append("i|").append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
            this.stateMappings.values().stream()
                    .forEach(mapping -> line.append("s|").append(mapping.sourceName())
                            .append('|').append(sortedProperties(mapping.sourceProperties()))
                            .append("=>").append(mapping.targetName())
                            .append('|').append(sortedProperties(mapping.targetProperties()))
                            .append('\n'));
            digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String sortedProperties(Map<String, String> properties) {
        StringBuilder builder = new StringBuilder();
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(entry.getValue()).append(','));
        return builder.toString();
    }
}
