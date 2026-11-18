package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 跨盒合并、按类型分配和交换环规划，沿用 Shulker Box Sort 算法。 */
public final class QuickCrossBoxSortPlanner {
    private QuickCrossBoxSortPlanner() {}

    public static <K> Plan<K> plan(List<QuickCrossBoxSortStacks.Box<K>> boxes, List<Integer> spareSlots,
    Comparator<QuickCrossBoxSortStacks.Stack<K>> comparator, boolean onePerBox) {
        if (boxes.isEmpty()) { return new Plan<>(List.of(), List.of(), List.of(), 0, 0, 0); }
        var merged = mergeToCanonicalStacks(boxes, 27);
        List<QuickCrossBoxSortStacks.Box<K>> extended = new ArrayList<>(merged.boxes());
        if (onePerBox) {
            for (int slot : spareSlots) {
                extended.add(new QuickCrossBoxSortStacks.Box<>(slot, new ArrayList<>(Collections.nCopies(27, null))));
            }
        }
        var targets = layout(extended, comparator, onePerBox);
        var cycles = buildSwapCycles(extended, targets, 27);
        int emptied = 0;
        int remaining = 0;
        for (int i = 0; i < extended.size(); i++) {
            boolean wasEmpty = i >= boxes.size() || boxes.get(i).slots().stream().allMatch(Objects::isNull);
            boolean nowEmpty = targets.get(i).slots().stream().allMatch(Objects::isNull);
            if (!wasEmpty && nowEmpty) { emptied++; }
            if (!nowEmpty) { remaining++; }
        }
        int moves = (int) merged.merges().stream().filter(m -> m.sourceInventorySlot() != m.targetInventorySlot()).count();
        for (var cycle : cycles) {
            SlotRef source = null;
            for (var step : cycle.steps()) {
                if (step.registerBefore() != null && source != null && !sameBox(source, step.slot())) { moves++; }
                source = step.slotBefore() == null ? null : step.slot();
            }
        }
        return new Plan<>(merged.merges(), targets, cycles, emptied, remaining, moves);
    }

    private static <K> MergeResult<K> mergeToCanonicalStacks(List<QuickCrossBoxSortStacks.Box<K>> input, int boxSize) {
        List<MutableBox<K>> boxes = mutableCopy(input);
        List<Position> positions = allPositions(boxes.size(), boxSize);
        List<QuickCrossBoxSortStacks.Transfer<K>> merges = new ArrayList<>();
        LinkedHashMap<K, List<Position>> byKey = new LinkedHashMap<>();

        for(Position position : positions) {
            MutableStack<K> stack = (boxes.get(position.box())).slots.get(position.slot());
            if (stack != null) {
                (byKey.computeIfAbsent(stack.key, (ignored) -> new ArrayList<>())).add(position);
            }
        }

        for(List<Position> keyPositions : byKey.values()) {
            for(int targetIndex = 0; targetIndex < keyPositions.size(); ++targetIndex) {
                Position targetPosition = keyPositions.get(targetIndex);
                MutableStack<K> target = (boxes.get(targetPosition.box())).slots.get(targetPosition.slot());
                if (target != null) {
                    for(int sourceIndex = keyPositions.size() - 1; sourceIndex > targetIndex && target.count < target.maxCount; --sourceIndex) {
                        Position sourcePosition = keyPositions.get(sourceIndex);
                        MutableStack<K> source = (boxes.get(sourcePosition.box())).slots.get(sourcePosition.slot());
                        if (source != null) {
                            int amount = Math.min(target.maxCount - target.count, source.count);
                            merges.add(new QuickCrossBoxSortStacks.Transfer<>((boxes.get(sourcePosition.box())).inventorySlot, sourcePosition.slot(), (boxes.get(targetPosition.box())).inventorySlot, targetPosition.slot(), source.key, amount));
                            target.count += amount;
                            source.count -= amount;
                            if (source.count == 0) {
                                (boxes.get(sourcePosition.box())).slots.set(sourcePosition.slot(), null);
                            }
                        }
                    }
                }
            }

        }

        return new MergeResult<K>(List.copyOf(merges), immutableCopy(boxes));
    }

