package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.assertThat;

/** 用满级、互不重复的附魔费用模型验证合成树搜索，不依赖客户端或服务器。 */
class QuickCraftAnvilCombinationSearchTest {
    private record Item(Map<String, Integer> enchantments, int repairCost, boolean equipment) {}

    @Test
    void threeBooksCanBeCheaperWhenMergedBeforeApplying() {
        var materials = materials(1, 1, 1);
        var result = solve(materials, 3);
        assertThat(result.item().enchantments()).hasSize(3);
        assertThat(result.cost()).isEqualTo(6);
        assertThat(result.steps()).hasSize(3).anyMatch(step -> step.leftSlot() != 0);
        assertExecutable(materials, result);
    }

    @Test
    void sevenSwordTargetsFitWithoutSkippingMending() {
        // 击退II、耐久III、火焰附加II、锋利V、横扫III、抢夺III、经验修补I。
        var materials = materials(2, 3, 4, 5, 6, 6, 2);
        var result = solve(materials, 7);
        assertThat(result.item().enchantments()).hasSize(7);
        assertThat(result.cost()).isLessThanOrEqualTo(52);
        assertThat(result.steps()).hasSize(7).allMatch(step -> step.cost() < 40);
        assertThat(result.steps()).anyMatch(step -> step.leftSlot() != 0);
        assertExecutable(materials, result);
    }

    @Test
    void directApplicationStillWinsWhenMergingWouldCostMore() {
        var result = solve(materials(1, 1), 2);
        assertThat(result.cost()).isEqualTo(3);
        assertThat(result.steps()).allMatch(step -> step.leftSlot() == 0);
    }

