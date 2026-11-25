package com.yiyihehe.quickcraft.sort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只传来源槽号与数量；服务端从真实库存取物品，不接受客户端物品模板。 */
public record QuickCrossBoxSortRequest(
        int menuId,
        List<Integer> menuSlots,
        List<Target> targets,
        List<QuickCrossBoxSortSpares.Spare> splits
) {
    public QuickCrossBoxSortRequest {
        targets = List.copyOf(targets);
        splits = List.copyOf(splits);
        menuSlots = List.copyOf(menuSlots);
    }

    public record Source(int index, int amount) {
    }

    public record Target(int inventorySlot, List<List<Source>> slots) {
        public Target {
            slots = slots.stream().map(List::copyOf).toList();
        }
    }

    static QuickCrossBoxSortRequest create(
            List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> boxes,
            List<QuickCrossBoxSortStacks.Box<QuickCrossBoxSortStackKey>> targets,
            List<QuickCrossBoxSortSpares.Spare> splits,
            int menuId,
            List<Integer> menuSlots
    ) {
        Map<QuickCrossBoxSortStackKey, ArrayDeque<int[]>> pools = new HashMap<>();
        for (var box : boxes) {
            for (int slot = 0; slot < 27; slot++) {
                var stack = box.slots().get(slot);
                if (stack != null) {
                    pools.computeIfAbsent(stack.key(), key -> new ArrayDeque<>()).addLast(
                            new int[] { box.inventorySlot() * 27 + slot, stack.count() }
                    );
                }
            }
        }
        List<Target> result = new ArrayList<>();
        for (var box : targets) {
            List<List<Source>> slots = new ArrayList<>(27);
            for (var stack : box.slots()) {
                List<Source> parts = new ArrayList<>();
                if (stack != null) {
                    var pool = pools.get(stack.key());
                    int remaining = stack.count();
                    while (remaining > 0) {
                        if (pool == null || pool.isEmpty()) {
                            throw new IllegalStateException("Missing source stack");
                        }
                        int[] head = pool.getFirst();
                        int amount = Math.min(remaining, head[1]);
                        parts.add(new Source(head[0], amount));
                        remaining -= amount;
                        head[1] -= amount;
                        if (head[1] == 0) {
                            pool.removeFirst();
                        }
                    }
                }
                slots.add(parts);
            }
            result.add(new Target(box.inventorySlot(), slots));
        }
        if (pools.values().stream().anyMatch(pool -> !pool.isEmpty())) {
            throw new IllegalStateException("Unconsumed source stack");
        }
        return new QuickCrossBoxSortRequest(menuId, menuSlots, result, splits);
    }
}
