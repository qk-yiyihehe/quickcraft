package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStacks.Box;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStacks.Stack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCrossBoxSortPlannerTest {
    private static final Stack<String> A = new Stack<>("a", 64, 64);
    private static final Stack<String> B = new Stack<>("b", 64, 64);
    private static final Comparator<Stack<String>> BY_KEY = Comparator.comparing(Stack::key);

    @Test
    void pureSortingMaximizesRetentionAcrossBoxes() {
        var first = box(0, Collections.nCopies(10, A), Collections.nCopies(8, B), List.of());
        var second = box(1, Collections.nCopies(9, A), List.of(), List.of());

        var plan = QuickCrossBoxSortPlanner.plan(List.of(first, second), List.of(2), BY_KEY, true);

        assertThat(plan.targets().get(0).slots().stream().filter(Objects::nonNull))
                .containsExactlyElementsOf(Collections.nCopies(8, B));
        assertThat(plan.targets().get(1).slots().stream().filter(Objects::nonNull))
                .containsExactlyElementsOf(Collections.nCopies(19, A));
        assertThat(plan.crossBoxMoves()).isEqualTo(10);
    }

    @Test
    void mixedSortingReservesLocalMatchesBeforeMovingBetweenBoxes() {
        var first = box(0, Collections.nCopies(26, A), List.of(B), List.of());
        var second = box(1, List.of(B), Collections.nCopies(13, A), Collections.nCopies(13, B));
        var third = box(2, List.of(A, B), List.of(), List.of());

        var plan = QuickCrossBoxSortPlanner.plan(List.of(first, second, third), List.of(), BY_KEY, false);

        assertThat(plan.targets()).containsExactly(
                box(0, Collections.nCopies(27, A), List.of(), List.of()),
                box(1, Collections.nCopies(13, A), Collections.nCopies(14, B), List.of()),
                box(2, List.of(B, B), List.of(), List.of()));
        assertThat(plan.crossBoxMoves()).isEqualTo(2);
    }

    @Test
    void independentSwapsShareOpenBoxesWithoutSharingTheirHotbarSlots() {
        var first = box(0, Collections.nCopies(25, A), Collections.nCopies(2, B), List.of());
        var second = box(1, Collections.nCopies(2, A), Collections.nCopies(25, B), List.of());
        var plan = QuickCrossBoxSortPlanner.plan(List.of(first, second), List.of(), BY_KEY, false);

        var single = QuickCrossBoxSortPlanner.scheduleSwaps(plan.swapCycles(), 1);
        var batched = QuickCrossBoxSortPlanner.scheduleSwaps(plan.swapCycles(), 2);
        assertThat(single).extracting(swap -> swap.step().slot().inventorySlot()).containsExactly(0, 1, 0, 0, 1, 0);
        assertThat(batched).extracting(swap -> swap.step().slot().inventorySlot()).containsExactly(0, 0, 1, 1, 0, 0);

        var actual = List.of(new ArrayList<>(first.slots()), new ArrayList<>(second.slots()));
        List<Stack<String>> hotbar = new ArrayList<>(Collections.nCopies(2, null));
        for (var scheduled : batched) {
            var step = scheduled.step();
            var slots = actual.get(step.slot().inventorySlot());
            int slot = step.slot().boxSlot();
            int register = scheduled.register();
            assertThat(slots.get(slot)).isEqualTo(step.slotBefore());
            assertThat(hotbar.get(register)).isEqualTo(step.registerBefore());
            slots.set(slot, step.slotAfter());
            hotbar.set(register, step.registerAfter());
        }
        assertThat(actual.get(0)).isEqualTo(plan.targets().get(0).slots());
        assertThat(actual.get(1)).isEqualTo(plan.targets().get(1).slots());
        assertThat(hotbar).containsOnlyNulls();
    }

    private static Box<String> box(int slot, List<Stack<String>> prefix, List<Stack<String>> middle,
                                   List<Stack<String>> suffix) {
        List<Stack<String>> stacks = new ArrayList<>(prefix);
        stacks.addAll(middle);
        stacks.addAll(suffix);
        stacks.addAll(Collections.nCopies(27 - stacks.size(), null));
        return new Box<>(slot, stacks);
    }
}