    private static <K> List<SwapCycle<K>> buildSwapCycles(List<QuickCrossBoxSortStacks.Box<K>> current, List<QuickCrossBoxSortStacks.Box<K>> targets, int boxSize) {
        List<SlotRef> refs = new ArrayList<>();
        List<QuickCrossBoxSortStacks.Stack<K>> source = new ArrayList<>();
        List<QuickCrossBoxSortStacks.Stack<K>> target = new ArrayList<>();

        for(int box = 0; box < current.size(); ++box) {
            for(int slot = 0; slot < boxSize; ++slot) {
                refs.add(new SlotRef((current.get(box)).inventorySlot(), slot));
                source.add((current.get(box)).slots().get(slot));
                target.add((targets.get(box)).slots().get(slot));
            }
        }

        int size = refs.size();
        int[] destination = new int[size];
        Arrays.fill(destination, -1);
        boolean[] targetUsed = new boolean[size];

        for(int i = 0; i < size; ++i) {
            if (sameStack(source.get(i), target.get(i))) {
                destination[i] = i;
                targetUsed[i] = true;
            }
        }

        for(int sourceIndex = 0; sourceIndex < size; ++sourceIndex) {
            if (destination[sourceIndex] < 0) {
                int best = -1;

                for(int targetIndex = 0; targetIndex < size; ++targetIndex) {
                    if (!targetUsed[targetIndex] && sameStack(source.get(sourceIndex), target.get(targetIndex)) && (best < 0 || sameBox(refs.get(sourceIndex), refs.get(targetIndex)) && !sameBox(refs.get(sourceIndex), refs.get(best)))) {
                        best = targetIndex;
                    }
                }

                if (best < 0) {
                    throw new IllegalStateException("Source and target stack multisets differ");
                }

                destination[sourceIndex] = best;
                targetUsed[best] = true;
            }
        }

        boolean[] visited = new boolean[size];
        List<SwapCycle<K>> cycles = new ArrayList<>();

        for(int seed = 0; seed < size; ++seed) {
            if (!visited[seed] && destination[seed] != seed) {
                List<Integer> cycle = new ArrayList<>();
                int cursor = seed;

                do {
                    cycle.add(cursor);
                    visited[cursor] = true;
                    cursor = destination[cursor];
                } while(cursor != seed);

                int emptyIndex = -1;

                for(int i = 0; i < cycle.size(); ++i) {
                    if (source.get(cycle.get(i)) == null) {
                        emptyIndex = i;
                        break;
                    }
                }

                List<Integer> swapPositions = new ArrayList<>();
                if (emptyIndex >= 0) {
                    for(int offset = 1; offset < cycle.size(); ++offset) {
                        swapPositions.add(cycle.get((emptyIndex + offset) % cycle.size()));
                    }

                    swapPositions.add(cycle.get(emptyIndex));
                } else {
                    swapPositions.addAll(cycle);
                    swapPositions.add(cycle.getFirst());
                }

                QuickCrossBoxSortStacks.Stack<K> register = null;
                List<QuickCrossBoxSortStacks.Stack<K>> simulation = new ArrayList<>(source);
                List<SwapStep<K>> steps = new ArrayList<>();

                for(int position : swapPositions) {
                    QuickCrossBoxSortStacks.Stack<K> slotBefore = simulation.get(position);
                    QuickCrossBoxSortStacks.Stack<K> registerBefore = register;
                    simulation.set(position, register);
                    register = slotBefore;
                    steps.add(new SwapStep<>(refs.get(position), slotBefore, registerBefore, simulation.get(position), slotBefore));
                }

                if (register != null) {
                    throw new IllegalStateException("Cycle did not clear the swap register");
                }

                cycles.add(new SwapCycle<>(steps));
            }
        }

        return cycles;
    }

