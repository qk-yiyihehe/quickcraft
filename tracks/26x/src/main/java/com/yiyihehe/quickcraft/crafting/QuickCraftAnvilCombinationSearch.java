package com.yiyihehe.quickcraft.crafting;

import java.util.*;
import java.util.function.BiFunction;
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
        final List<Node<T>> alternatives = new ArrayList<>();

        Value(int id, T item, boolean equipment, boolean complete, int progress) {
            this.id = id;
            this.item = item;
            this.equipment = equipment;
            this.complete = complete;
            this.progress = progress;
        }
    }

    private static final class Node<T> {
        final Value<T> value;
        final long used;
        final int sourceSlot;
        final int cost;
        final Node<T> left;
        final Node<T> right;
        final int mergeCost;
        boolean discarded;

        Node(Value<T> value, long used, int sourceSlot, int cost,
             Node<T> left, Node<T> right, int mergeCost) {
            this.value = value;
            this.used = used;
            this.sourceSlot = sourceSlot;
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
    private final BiFunction<T, T, Merge<T>> combine;
    private final Predicate<T> goal;
    private final ToIntFunction<T> progress;
    private final Map<ValueKey<K>, Value<T>> values = new HashMap<>();
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

    public QuickCraftAnvilCombinationSearch(List<Material<T>> materials, Function<T, K> key,
                                           BiFunction<T, T, Merge<T>> combine, Predicate<T> goal,
                                           ToIntFunction<T> progress) {
        if (materials.size() > Long.SIZE) throw new IllegalStateException("Anvil search too large");
        this.key = key;
        this.combine = combine;
        this.goal = goal;
        this.progress = progress;
        for (int index = 0; index < materials.size(); index++) {
            var material = materials.get(index);
            offer(new Node<>(value(material.item, material.equipment), 1L << index,
                    material.slot, 0, null, null, 0));
        }
    }

    private Value<T> value(T item, boolean equipment) {
        var valueKey = new ValueKey<>(key.apply(item), equipment);
        return values.computeIfAbsent(valueKey, ignored ->
                new Value<>(values.size(), item, equipment, equipment && goal.test(item), progress.applyAsInt(item)));
    }

    private void offer(Node<T> node) {
        if (incumbent != null && node.cost >= incumbent.cost) return;
        var alternatives = node.value.alternatives;
        // 相同物品若有更便宜、占用材料更少的路线，可以替代其所有后续操作。
        for (var previous : alternatives) {
            if ((previous.used & node.used) == previous.used && previous.cost <= node.cost) return;
        }
        int previousCount = alternatives.size();
        alternatives.removeIf(previous -> {
            if ((previous.used & node.used) == node.used && node.cost <= previous.cost) {
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
                groupIndex = partnerIndex = 0;
            } else if (active.discarded) {
                active = null;
            } else if (groupIndex < settled.size()) {
                var group = settled.get(groupIndex);
                if (partnerIndex == 0 && ++groupsVisited > 5_000_000) throw new IllegalStateException("Anvil search too large");
                // 组内按出队费用递增，可整组跳过重叠材料、两件装备和超过费用上限的余项。
                if ((active.used & group.used) != 0 || (active.value.equipment && group.equipment)
                        || partnerIndex >= group.nodes.size()
                        || (incumbent != null && (long) active.cost + group.nodes.get(partnerIndex).cost >= incumbent.cost)) {
                    groupIndex++;
                    partnerIndex = 0;
                    continue;
                }
                var other = group.nodes.get(partnerIndex++);
                if (other.discarded) continue;
                if (++pairs > 5_000_000) throw new IllegalStateException("Anvil search too large");
                if (!other.value.equipment) merge(active, other);
                if (!active.value.equipment) merge(other, active);
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

    private void merge(Node<T> left, Node<T> right) {
        long subtotal = (long) left.cost + right.cost;
        if (incumbent != null && subtotal >= incumbent.cost) return;
        long pairKey = ((long) left.value.id << 32) | (right.value.id & 0xffffffffL);
        var cached = merges.get(pairKey);
        if (cached == null) {
            var merged = combine.apply(left.value.item, right.value.item);
            if (merged != null && merged.cost < 0) throw new IllegalArgumentException("Negative anvil cost");
            cached = merged == null ? new CachedMerge<>(null, 0) : new CachedMerge<>(merged.item, merged.cost);
            // 缓存失败模拟同样有效；限制额外缓存内存，达到上限后仍照常精确搜索。
            if (merges.size() < 65_536) merges.put(pairKey, cached);
        }
        if (cached.item == null) return;
        long cost = subtotal + cached.cost;
        if (cost > Integer.MAX_VALUE) throw new IllegalStateException("Anvil search too large");
        if (incumbent != null && cost >= incumbent.cost) return;
        offer(new Node<>(value(cached.item, left.value.equipment), left.used | right.used, left.sourceSlot,
                (int) cost, left, right, cached.cost));
    }

    private boolean finish(Node<T> node) {
        if (node != null) {
            var steps = new ArrayList<Step<T>>();
            appendSteps(node, steps);
            result = new Plan<>(node.sourceSlot, node.value.item, node.value.equipment, node.used,
                    List.copyOf(steps), node.cost);
        }
        finished = true;
        return true;
    }

    private void appendSteps(Node<T> node, List<Step<T>> steps) {
        if (node.left == null) return;
        appendSteps(node.left, steps);
        appendSteps(node.right, steps);
        steps.add(new Step<>(node.left.sourceSlot, node.right.sourceSlot,
                node.left.value.item, node.right.value.item, node.value.item, node.mergeCost));
    }

    public Plan<T> result() { return result; }
}
