package com.yiyihehe.quickcraft.litematica;

final class QuickLitematicaPreviewVertexBudget {
    static final int DEFAULT_VERTEX_LIMIT = 16_000_000;
    private static final ThreadLocal<Boolean> FORCED = new ThreadLocal<>();

    private QuickLitematicaPreviewVertexBudget() {
    }

    static int vertexLimit() {
        return countLimit(DEFAULT_VERTEX_LIMIT);
    }

    static int countLimit(int defaultLimit) {
        return isForced() ? Integer.MAX_VALUE : defaultLimit;
    }

    static long nbtByteLimit(long defaultLimit) {
        return isForced() ? Long.MAX_VALUE : defaultLimit;
    }

    static int layerByteLimit() {
        // 静态层在完整方块之后分批；保留单块模型与动态缓冲的安全容量上限，强制也不能突破 int 长度。
        return isForced() ? ((Integer.MAX_VALUE - 8) / 44) * QuickLitematicaPreviewAccess.QUANTIZED_VERTEX_BYTES : DEFAULT_VERTEX_LIMIT * 32;
    }

    static boolean isForced() {
        return Boolean.TRUE.equals(FORCED.get());
    }

    static void run(boolean forced, Runnable action) {
        Boolean previous = FORCED.get();
        FORCED.set(forced);
        try {
            action.run();
        } finally {
            // 工作线程复用，强制预算不能泄漏到下一份投影。
            if (previous == null) FORCED.remove();
            else FORCED.set(previous);
        }
    }

    static void checkVertexCount(int recordedVertices) {
        checkVertexCount(recordedVertices, vertexLimit());
    }

    static void checkVertexCount(int recordedVertices, int limit) {
        if (recordedVertices >= limit) {
            QuickLitematicaPreviewLog.LOGGER.warn("网格顶点预算超限：实际={}，上限={}，强制={}，拒绝继续记录",
                    recordedVertices, limit, isForced());
            throw new QuickLitematicaPreview3D.PreviewTooLargeException(!isForced());
        }
    }

    static int growCapacity(int current, int needed) {
        int limit = layerByteLimit();
        if (needed < 0 || needed > limit) {
            throw new QuickLitematicaPreview3D.PreviewTooLargeException(!isForced());
        }
        int capacity = Math.max(1, current);
        while (capacity < needed) {
            capacity = (int) Math.min(limit, (long) capacity * 2);
        }
        return capacity;
    }
}