    private static boolean sameBox(SlotRef left, SlotRef right) {
        return left.inventorySlot() == right.inventorySlot();
    }

    private static <K> boolean sameStack(QuickCrossBoxSortStacks.Stack<K> left, QuickCrossBoxSortStacks.Stack<K> right) {
        return left == right || left != null && right != null && left.count() == right.count() && left.maxCount() == right.maxCount() && Objects.equals(left.key(), right.key());
    }

    private static List<Position> allPositions(int boxCount, int boxSize) {
        List<Position> result = new ArrayList<>(boxCount * boxSize);

        for(int box = 0; box < boxCount; ++box) {
            for(int slot = 0; slot < boxSize; ++slot) {
                result.add(new Position(box, slot));
            }
        }

        return result;
    }

    private static <K> List<MutableBox<K>> mutableCopy(List<QuickCrossBoxSortStacks.Box<K>> boxes) {
        List<MutableBox<K>> result = new ArrayList<>();

        for(QuickCrossBoxSortStacks.Box<K> box : boxes) {
            result.add(new MutableBox<>(box));
        }

        return result;
    }

    private static <K> List<QuickCrossBoxSortStacks.Box<K>> immutableCopy(List<MutableBox<K>> boxes) {
        List<QuickCrossBoxSortStacks.Box<K>> result = new ArrayList<>();

        for(MutableBox<K> box : boxes) {
            List<QuickCrossBoxSortStacks.Stack<K>> slots = new ArrayList<>();

            for(MutableStack<K> stack : box.slots) {
                slots.add(stack == null ? null : new QuickCrossBoxSortStacks.Stack<>(stack.key, stack.count, stack.maxCount));
            }

            result.add(new QuickCrossBoxSortStacks.Box<>(box.inventorySlot, slots));
        }

        return List.copyOf(result);
    }

    private static <K> List<QuickCrossBoxSortStacks.Box<K>> layout(List<QuickCrossBoxSortStacks.Box<K>> boxes,
    Comparator<QuickCrossBoxSortStacks.Stack<K>> comparator, boolean onePerBox) {
        Map<K, List<QuickCrossBoxSortStacks.Stack<K>>> byKey = new LinkedHashMap<>();
        for (QuickCrossBoxSortStacks.Box<K> box : boxes) {
            for (QuickCrossBoxSortStacks.Stack<K> stack : box.slots()) {
                if (stack != null) {
                    byKey.computeIfAbsent(stack.key(), key -> new ArrayList<>()).add(stack);
                }
            }
        }
        if (byKey.isEmpty()) {
            throw new IllegalStateException("No complete box layout");
        }

        List<List<QuickCrossBoxSortStacks.Stack<K>>> groups = new ArrayList<>(byKey.values());
        groups.forEach(group -> group.sort(comparator));
        groups.sort((left, right) -> comparator.compare(left.get(0), right.get(0)));

        int needed = 0;
        for (List<QuickCrossBoxSortStacks.Stack<K>> group : groups) {
            needed += (group.size() + 27 - 1) / 27;
        }

        List<List<QuickCrossBoxSortStacks.Stack<K>>> contents = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            contents.add(new ArrayList<>(Collections.nCopies(27, null)));
        }

        // 纯模式优先使用原本存放该类型最多的盒子，减少跨盒搬运。
        int[][] affinity = new int[groups.size()][boxes.size()];
        for (int g = 0; g < groups.size(); g++) {
            K key = groups.get(g).get(0).key();
            for (int b = 0; b < boxes.size(); b++) {
                affinity[g][b] = countOf(boxes.get(b), key);
            }
        }

        if (onePerBox && needed <= boxes.size()) {
            if (!assignExclusive(groups, contents, affinity, boxes.size())) {
                throw new IllegalStateException("No complete box layout");
            }
        } else {
            assignGrouped(groups, contents, boxes.size());
        }

