package com.yiyihehe.quickcraft.futurecompat;

import org.jetbrains.annotations.Nullable;

/** 编辑器预填草稿的纯函数（与 UI 解耦，可离线测试）。 */
final class FutureCompatDrafts {
    private FutureCompatDrafts() {
    }

    /** 已有用户映射 → 当前目标；未映射 → 空串（绝不返回 null：null 曾导致编辑器打开即 NPE）。 */
    static String initialDraftFor(@Nullable FutureCompatEditorScreen.Row row, FutureCompatMappings mappings) {
        if (row instanceof FutureCompatEditorScreen.InvalidRow invalid) {
            return invalid.targetId();
        }
        if (row instanceof FutureCompatEditorScreen.AutoMappedRow auto) {
            return auto.targetId();
        }
        if (row instanceof FutureCompatEditorScreen.StateRow state) {
            return mappings.stateTarget(state.sourceId(), state.sourceProperties())
                    .map(StateMapping::targetName)
                    .orElse(state.sourceId());
        }
        if (row instanceof FutureCompatEditorScreen.MergedRow mergedRow) {
            return mappings.blockTarget(mergedRow.sourceId()) != null
                    ? mappings.blockTarget(mergedRow.sourceId())
                    : "";
        }
        String sourceId = switch (row) {
            case FutureCompatEditorScreen.BlockRow(String blockSourceId) -> blockSourceId;
            case FutureCompatEditorScreen.ItemRow(String itemSourceId) -> itemSourceId;
            default -> "";
        };
        String existing = row instanceof FutureCompatEditorScreen.BlockRow
                ? mappings.blockTarget(sourceId)
                : mappings.itemTarget(sourceId);
        return existing != null ? existing : "";
    }
}
