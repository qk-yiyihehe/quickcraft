package com.yiyihehe.quickcraft.crafting;

import java.util.ArrayList;
import java.util.List;

/** 原版“右侧附魔书”分支的纯数值模型，不创建容器、物品副本或访问游戏状态。 */
public final class QuickCraftAnvilCostModel {
    public record Item(List<Integer> levels, int repairCost, int equipmentProfile, boolean clearName) {
        public Item { levels = List.copyOf(levels); }
        public boolean equipment() { return equipmentProfile >= 0; }
        public Item dominanceKey() { return new Item(levels, 0, equipmentProfile, clearName); }
    }

    private final int[] targets;
    private final int[] maximums;
    private final int[] bookCosts;
    private final List<int[]> conflicts;
    private final boolean unlimited;

    public QuickCraftAnvilCostModel(int[] targets, int[] maximums, int[] bookCosts,
                                   List<int[]> conflicts, boolean unlimited) {
        this.targets = targets.clone();
        this.maximums = maximums.clone();
        this.bookCosts = bookCosts.clone();
        this.conflicts = conflicts.stream().map(int[]::clone).toList();
        this.unlimited = unlimited;
    }

    public QuickCraftAnvilCombinationSearch.Merge<Item> combine(Item left, Item right) {
        if (right.equipment()) return null;
        var levels = new ArrayList<>(left.levels);
        int price = left.clearName ? 1 : 0;
        boolean improved = false;
        for (int index = 0; index < targets.length; index++) {
            int added = right.levels.get(index);
            if (added == 0) continue;
            int incompatible = left.equipment() ? conflicts.get(left.equipmentProfile)[index] : 0;
            if (incompatible != 0) {
                price += incompatible;
                continue;
            }
            int current = left.levels.get(index);
            int level = Math.min(maximums[index], current == added ? added + 1 : Math.max(current, added));
            if (level < current || level > Math.max(targets[index], current)) return null;
            levels.set(index, level);
            price += bookCosts[index] * level;
            improved |= Math.min(level, targets[index]) > Math.min(current, targets[index]);
        }
        if (!improved || price <= 0) return null;
        int cost = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) left.repairCost + right.repairCost + price));
        if (cost <= 0 || (!unlimited && cost >= 40)) return null;
        int penalty = (int) Math.min(Integer.MAX_VALUE, Math.max(left.repairCost, right.repairCost) * 2L + 1L);
        return new QuickCraftAnvilCombinationSearch.Merge<>(new Item(levels, penalty, left.equipmentProfile, false), cost);
    }

    public boolean redundantStock(Item item) {
        if (item.equipment()) return true;
        if (item.clearName) return false;
        // 满级书的全部贡献已在结果中；重复消耗它不能提升目标，可删去重复叶子且不增加费用。
        // 低等级书仍严格比较数量，不能把“合并两本升级”误当成重复材料。
        for (int index = 0; index < targets.length; index++) {
            int level = item.levels.get(index);
            if (level != 0 && (level != maximums[index] || level != targets[index])) return false;
        }
        return true;
    }

    public int reachableProgress(List<QuickCraftAnvilCombinationSearch.Material<Item>> materials) {
        int[][] books = new int[targets.length][];
        for (int index = 0; index < targets.length; index++) books[index] = new int[targets[index] + 1];
        for (var material : materials) {
            if (material.equipment()) continue;
            for (int index = 0; index < targets.length; index++) {
                int level = Math.min(targets[index], material.item().levels.get(index));
                if (level > 0) books[index][level]++;
            }
        }
        int best = 0;
        for (var material : materials) {
            if (!material.equipment()) continue;
            Item equipment = material.item();
            int total = 0;
            for (int index = 0; index < targets.length; index++) {
                int current = Math.min(targets[index], equipment.levels.get(index));
                if (conflicts.get(equipment.equipmentProfile)[index] != 0) {
                    total += current;
                    continue;
                }
                int[] counts = books[index].clone();
                if (current > 0) counts[current]++;
                // 同级两本才可进一级；忽略费用与多附魔耦合，得到不会低估的进度上界。
                for (int level = 1; level < targets[index]; level++) counts[level + 1] += counts[level] / 2;
                int highest = targets[index];
                while (highest > 0 && counts[highest] == 0) highest--;
                total += highest;
            }
            best = Math.max(best, total);
        }
        return best;
    }

    public int progress(Item item) {
        int total = 0;
        for (int index = 0; index < targets.length; index++) total += Math.min(targets[index], item.levels.get(index));
        return total;
    }
}
