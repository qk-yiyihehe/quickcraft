package com.yiyihehe.quickcraft.crafting;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 非负等级费用上的多起点 Dijkstra；完整目标出队即最优，部分目标需穷尽后择优。
 * 原版扣等级保留经验条进度；固定起始经验时，累计扣级最少也就是剩余经验点最多。
 */
public final class QuickCraftAnvilSearch<S, K> {
    public record Edge<S>(S state, int cost) {}
    public record Result<S>(S state, int cost) {}
    private final Function<S, K> key;
    private final Predicate<S> goal;
    private final Function<S, List<Edge<S>>> expand;
    private final ToIntFunction<S> partialProgress;
    private final PriorityQueue<Result<S>> pending = new PriorityQueue<>(Comparator.comparingInt(Result::cost));
    private final Map<K, Integer> distance = new HashMap<>();
    private Result<S> result;
    private Result<S> bestPartial;

    public QuickCraftAnvilSearch(List<S> roots, Function<S, K> key, Predicate<S> goal,
                                Function<S, List<Edge<S>>> expand) {
        this(roots, key, goal, expand, null);
    }

    public QuickCraftAnvilSearch(List<S> roots, Function<S, K> key, Predicate<S> goal,
                                Function<S, List<Edge<S>>> expand, ToIntFunction<S> partialProgress) {
        this.key = key;
        this.goal = goal;
        this.expand = expand;
        this.partialProgress = partialProgress;
        for (S root : roots) offer(root, 0);
    }

    private void offer(S state, int cost) {
        K stateKey = key.apply(state);
        if (cost < distance.getOrDefault(stateKey, Integer.MAX_VALUE)) {
            distance.put(stateKey, cost);
            pending.add(new Result<>(state, cost));
        }
    }

    public boolean advance(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        do {
            if (result != null) return true;
            if (pending.isEmpty()) {
                result = bestPartial;
                return true;
            }
            Result<S> next = pending.remove();
            if (next.cost != distance.get(key.apply(next.state))) continue;
            // 缺书时也必须穷尽可行状态，先最大化目标进度，同进度再选最低费用。
            if (partialProgress != null && (bestPartial == null
                    || partialProgress.applyAsInt(next.state) > partialProgress.applyAsInt(bestPartial.state)
                    || (partialProgress.applyAsInt(next.state) == partialProgress.applyAsInt(bestPartial.state)
                        && next.cost < bestPartial.cost))) bestPartial = next;
            if (goal.test(next.state)) {
                result = next;
                return true;
            }
            for (Edge<S> edge : expand.apply(next.state)) {
                if (edge.cost < 0) throw new IllegalArgumentException("Negative anvil cost");
                offer(edge.state, Math.addExact(next.cost, edge.cost));
            }
            // 不在超出内存预算后把尚未证明的路线当作最优路线执行。
            if (distance.size() > 200_000) throw new IllegalStateException("Anvil search too large");
        } while (System.nanoTime() < end);
        return false;
    }

    public Result<S> result() { return result; }

    public static double experienceAt(int level, float progress) {
        double base = level <= 16 ? (double) level * level + 6.0 * level
                : level <= 31 ? 2.5 * level * level - 40.5 * level + 360
                : 4.5 * level * level - 162.5 * level + 2220;
        int next = level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
        return base + progress * next;
    }
}
