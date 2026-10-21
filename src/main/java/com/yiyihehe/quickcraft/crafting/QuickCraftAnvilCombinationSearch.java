package com.yiyihehe.quickcraft.crafting;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 按累计费用搜索合成树；左右子树消耗的原始材料必须互不重叠。 */
public final class QuickCraftAnvilCombinationSearch<T, K> {
    public record Material<T>(int slot, T item, boolean equipment) {}
    public record Merge<T>(T item, int cost) {}
    public record Step<T>(int leftSlot, int rightSlot, T left, T right, T output, int cost) {}
    public record Plan<T>(int sourceSlot, T item, boolean equipment, long used, List<Step<T>> steps, int cost) {}
    private record ValueKey<K>(K item, boolean equipment) {}

    private static final class Value<T> {
        final int id;
        final T item;
        final boolean equipment;
        final boolean complete;
        final int progress;
        final List<Node<T>> alternatives;

        Value(int id, T item, boolean equipment, boolean complete, int progress, List<Node<T>> alternatives) {
            this.id = id;
            this.item = item;
            this.equipment = equipment;
            this.complete = complete;
            this.progress = progress;
            this.alternatives = alternatives;
        }
    }

    private static final class Resource<T> {
        final int id;
        final Value<T> value;
        final List<Integer> slots = new ArrayList<>();
        long mask;
        long[] prefixes;

        Resource(int id, Value<T> value) {
            this.id = id;
            this.value = value;
        }

        void prepare() {
            prefixes = new long[slots.size() + 1];
            long remaining = mask;
            for (int count = 1; count < prefixes.length; count++) {
                prefixes[count] = prefixes[count - 1] | Long.lowestOneBit(remaining);
                remaining &= remaining - 1;
            }
        }
    }

    private static final class Node<T> {
        final Value<T> value;
        final long used;
        final int cost;
        final Node<T> left;
        final Node<T> right;
        final int mergeCost;
        boolean discarded;

        Node(Value<T> value, long used, int cost,
             Node<T> left, Node<T> right, int mergeCost) {
            this.value = value;
            this.used = used;
            this.cost = cost;
            this.left = left;
            this.right = right;
            this.mergeCost = mergeCost;
        }
    }

    private static final class Group<T> {
        final long used;
        final boolean equipment;
        final List<Node<T>> nodes = new ArrayList<>();

        Group(Node<T> node) {
            used = node.used;
            equipment = node.value.equipment;
        }
    }

    private record CachedMerge<T>(T item, int cost) {}
    private final Function<T, K> key;
    private final Function<T, K> dominanceKey;
    private final BiPredicate<T, T> noWorse;
    private final BiFunction<T, T, Merge<T>> combine;
    private final Predicate<T> goal;
    private final ToIntFunction<T> progress;
    private final Map<ValueKey<K>, Value<T>> values = new HashMap<>();
    private final Map<ValueKey<K>, List<Node<T>>> alternatives = new HashMap<>();
    private final List<Resource<T>> resources = new ArrayList<>();
    private final List<Resource<T>> materialResources = new ArrayList<>();
    private final Map<Long, CachedMerge<T>> merges = new HashMap<>();
    private final PriorityQueue<Node<T>> pending = new PriorityQueue<>(Comparator.comparingInt(node -> node.cost));
    private final Map<Long, Group<T>> settledByMask = new HashMap<>();
    private final List<Group<T>> settled = new ArrayList<>();
    private Node<T> active;
    private Node<T> incumbent;
    private Node<T> bestPartial;
    private Plan<T> result;
    private int groupIndex;
    private int partnerIndex;
    private int states;
    private int pairs;
    private int groupsVisited;
    private boolean finished;
    private boolean pendingSelf;
    private long pairUsage;
    private long comparedMaterialMask;

    public QuickCraftAnvilCombinationSearch(List<Material<T>> materials, Function<T, K> key,
                                           BiFunction<T, T, Merge<T>> combine, Predicate<T> goal,
                                           ToIntFunction<T> progress) {
        this(materials, key, combine, goal, progress, key, (left, right) -> true);
    }

