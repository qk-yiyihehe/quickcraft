package com.yiyihehe.quickcraft.sort;

public record QuickCrossBoxSortResult(boolean success, int emptied, int remaining, int moves, String failureKey) {
    public static final QuickCrossBoxSortResult MISMATCH = new QuickCrossBoxSortResult(
            false,
            0,
            0,
            0,
            "quickcraft.message.cross_box_sort.inventory_changed"
    );
    public static final QuickCrossBoxSortResult FAILED = new QuickCrossBoxSortResult(
            false,
            0,
            0,
            0,
            "quickcraft.message.cross_box_sort.plan_failed"
    );
}
