package com.yiyihehe.quickcraft.sort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
            if (Objects.equals(source.get(i), target.get(i))) {
                destination[i] = i;
                targetUsed[i] = true;
            }
        }

        // Reserve local matches in every box before an earlier box can claim them.
        for (int start = 0; start < size; start += boxSize) {
            matchSlots(source, target, destination, targetUsed, start, start + boxSize);
        }
        matchSlots(source, target, destination, targetUsed, 0, size);
        for (int slot : destination) {
            if (slot < 0) {
                throw new IllegalStateException("Source and target stack multisets differ");
            }
        }

        boolean[] visited = new boolean[size];
        List<SwapCycle<K>> cycles = new ArrayList<>();
        List<QuickCrossBoxSortStacks.Stack<K>> simulation = new ArrayList<>(source);

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
                List<SwapStep<K>> steps = new ArrayList<>();

                for(int position : swapPositions) {
                    QuickCrossBoxSortStacks.Stack<K> slotBefore = simulation.get(position);
                    if (Objects.equals(slotBefore, register)) {
                        continue;
                    }
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

    private static <K> void matchSlots(List<QuickCrossBoxSortStacks.Stack<K>> source,
                                       List<QuickCrossBoxSortStacks.Stack<K>> target,
                                       int[] destination, boolean[] targetUsed, int start, int end) {
        Map<QuickCrossBoxSortStacks.Stack<K>, ArrayDeque<Integer>> available = new HashMap<>();
        for (int i = start; i < end; i++) {
            if (!targetUsed[i]) {
                available.computeIfAbsent(target.get(i), ignored -> new ArrayDeque<>()).addLast(i);
            }
        }
        for (int i = start; i < end; i++) {
            if (destination[i] < 0) {
                var matches = available.get(source.get(i));
                if (matches != null && !matches.isEmpty()) {
                    int slot = matches.removeFirst();
                    destination[i] = slot;
                    targetUsed[slot] = true;
                }
            }
        }
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

        if (onePerBox && needed <= boxes.size()) {
            assignExclusive(groups, contents, boxes);
        } else {
            assignGrouped(groups, contents, boxes.size());
        }

        List<QuickCrossBoxSortStacks.Box<K>> targets = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            targets.add(new QuickCrossBoxSortStacks.Box<>(boxes.get(i).inventorySlot(), contents.get(i)));
        }
        return targets;
    }

    private static <K> void assignExclusive(List<List<QuickCrossBoxSortStacks.Stack<K>>> groups,
                                             List<List<QuickCrossBoxSortStacks.Stack<K>>> contents,
                                             List<QuickCrossBoxSortStacks.Box<K>> boxes) {
        List<List<QuickCrossBoxSortStacks.Stack<K>>> chunks = new ArrayList<>();
        for (var group : groups) {
            for (int start = 0; start < group.size(); start += 27) {
                chunks.add(group.subList(start, Math.min(start + 27, group.size())));
            }
        }
        var boxCounts = boxes.stream().map(box -> stackCounts(box.slots())).toList();
        int[][] retained = new int[chunks.size()][boxes.size()];
        for (int chunk = 0; chunk < chunks.size(); chunk++) {
            var required = stackCounts(chunks.get(chunk));
            for (int box = 0; box < boxes.size(); box++) {
                var available = boxCounts.get(box);
                int overlap = 0;
                for (var stack : required.entrySet()) {
                    overlap += Math.min(stack.getValue(), available.getOrDefault(stack.getKey(), 0));
                }
                retained[chunk][box] = overlap;
            }
        }
        int[] assignedBoxes = maximumRetentionAssignment(retained);
        for (int chunk = 0; chunk < chunks.size(); chunk++) {
            Collections.copy(contents.get(assignedBoxes[chunk]), chunks.get(chunk));
        }
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

    private static <K> Map<QuickCrossBoxSortStacks.Stack<K>, Integer> stackCounts(
            List<QuickCrossBoxSortStacks.Stack<K>> stacks) {
        Map<QuickCrossBoxSortStacks.Stack<K>, Integer> counts = new HashMap<>();
        for (var stack : stacks) {
            if (stack != null) {
                counts.merge(stack, 1, Integer::sum);
            }
        }
        return counts;
    }

    // Rectangular Hungarian algorithm. Each chunk needs one distinct box; spare boxes may stay unmatched.
    private static int[] maximumRetentionAssignment(int[][] retained) {
        int chunkCount = retained.length;
        int boxCount = retained[0].length;
        int[] chunkPotential = new int[chunkCount + 1];
        int[] boxPotential = new int[boxCount + 1];
        int[] chunkByBox = new int[boxCount + 1];
        int[] previousBox = new int[boxCount + 1];

        for (int chunk = 1; chunk <= chunkCount; chunk++) {
            // Column zero is a temporary root for the alternating path.
            chunkByBox[0] = chunk;
            int box = 0;
            int[] slack = new int[boxCount + 1];
            Arrays.fill(slack, Integer.MAX_VALUE);
            boolean[] visited = new boolean[boxCount + 1];
            do {
                visited[box] = true;
                int assignedChunk = chunkByBox[box];
                int delta = Integer.MAX_VALUE;
                int nextBox = 0;
                for (int candidate = 1; candidate <= boxCount; candidate++) {
                    if (visited[candidate]) {
                        continue;
                    }
                    int cost = -retained[assignedChunk - 1][candidate - 1]
                            - chunkPotential[assignedChunk] - boxPotential[candidate];
                    if (cost < slack[candidate]) {
                        slack[candidate] = cost;
                        previousBox[candidate] = box;
                    }
                    if (slack[candidate] < delta) {
                        delta = slack[candidate];
                        nextBox = candidate;
                    }
                }
                for (int candidate = 0; candidate <= boxCount; candidate++) {
                    if (visited[candidate]) {
                        chunkPotential[chunkByBox[candidate]] += delta;
                        boxPotential[candidate] -= delta;
                    } else {
                        slack[candidate] -= delta;
                    }
                }
                box = nextBox;
            } while (chunkByBox[box] != 0);

            while (box != 0) {
                int previous = previousBox[box];
                chunkByBox[box] = chunkByBox[previous];
                box = previous;
            }
        }

        int[] assignedBoxes = new int[chunkCount];
        for (int box = 1; box <= boxCount; box++) {
            if (chunkByBox[box] != 0) {
                assignedBoxes[chunkByBox[box] - 1] = box - 1;
            }
        }
        return assignedBoxes;
    }

    public record SlotRef(int inventorySlot, int boxSlot) {}
    public record SwapStep<K>(SlotRef slot, QuickCrossBoxSortStacks.Stack<K> slotBefore, QuickCrossBoxSortStacks.Stack<K> registerBefore,
    QuickCrossBoxSortStacks.Stack<K> slotAfter, QuickCrossBoxSortStacks.Stack<K> registerAfter) {}
    public record SwapCycle<K>(List<SwapStep<K>> steps) {
        public SwapCycle { steps = List.copyOf(steps); }
    }

    static <K> List<ScheduledSwap<K>> scheduleSwaps(List<SwapCycle<K>> cycles, int registerCount) {
        List<ScheduledSwap<K>> original = new ArrayList<>();
        for (var cycle : cycles) {
            for (var step : cycle.steps()) {
                original.add(new ScheduledSwap<>(0, step));
            }
        }
        if (cycles.size() < 2) {
            return original;
        }

        // The cycles touch disjoint slots. Advance each using its own empty hotbar slot.
        List<SwapCycle<K>> pending = new ArrayList<>(cycles);
        List<ArrayDeque<SwapStep<K>>> registers = new ArrayList<>(Collections.nCopies(registerCount, null));
        List<ScheduledSwap<K>> grouped = new ArrayList<>(original.size());
        int openBox = -1;
        while (grouped.size() < original.size()) {
            int firstReady = -1;
            int chosen = -1;
            for (int register = 0; register < registers.size(); register++) {
                var ready = registers.get(register);
                if (ready == null) {
                    continue;
                }
                if (firstReady < 0) {
                    firstReady = register;
                }
                if (ready.getFirst().slot().inventorySlot() == openBox) {
                    chosen = register;
                    break;
                }
            }
            if (chosen < 0) {
                int free = registers.indexOf(null);
                int nextCycle = free < 0 ? -1 : findCycleStartingIn(pending, openBox);
                if (nextCycle >= 0 || firstReady < 0) {
                    chosen = free;
                    var cycle = pending.remove(nextCycle < 0 ? 0 : nextCycle);
                    registers.set(chosen, new ArrayDeque<>(cycle.steps()));
                } else {
                    chosen = firstReady;
                }
            }

            var ready = registers.get(chosen);
            var step = ready.removeFirst();
            grouped.add(new ScheduledSwap<>(chosen, step));
            openBox = step.slot().inventorySlot();
            if (ready.isEmpty()) {
                registers.set(chosen, null);
            }
        }
        return countSwapOpens(grouped) < countSwapOpens(original) ? grouped : original;
    }

    private static <K> int findCycleStartingIn(List<SwapCycle<K>> cycles, int box) {
        for (int i = 0; i < cycles.size(); i++) {
            if (cycles.get(i).steps().getFirst().slot().inventorySlot() == box) {
                return i;
            }
        }
        return -1;
    }

    private static int countSwapOpens(List<? extends ScheduledSwap<?>> swaps) {
        int opens = 0;
        int lastBox = -1;
        for (var swap : swaps) {
            int box = swap.step().slot().inventorySlot();
            if (box != lastBox) {
                opens++;
                lastBox = box;
            }
        }
        return opens;
    }

    record ScheduledSwap<K>(int register, SwapStep<K> step) {}

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
