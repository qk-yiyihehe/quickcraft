package com.yiyihehe.quickcraft.compat.voidwarehouse;

/**
 * 「材料来源」那两个数字的纯计算：{@link #merge} 算出「已有数量」。
 *
 * <h2>为什么单独一个类</h2>
 * <p>真正接进材料列表的只有 {@code getCountAvailable} 一处，其余都是边界处理：
 * 负数、溢出、仓库没数据。把这些挪到没有 Fabric 依赖的纯静态方法里，
 * 注入点就退化成「拿返回值、算一下、塞回去」三行胶水，而会出错的边界都能被直接验证。</p>
 *
 * <h2>口径：背包 + 虚空仓库</h2>
 * <p>与 {@code litematic-printer} 保持同一口径，理由也相同：仓库是主要库存，
 * 而玩家手上永远有一些「刚从仓库取出来、准备放进投影」的材料。只看仓库会让这类材料
 * 凭空显示成 0，只看背包又完全反映不出仓库有多少 —— 两个都算才回答得了「我还差多少」。</p>
 *
 * <h2>作用范围</h2>
 * <p>只影响「已有数量」。{@code MaterialListEntry#countMissing} 是 {@code final}，
 * 且本功能是<b>只读</b>的（不会真的把材料取到手上），所以「缺失」一列仍按实际手上的缺口计算。</p>
 */
public final class VoidWarehouseMaterialCounts {

    private VoidWarehouseMaterialCounts() {
    }

    /**
     * 把仓库数量并入「已有数量」。
     *
     * @param inventoryCount  原版算出来的已有数量（玩家背包，含嵌套潜影盒）
     * @param warehouseAmount 虚空仓库里的数量；没有这条数据时传 0
     * @return 相加后的数量，<b>饱和</b>在 {@link Integer#MAX_VALUE}
     */
    public static int merge(int inventoryCount, long warehouseAmount) {
        // 界面上这个数只被当「够不够」用，负数没有任何意义。原版不会给负数，
        // 但仓库那边是 long、还来自网络，多一道夹紧不亏。
        long base = Math.max(0, inventoryCount);
        if (warehouseAmount <= 0L) {
            return (int) Math.min(base, Integer.MAX_VALUE);
        }
        // 仓库可能已经是 Long.MAX_VALUE，必须先判断饱和，避免相加后 long 也溢出。
        if (warehouseAmount >= (long) Integer.MAX_VALUE - base) {
            return Integer.MAX_VALUE;
        }
        return (int) (base + warehouseAmount);
    }
}
