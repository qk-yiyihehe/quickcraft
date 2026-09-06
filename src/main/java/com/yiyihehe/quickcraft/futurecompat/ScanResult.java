package com.yiyihehe.quickcraft.futurecompat;

import java.util.List;
import java.util.Map;

/** 扫描结果：三类可修复问题 + 失效映射 + 内置映射自动处理条目，均按 id 排序。 */
public record ScanResult(List<UnknownBlock> unknownBlocks,
                         List<UnknownItem> unknownItems,
                         List<StateMismatch> stateMismatches,
                         List<InvalidMapping> invalidMappings,
                         List<AutoMapped> autoMapped) {
    /** 无问题结果（非未来版本 / 无世界 / 读取失败等所有"没有可报告内容"的场合）。 */
    public static final ScanResult EMPTY = new ScanResult(List.of(), List.of(), List.of(), List.of(), List.of());

    public ScanResult {
        unknownBlocks = List.copyOf(unknownBlocks);
        unknownItems = List.copyOf(unknownItems);
        stateMismatches = List.copyOf(stateMismatches);
        invalidMappings = List.copyOf(invalidMappings);
        autoMapped = List.copyOf(autoMapped);
    }

    /** 可通过补映射修复的问题数（按钮计数口径）。 */
    public int fixableCount() {
        return this.unknownBlocks.size() + this.unknownItems.size() + this.stateMismatches.size();
    }

    public boolean hasProblems() {
        return this.fixableCount() > 0 || !this.invalidMappings.isEmpty();
    }

    public record UnknownBlock(String id, int count) {
    }

    public record UnknownItem(String id, int count) {
    }

    public record StateMismatch(String id, Map<String, String> properties, int count) {
    }

    /** 表里存在但在当前环境无法生效的映射（如目标方块/物品已不存在）。 */
    public record InvalidMapping(String kind, String sourceId, String targetId, String reason) {
    }

    /** 已被映射表自动处理、无需玩家操作的条目（编辑器中透明展示，可覆盖）。 */
    public record AutoMapped(String kind, String sourceId, String targetId) {
    }
}
