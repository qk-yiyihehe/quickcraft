package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftAnvilCostModelTest {
    @Test
    void chargesTheRightEnchantmentsAndBothPenalties() {
        var model = model(new int[]{5}, new int[]{1});
        var merged = model.combine(item(0, 3, 0), item(-1, 1, 5));
        assertThat(merged.cost()).isEqualTo(9);
        assertThat(merged.item().repairCost()).isEqualTo(7);
        assertThat(merged.item().levels()).containsExactly(5);
    }

    @Test
    void respectsTheSurvivalLimitAndCreativeBookMode() {
        var limited = model(new int[]{1}, new int[]{2});
        var unlimited = new QuickCraftAnvilCostModel(new int[]{1}, new int[]{1}, new int[]{2}, List.of(new int[]{0}), true);
        assertThat(limited.combine(item(0, 31, 0), item(-1, 7, 1))).isNull();
        var merged = unlimited.combine(item(0, 31, 0), item(-1, 7, 1));
        assertThat(merged.cost()).isEqualTo(40);
        assertThat(merged.item().repairCost()).isEqualTo(63);
    }

    @Test
    void preservesPartialApplicationAndChargesConflicts() {
        var model = new QuickCraftAnvilCostModel(new int[]{5, 1}, new int[]{5, 1}, new int[]{1, 2},
                List.of(new int[]{1, 0}), false);
        var merged = model.combine(item(0, 0, 0, 0), item(-1, 0, 5, 1));
        assertThat(merged.cost()).isEqualTo(3);
        assertThat(merged.item().levels()).containsExactly(0, 1);
    }

    @Test
    void equalLevelsUpgradeButCannotExceedACustomTarget() {
        var model = new QuickCraftAnvilCostModel(new int[]{3}, new int[]{5}, new int[]{1}, List.of(new int[]{0}), false);
        assertThat(model.combine(item(0, 0, 2), item(-1, 0, 1))).isNull();
        assertThat(model.combine(item(0, 0, 2), item(-1, 0, 2)).item().levels()).containsExactly(3);
        assertThat(model.combine(item(0, 0, 3), item(-1, 0, 3))).isNull();
    }

    @Test
    void blankCustomNamesAreRemovedAndChargedOnce() {
        var model = model(new int[]{1}, new int[]{2});
        var merged = model.combine(new QuickCraftAnvilCostModel.Item(List.of(0), 0, 0, true), item(-1, 0, 1));
        assertThat(merged.cost()).isEqualTo(3);
        assertThat(merged.item().clearName()).isFalse();
    }

    @Test
    void aBackpackOfDuplicateSwordBooksStillHasASevenStepOptimum() {
        int[] targets = {2, 3, 2, 5, 3, 3, 1};
        var model = model(targets, new int[]{1, 1, 2, 1, 2, 2, 2});
        var materials = new ArrayList<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>>();
        materials.add(material(0, item(0, 0, new int[7])));
        for (int copy = 0; copy < 5; copy++) {
            for (int index = 0; index < targets.length; index++) {
                int[] levels = new int[7];
                levels[index] = targets[index];
                materials.add(material(materials.size() + 2, item(-1, 0, levels)));
            }
        }
        var result = solve(model, materials, true);
        assertThat(result.cost()).isEqualTo(49);
        assertThat(result.steps()).hasSize(7).allMatch(step -> step.cost() < 40);
        assertExecutable(model, materials, result);
    }

    @Test
    void thirtySixLowLevelCopiesAreCountedInsteadOfEnumeratingTheirSlots() {
        var model = model(new int[]{2}, new int[]{1});
        var materials = new ArrayList<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>>();
        materials.add(material(0, item(0, 0, 0)));
        for (int index = 0; index < 36; index++) materials.add(material(index + 3, item(-1, 0, 1)));
        var search = search(model, materials, true);
        // 零时间片每次仅推进一次状态机，限制的是工作量，不依赖机器计时。
        int work = 0;
        while (!search.advance(0)) assertThat(++work).isLessThan(1_000);
        assertThat(search.result().cost()).isEqualTo(4);
        assertThat(search.result().steps()).hasSize(2);
        assertExecutable(model, materials, search.result());
    }

    @Test
    void coveragePruningMatchesExactStockTrackingWithOverlappingAndLowLevelBooks() {
        var model = model(new int[]{2, 2, 1}, new int[]{1, 2, 2});
        var materials = List.of(material(0, item(0, 0, 0, 0, 0)), material(3, item(-1, 0, 2, 2, 0)),
                material(4, item(-1, 0, 2, 0, 1)), material(5, item(-1, 0, 0, 1, 0)),
                material(6, item(-1, 0, 0, 1, 0)), material(7, item(-1, 0, 0, 0, 1)));
        var exact = solve(model, materials, false);
        var optimized = solve(model, materials, true);
        assertThat(optimized.cost()).isEqualTo(exact.cost());
        assertThat(optimized.item().levels()).isEqualTo(exact.item().levels());
        assertExecutable(model, materials, optimized);
    }

    @Test
    void missingBooksUseAnAttainableProgressBound() {
        var model = model(new int[]{5, 1}, new int[]{1, 2});
        var materials = List.of(material(0, item(0, 0, 0, 0)), material(3, item(-1, 0, 2, 0)),
                material(4, item(-1, 0, 2, 0)));
        assertThat(model.reachableProgress(materials)).isEqualTo(3);
        var result = solve(model, materials, true);
        assertThat(result.item().levels()).containsExactly(3, 0);
        assertThat(result.cost()).isEqualTo(6);
        assertExecutable(model, materials, result);
    }

    @Test
    void equipmentCandidatesKeepTheirExistingLevelsAndPenalties() {
        var model = model(new int[]{3}, new int[]{1});
        var materials = List.of(material(0, item(0, 15, 2)), material(8, item(0, 0, 0)),
                material(3, item(-1, 0, 3)));
        var result = solve(model, materials, true);
        assertThat(result.sourceSlot()).isEqualTo(8);
        assertThat(result.cost()).isEqualTo(3);
        assertExecutable(model, materials, result);
    }

    private static QuickCraftAnvilCostModel model(int[] targets, int[] costs) {
        return new QuickCraftAnvilCostModel(targets, targets, costs, List.of(new int[targets.length]), false);
    }

    private static QuickCraftAnvilCostModel.Item item(int profile, int penalty, int... levels) {
        return new QuickCraftAnvilCostModel.Item(Arrays.stream(levels).boxed().toList(), penalty, profile, false);
    }

    private static QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item> material(
            int slot, QuickCraftAnvilCostModel.Item item) {
        return new QuickCraftAnvilCombinationSearch.Material<>(slot, item, item.equipment());
    }

    private static QuickCraftAnvilCombinationSearch<QuickCraftAnvilCostModel.Item, QuickCraftAnvilCostModel.Item> search(
            QuickCraftAnvilCostModel model,
            List<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>> materials, boolean optimized) {
        int reachable = model.reachableProgress(materials);
        return optimized ? new QuickCraftAnvilCombinationSearch<>(materials, item -> item, model::combine,
                item -> model.progress(item) >= reachable, model::progress, QuickCraftAnvilCostModel.Item::dominanceKey,
                (left, right) -> left.repairCost() <= right.repairCost(), model::redundantStock)
                : new QuickCraftAnvilCombinationSearch<>(materials, item -> item, model::combine,
                item -> model.progress(item) >= reachable, model::progress);
    }

    private static QuickCraftAnvilCombinationSearch.Plan<QuickCraftAnvilCostModel.Item> solve(
            QuickCraftAnvilCostModel model,
            List<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>> materials, boolean optimized) {
        var search = search(model, materials, optimized);
        int frames = 0;
        while (!search.advance(1_000_000)) assertThat(++frames).isLessThan(20_000);
        assertThat(search.result()).isNotNull();
        return search.result();
    }

    private static void assertExecutable(QuickCraftAnvilCostModel model,
            List<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>> materials,
            QuickCraftAnvilCombinationSearch.Plan<QuickCraftAnvilCostModel.Item> plan) {
        var available = new HashMap<Integer, QuickCraftAnvilCostModel.Item>();
        materials.forEach(material -> available.put(material.slot(), material.item()));
        int cost = 0;
        for (var step : plan.steps()) {
            assertThat(available.get(step.leftSlot())).isEqualTo(step.left());
            assertThat(available.remove(step.rightSlot())).isEqualTo(step.right());
            var merged = model.combine(step.left(), step.right());
            assertThat(merged).isNotNull();
            assertThat(merged.cost()).isEqualTo(step.cost());
            assertThat(merged.item()).isEqualTo(step.output());
            available.put(step.leftSlot(), step.output());
            cost += step.cost();
        }
        assertThat(available.get(plan.sourceSlot())).isEqualTo(plan.item());
        assertThat(cost).isEqualTo(plan.cost());
    }
}