        List<QuickCrossBoxSortStacks.Box<K>> targets = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            targets.add(new QuickCrossBoxSortStacks.Box<>(boxes.get(i).inventorySlot(), contents.get(i)));
        }
        return targets;
    }

    private static <K> boolean assignExclusive(List<List<QuickCrossBoxSortStacks.Stack<K>>> groups,
    List<List<QuickCrossBoxSortStacks.Stack<K>>> contents,
    int[][] affinity, int boxCount) {
        boolean[] used = new boolean[boxCount];
        for (int g = 0; g < groups.size(); g++) {
            List<QuickCrossBoxSortStacks.Stack<K>> group = groups.get(g);
            int chunks = (group.size() + 27 - 1) / 27;
            List<Integer> free = new ArrayList<>();
            for (int b = 0; b < boxCount; b++) {
                if (!used[b]) {
                    free.add(b);
                }
            }
            if (free.size() < chunks) {
                return false;
            }
            final int index = g;
            free.sort((left, right) -> {
                int cmp = Integer.compare(affinity[index][right], affinity[index][left]);
                return cmp != 0 ? cmp : Integer.compare(left, right);
            });
            for (int chunk = 0; chunk < chunks; chunk++) {
                int box = free.get(chunk);
                used[box] = true;
                for (int s = 0; s < 27; s++) {
                    int at = chunk * 27 + s;
                    contents.get(box).set(s, at < group.size() ? group.get(at) : null);
                }
            }
        }
        return true;
    }

    private static <K> void assignGrouped(List<List<QuickCrossBoxSortStacks.Stack<K>>> groups,
    List<List<QuickCrossBoxSortStacks.Stack<K>>> contents,
    int boxCount) {
        int box = 0;
        int slot = 0;
        for (List<QuickCrossBoxSortStacks.Stack<K>> group : groups) {
            for (QuickCrossBoxSortStacks.Stack<K> stack : group) {
                while (box < boxCount && slot >= 27) {
                    box++;
                    slot = 0;
                }
                if (box >= boxCount) {
                    throw new IllegalStateException("Insufficient box capacity");
                }
                contents.get(box).set(slot++, stack);
            }
        }
    }

    private static <K> int countOf(QuickCrossBoxSortStacks.Box<K> box, K key) {
        int count = 0;
        for (QuickCrossBoxSortStacks.Stack<K> stack : box.slots()) {
            if (stack != null && key.equals(stack.key())) {
                count++;
            }
        }
        return count;
    }

    public record SlotRef(int inventorySlot, int boxSlot) {}
    public record SwapStep<K>(SlotRef slot, QuickCrossBoxSortStacks.Stack<K> slotBefore, QuickCrossBoxSortStacks.Stack<K> registerBefore,
    QuickCrossBoxSortStacks.Stack<K> slotAfter, QuickCrossBoxSortStacks.Stack<K> registerAfter) {}
    public record SwapCycle<K>(List<SwapStep<K>> steps) {
        public SwapCycle { steps = List.copyOf(steps); }
    }
    public record Plan<K>(List<QuickCrossBoxSortStacks.Transfer<K>> merges, List<QuickCrossBoxSortStacks.Box<K>> targets, List<SwapCycle<K>> swapCycles,
    int emptiedBoxes, int remainingNonEmptyBoxes, int crossBoxMoves) {}
    private record Position(int box, int slot) {}
    private record MergeResult<K>(List<QuickCrossBoxSortStacks.Transfer<K>> merges, List<QuickCrossBoxSortStacks.Box<K>> boxes) {}
    private static final class MutableBox<K> {
        final int inventorySlot;
        final List<MutableStack<K>> slots = new ArrayList<>();
        MutableBox(QuickCrossBoxSortStacks.Box<K> box) {
            inventorySlot = box.inventorySlot();
            for (var stack : box.slots()) { slots.add(stack == null ? null : new MutableStack<>(stack)); }
        }
    }
    private static final class MutableStack<K> {
        final K key;
        int count;
        final int maxCount;
        MutableStack(QuickCrossBoxSortStacks.Stack<K> stack) {
            key = stack.key(); count = stack.count(); maxCount = stack.maxCount();
        }
    }
}
