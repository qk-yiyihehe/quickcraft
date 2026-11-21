package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.compat.voidwarehouse.QuickLitematicaVoidWarehouseMaterials;
import com.yiyihehe.quickcraft.compat.voidwarehouse.VoidWarehouseMaterialCounts;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QuickLitematicaMaterialInventory {
    private record StackKey(Item item, DataComponentMap components) {
        static StackKey of(ItemStack stack) { return new StackKey(stack.getItem(), stack.getComponents()); }
    }

    private QuickLitematicaMaterialInventory() { }

    static int countAvailable(List<ItemStack> targets) {
        var player = Minecraft.getInstance().player;
        if (player == null) { return 0; }
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            count += countStack(inventory.getItem(slot), targets, 0);
        }
        return count;
    }

    static int[] countAvailableFor(List<ItemStack> targets) {
        int[] result = new int[targets.size()];
        if (targets.isEmpty()) { return result; }
        var player = Minecraft.getInstance().player;
        if (player == null) { return result; }
        Map<StackKey, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            countStack(inventory.getItem(slot), counts, 0, 1);
        }
        for (int i = 0; i < targets.size(); i++) {
            result[i] = counts.getOrDefault(StackKey.of(targets.get(i)), 0);
        }
        return result;
    }

    private static void countStack(ItemStack stack, Map<StackKey, Integer> counts, int depth, int multiplier) {
        if (stack.isEmpty()) { return; }
        int amount = multiplier * stack.getCount();
        counts.merge(StackKey.of(stack), amount, Integer::sum);
        if (depth < 4 && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            var stored = InventoryUtils.getStoredItems(stack);
            if (stored != null) {
                for (ItemStack nested : stored) { countStack(nested, counts, depth + 1, amount); }
            }
        }
    }

    /**
     * 材料「已有数量」的对外口径：背包（含嵌套潜影盒）+ 虚空仓库。
     *
     * <p>开关关闭时与 {@link #countAvailable} 完全一致，不会产生任何额外发包或计算。
     * 仓库只影响「已有数量」（只读统计）；{@code MaterialListEntry} 的缺失数量是
     * {@code final}，且本功能不会真的把材料取到手上，所以「缺失」仍按实际手上的缺口计算。</p>
     */
    static int countAvailableWithSources(List<ItemStack> targets) {
        int inventory = countAvailable(targets);
        if (!QuickLitematicaVoidWarehouseMaterials.isSourceEnabled()) {
            return inventory;
        }
        // 先登记需求，查询由 tick 按节奏驱动；这里只读快照，不阻塞渲染。
        QuickLitematicaVoidWarehouseMaterials.noteDemand(targets);
        return VoidWarehouseMaterialCounts.merge(inventory, QuickLitematicaVoidWarehouseMaterials.amountFor(targets));
    }

    private static int countStack(ItemStack stack, List<ItemStack> targets, int depth) {
        if (stack.isEmpty()) { return 0; }
        int count = targets.stream().anyMatch(target -> ItemStack.isSameItemSameComponents(target, stack))
                ? stack.getCount() : 0;
        // 与容器材料和末影箱统计保持相同上限，兼容服务器提供的嵌套潜影盒。
        if (depth < 4 && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            var stored = InventoryUtils.getStoredItems(stack);
            if (stored != null) {
                for (ItemStack nested : stored) { count += stack.getCount() * countStack(nested, targets, depth + 1); }
            }
        }
        return count;
    }
}
