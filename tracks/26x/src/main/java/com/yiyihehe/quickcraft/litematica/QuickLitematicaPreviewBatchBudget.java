package com.yiyihehe.quickcraft.litematica;

final class QuickLitematicaPreviewBatchBudget {
    // 留出绘制/输入时间；单个原生模型调用无法抢占，目标耗时不是硬超时。
    static final long TARGET_NANOS = 8_000_000L;
    static final int INITIAL_SIZE = 256;
    static final int MIN_SIZE = 16;
    // 控制普通箱子的单批暂存大小，避免原生大缓冲反复扩容复制。
    static final int MAX_SIZE = 4096;

    private QuickLitematicaPreviewBatchBudget() {}

    static int nextSize(int previous, long elapsedNanos) {
        long proposed = (long) previous * TARGET_NANOS / Math.max(1L, elapsedNanos);
        // 首批 JIT/上传抖动不能使批次大小立即跨越几个数量级。
        long bounded = Math.max(previous / 2L, Math.min(previous * 2L, proposed));
        return (int) Math.max(MIN_SIZE, Math.min(MAX_SIZE, bounded));
    }
}