    public QuickCraftAnvilCombinationSearch(List<Material<T>> materials, Function<T, K> key,
                                           BiFunction<T, T, Merge<T>> combine, Predicate<T> goal,
                                           ToIntFunction<T> progress, Function<T, K> dominanceKey,
                                           BiPredicate<T, T> noWorse) {
        this(materials, key, combine, goal, progress, dominanceKey, noWorse, item -> false);
    }

    public QuickCraftAnvilCombinationSearch(List<Material<T>> materials, Function<T, K> key,
                                           BiFunction<T, T, Merge<T>> combine, Predicate<T> goal,
                                           ToIntFunction<T> progress, Function<T, K> dominanceKey,
                                           BiPredicate<T, T> noWorse, Predicate<T> redundantStock) {
        if (materials.size() > Long.SIZE) throw new IllegalStateException("Anvil search too large");
        this.key = key;
        this.dominanceKey = dominanceKey;
        this.noWorse = noWorse;
        this.combine = combine;
        this.goal = goal;
        this.progress = progress;
        var byValue = new HashMap<Integer, Resource<T>>();
        for (int index = 0; index < materials.size(); index++) {
            var material = materials.get(index);
            if (!redundantStock.test(material.item)) comparedMaterialMask |= 1L << index;
            var value = value(material.item, material.equipment);
            var resource = byValue.computeIfAbsent(value.id, ignored -> {
                var added = new Resource<>(resources.size(), value);
                resources.add(added);
                return added;
            });
            resource.slots.add(material.slot);
            resource.mask |= 1L << index;
            materialResources.add(resource);
        }
        for (var resource : resources) {
            resource.prepare();
            offer(new Node<>(resource.value, resource.prefixes[1], 0, null, null, 0));
        }
    }

    private Value<T> value(T item, boolean equipment) {
        var valueKey = new ValueKey<>(key.apply(item), equipment);
        return values.computeIfAbsent(valueKey, ignored -> new Value<>(values.size(), item, equipment,
                equipment && goal.test(item), progress.applyAsInt(item), alternatives.computeIfAbsent(
                        new ValueKey<>(dominanceKey.apply(item), equipment), unused -> new ArrayList<>())));
    }

    private void offer(Node<T> node) {
        if (incumbent != null && node.cost >= incumbent.cost) return;
        var alternatives = node.value.alternatives;
        long nodeUse = node.used & comparedMaterialMask;
        // 相同物品若有更便宜、占用材料更少的路线，可以替代其所有后续操作。
        for (var previous : alternatives) {
            long previousUse = previous.used & comparedMaterialMask;
            if ((previousUse & nodeUse) == previousUse && previous.cost <= node.cost
                    && noWorse.test(previous.value.item, node.value.item)) return;
        }
        int previousCount = alternatives.size();
        alternatives.removeIf(previous -> {
            long previousUse = previous.used & comparedMaterialMask;
            if ((previousUse & nodeUse) == nodeUse && node.cost <= previous.cost
                    && noWorse.test(node.value.item, previous.value.item)) {
                previous.discarded = true;
                return true;
            }
            return false;
        });
        states -= previousCount - alternatives.size();
        alternatives.add(node);
        if (++states > 200_000) throw new IllegalStateException("Anvil search too large");
        if (node.value.complete) incumbent = node;
        else pending.add(node);
    }

