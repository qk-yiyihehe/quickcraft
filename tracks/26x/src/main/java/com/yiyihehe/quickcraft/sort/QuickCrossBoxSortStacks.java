package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 保留空槽的不可变规划数据，物品身份不含数量。 */
public final class QuickCrossBoxSortStacks {
    private QuickCrossBoxSortStacks() {}

    public record Stack<K>(K key, int count, int maxCount) {
        public Stack {
            java.util.Objects.requireNonNull(key);
            if (count <= 0 || maxCount <= 0 || count > maxCount) {
                throw new IllegalArgumentException("Invalid stack size");
            }
        }
    }

    public record Box<K>(int inventorySlot, List<Stack<K>> slots) {
        public Box {
            slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }
    }

    public record Transfer<K>(
            int sourceInventorySlot,
            int sourceBoxSlot,
            int targetInventorySlot,
            int targetBoxSlot,
            K key,
            int amount
    ) {
    }
}
