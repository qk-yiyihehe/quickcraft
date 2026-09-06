package com.yiyihehe.quickcraft.futurecompat;

import java.util.Map;

/**
 * 状态级映射 = 完整状态对 (源Name+源Properties) → (目标Name+目标Properties)；
 * "未来属性值 → 当前属性值"只是它的 UI 展示形式。改写时目标 Properties 整体替换源 Properties。
 */
public record StateMapping(String sourceName, Map<String, String> sourceProperties,
                           String targetName, Map<String, String> targetProperties) {
    public StateMapping {
        sourceProperties = Map.copyOf(sourceProperties);
        targetProperties = Map.copyOf(targetProperties);
    }

    /** 源状态对的可重复键（属性键排序），用于查找与玩家映射覆盖内置表。 */
    static String keyOf(String name, Map<String, String> properties) {
        StringBuilder key = new StringBuilder(name);
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> key.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
        return key.toString();
    }

    String sourceKey() {
        return keyOf(this.sourceName, this.sourceProperties);
    }
}
