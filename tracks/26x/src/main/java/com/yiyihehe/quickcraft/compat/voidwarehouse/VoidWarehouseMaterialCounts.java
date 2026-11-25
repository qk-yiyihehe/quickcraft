package com.yiyihehe.quickcraft.compat.voidwarehouse;

final class VoidWarehouseMaterialCounts {
    private VoidWarehouseMaterialCounts() {}

    static int merge(int inventoryCount, long warehouseAmount) {
        int base = Math.max(0, inventoryCount);
        // 先限制仓库数量，避免输入接近 Long.MAX_VALUE 时相加溢出。
        return base + (int) Math.clamp(warehouseAmount, 0, Integer.MAX_VALUE - base);
    }
}