    @Test
    void cannotReuseABookOrSacrificeAnotherEquipmentCandidate() {
        var materials = new ArrayList<>(materials(1));
        materials.add(new QuickCraftAnvilCombinationSearch.Material<>(8, new Item(Map.of(), 0, true), true));
        var result = solve(materials, 2);
        assertThat(result.item().enchantments()).hasSize(1);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.sourceSlot()).isIn(0, 8);
        assertExecutable(materials, result);
    }

    @Test
    void partialRoutesMaximizeProgressBeforeComparingTheirCost() {
        var materials = materials(1, 2, 3);
        var result = solve(materials, 4);
        assertThat(result.item().enchantments()).hasSize(3);
        assertExecutable(materials, result);
    }

    @Test
    void aFirstCompleteRouteDoesNotHideALaterCheaperRoute() {
        var materials = List.of(new QuickCraftAnvilCombinationSearch.Material<>(0, 0, true),
                new QuickCraftAnvilCombinationSearch.Material<>(3, 1, false),
                new QuickCraftAnvilCombinationSearch.Material<>(4, 2, false));
        var search = new QuickCraftAnvilCombinationSearch<>(materials, item -> item,
                (Integer left, Integer right) -> {
                    if (left == 0 && right == 1) return new QuickCraftAnvilCombinationSearch.Merge<>(3, 20);
                    if (left == 0 && right == 2) return new QuickCraftAnvilCombinationSearch.Merge<>(4, 1);
                    if (left == 4 && right == 1) return new QuickCraftAnvilCombinationSearch.Merge<>(3, 1);
                    return null;
                }, item -> item == 3, item -> item == 3 ? 2 : item == 4 ? 1 : 0);
        var result = finish(search);
        assertThat(result.cost()).isEqualTo(2);
        assertThat(result.steps()).hasSize(2);
    }

    @Test
    void duplicateBooksReuseSuccessfulAndFailedSimulationsWithoutReusingSlots() {
        var materials = new ArrayList<>(materials(1, 1, 1));
        Item duplicate = materials.get(1).item();
        materials.add(new QuickCraftAnvilCombinationSearch.Material<>(6, duplicate, false));
        materials.add(new QuickCraftAnvilCombinationSearch.Material<>(7, duplicate, false));
        var calls = new HashMap<List<Item>, Integer>();
        var result = solve(materials, 4, (left, right) -> {
            calls.merge(List.of(left, right), 1, Integer::sum);
            return combine(left, right);
        });
        assertThat(calls.values()).allMatch(count -> count == 1);
        assertThat(calls.get(List.of(duplicate, duplicate))).isEqualTo(1);
        assertThat(result.item().enchantments()).hasSize(3);
        assertThat(result.cost()).isEqualTo(6);
        assertExecutable(materials, result);
    }

    @Test
    void equalLowLevelBooksRemainTwoIndependentConsumableMaterials() {
        var materials = List.of(new QuickCraftAnvilCombinationSearch.Material<>(0, 0, true),
                new QuickCraftAnvilCombinationSearch.Material<>(3, 1, false),
                new QuickCraftAnvilCombinationSearch.Material<>(4, 1, false));
        var search = new QuickCraftAnvilCombinationSearch<>(materials, item -> item,
                (Integer left, Integer right) -> {
                    int level = left.equals(right) ? Math.min(2, left + 1) : Math.max(left, right);
                    return level > left ? new QuickCraftAnvilCombinationSearch.Merge<>(level, 1) : null;
                }, item -> item == 2, item -> item);
        var result = finish(search);
        assertThat(result.item()).isEqualTo(2);
        assertThat(result.cost()).isEqualTo(2);
        assertThat(result.used()).isEqualTo(7L);
        assertThat(result.steps()).hasSize(2);
        var available = new HashMap<Integer, Integer>();
        materials.forEach(material -> available.put(material.slot(), material.item()));
        for (var step : result.steps()) {
            assertThat(available.get(step.leftSlot())).isEqualTo(step.left());
            assertThat(available.remove(step.rightSlot())).isEqualTo(step.right());
            available.put(step.leftSlot(), step.output());
        }
        assertThat(available.get(result.sourceSlot())).isEqualTo(result.item());
    }

    private static List<QuickCraftAnvilCombinationSearch.Material<Item>> materials(int... costs) {
        var materials = new ArrayList<QuickCraftAnvilCombinationSearch.Material<Item>>();
        materials.add(new QuickCraftAnvilCombinationSearch.Material<>(0, new Item(Map.of(), 0, true), true));
        for (int index = 0; index < costs.length; index++) {
            materials.add(new QuickCraftAnvilCombinationSearch.Material<>(index + 3,
                    new Item(Map.of("enchantment" + index, costs[index]), 0, false), false));
        }
        return materials;
    }

    private static QuickCraftAnvilCombinationSearch.Plan<Item> solve(
            List<QuickCraftAnvilCombinationSearch.Material<Item>> materials, int goal) {
        return solve(materials, goal, QuickCraftAnvilCombinationSearchTest::combine);
    }

    private static QuickCraftAnvilCombinationSearch.Merge<Item> combine(Item left, Item right) {
        var enchantments = new HashMap<>(left.enchantments);
        enchantments.putAll(right.enchantments);
        if (enchantments.size() <= left.enchantments.size()) return null;
        int cost = left.repairCost + right.repairCost
                + right.enchantments.values().stream().mapToInt(Integer::intValue).sum();
        if (cost >= 40) return null;
        return new QuickCraftAnvilCombinationSearch.Merge<>(
                new Item(Map.copyOf(enchantments), Math.max(left.repairCost, right.repairCost) * 2 + 1, left.equipment), cost);
    }

    private static QuickCraftAnvilCombinationSearch.Plan<Item> solve(
            List<QuickCraftAnvilCombinationSearch.Material<Item>> materials, int goal,
            BiFunction<Item, Item, QuickCraftAnvilCombinationSearch.Merge<Item>> combine) {
        var search = new QuickCraftAnvilCombinationSearch<>(materials, item -> item,
                combine, item -> item.enchantments.size() >= goal, item -> item.enchantments.size());
        return finish(search);
    }

    private static <T, K> QuickCraftAnvilCombinationSearch.Plan<T> finish(QuickCraftAnvilCombinationSearch<T, K> search) {
        for (int frame = 0; !search.advance(1_000_000); frame++) assertThat(frame).isLessThan(20_000);
        assertThat(search.result()).isNotNull();
        return search.result();
    }

    private static void assertExecutable(List<QuickCraftAnvilCombinationSearch.Material<Item>> materials,
                                         QuickCraftAnvilCombinationSearch.Plan<Item> plan) {
        var available = new HashMap<Integer, Item>();
        materials.forEach(material -> available.put(material.slot(), material.item()));
        int cost = 0;
        for (var step : plan.steps()) {
            assertThat(available.get(step.leftSlot())).isEqualTo(step.left());
            assertThat(available.remove(step.rightSlot())).isEqualTo(step.right());
            available.put(step.leftSlot(), step.output());
            cost += step.cost();
        }
        assertThat(available.get(plan.sourceSlot())).isEqualTo(plan.item());
        assertThat(cost).isEqualTo(plan.cost());
    }
}