    public boolean advance(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        do {
            if (finished) return true;
            if (active == null) {
                if (pending.isEmpty()) return finish(incumbent != null ? incumbent : bestPartial);
                var next = pending.remove();
                if (next.discarded) continue;
                // 未展开子树的费用已不低于可行完整路线时，更贵的组合不可能改进答案。
                if (incumbent != null && next.cost >= incumbent.cost) return finish(incumbent);
                if (next.value.equipment && next.left != null && (bestPartial == null
                        || next.value.progress > bestPartial.value.progress
                        || (next.value.progress == bestPartial.value.progress && next.cost < bestPartial.cost))) {
                    bestPartial = next;
                }
                active = next;
                pendingSelf = true;
                groupIndex = partnerIndex = 0;
            } else if (active.discarded) {
                active = null;
            } else if (pendingSelf) {
                pendingSelf = false;
                long used = combinedUsage(active.used, active.used);
                if (!active.value.equipment && used != 0) merge(active, active, used);
            } else if (groupIndex < settled.size()) {
                var group = settled.get(groupIndex);
                if (partnerIndex == 0) {
                    if (++groupsVisited > 5_000_000) throw new IllegalStateException("Anvil search too large");
                    pairUsage = combinedUsage(active.used, group.used);
                }
                // 组内按出队费用递增，可整组跳过重叠材料、两件装备和超过费用上限的余项。
                if (pairUsage == 0 || (active.value.equipment && group.equipment)
                        || partnerIndex >= group.nodes.size()
                        || (incumbent != null && (long) active.cost + group.nodes.get(partnerIndex).cost >= incumbent.cost)) {
                    groupIndex++;
                    partnerIndex = 0;
                    continue;
                }
                var other = group.nodes.get(partnerIndex++);
                if (other.discarded) continue;
                if (++pairs > 5_000_000) throw new IllegalStateException("Anvil search too large");
                if (!other.value.equipment) merge(active, other, pairUsage);
                if (!active.value.equipment) merge(other, active, pairUsage);
            } else {
                var group = settledByMask.computeIfAbsent(active.used, ignored -> {
                    var added = new Group<>(active);
                    settled.add(added);
                    return added;
                });
                group.nodes.add(active);
                active = null;
            }
        } while (System.nanoTime() < end);
        return false;
    }

    private long combinedUsage(long left, long right) {
        long shared = left & right;
        long used = left | right;
        while (shared != 0) {
            var resource = materialResources.get(Long.numberOfTrailingZeros(shared));
            int count = Long.bitCount(left & resource.mask) + Long.bitCount(right & resource.mask);
            if (count >= resource.prefixes.length) return 0;
            // 每类书只记录用了几本；槽位在最终回溯时分配，避免枚举同类书的排列组合。
            used = (used & ~resource.mask) | resource.prefixes[count];
            shared &= ~resource.mask;
        }
        return used;
    }

    private void merge(Node<T> left, Node<T> right, long used) {
        long subtotal = (long) left.cost + right.cost;
        if (incumbent != null && subtotal >= incumbent.cost) return;
        long pairKey = ((long) left.value.id << 32) | (right.value.id & 0xffffffffL);
        var cached = merges.get(pairKey);
        if (cached == null) {
            var merged = combine.apply(left.value.item, right.value.item);
            if (merged != null && merged.cost < 0) throw new IllegalArgumentException("Negative anvil cost");
            cached = merged == null ? new CachedMerge<>(null, 0) : new CachedMerge<>(merged.item, merged.cost);
            // 无效组合也缓存；限制额外缓存内存，达到上限后仍照常精确搜索。
            if (merges.size() < 65_536) merges.put(pairKey, cached);
        }
        if (cached.item == null) return;
        long cost = subtotal + cached.cost;
        if (cost > Integer.MAX_VALUE) throw new IllegalStateException("Anvil search too large");
        if (incumbent != null && cost >= incumbent.cost) return;
        offer(new Node<>(value(cached.item, left.value.equipment), used, (int) cost, left, right, cached.cost));
    }

    private boolean finish(Node<T> node) {
        if (node != null) {
            var steps = new ArrayList<Step<T>>();
            int sourceSlot = appendSteps(node, steps, new int[resources.size()]);
            result = new Plan<>(sourceSlot, node.value.item, node.value.equipment, node.used,
                    List.copyOf(steps), node.cost);
        }
        finished = true;
        return true;
    }

    private int appendSteps(Node<T> node, List<Step<T>> steps, int[] nextSlots) {
        if (node.left == null) {
            var resource = materialResources.get(Long.numberOfTrailingZeros(node.used));
            return resource.slots.get(nextSlots[resource.id]++);
        }
        int leftSlot = appendSteps(node.left, steps, nextSlots);
        int rightSlot = appendSteps(node.right, steps, nextSlots);
        steps.add(new Step<>(leftSlot, rightSlot,
                node.left.value.item, node.right.value.item, node.value.item, node.mergeCost));
        return leftSlot;
    }

    public Plan<T> result() { return result; }
}
