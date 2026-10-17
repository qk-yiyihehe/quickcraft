package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.network.chat.Component;
import java.util.*;

/** 生存模式模拟合书与装备加书，创造模式可直接预览目标；规划不修改玩家背包或经验。 */
public final class QuickCraftAnvilPlanner {
    /** bookSlot=-1 为创造模式直接附魔，不读取或生成附魔书。 */
    public record Step(int inputSlot, int bookSlot, ItemStack book, ItemStack input, ItemStack output, int cost) {}
    public record State(int equipmentSlot, ItemStack equipment, long used, List<Step> steps) {}
    // 组件改动也参与缓存键，避免串用不同名称、耐久或铁砧惩罚费用的结果。
    private record Key(Item item, DataComponentPatch changes) {}
    private final Map<Holder<Enchantment>, Integer> target = new LinkedHashMap<>();
    private final Map<ItemStack, Map<Holder<Enchantment>, Integer>> searchEnchantments = new IdentityHashMap<>();
    private final ItemStack initialEquipment;
    private final boolean pickaxe;
    private final boolean creative;
    private final AnvilMenu simulation;
    private final QuickCraftAnvilCombinationSearch<ItemStack, Key> search;
    private final QuickCraftAnvilSearch.Result<State> creativePlan;

    public QuickCraftAnvilPlanner(Minecraft client, AnvilMenu handler) {
        ItemStack seed = handler.getSlot(0).getItem();
        initialEquipment = seed.copy();
        creative = client.player.hasInfiniteMaterials() && QuickCraftConfigs.Crafting.ANVIL_CREATIVE_NO_BOOKS.getBooleanValue();
        var preset = QuickCraftAnvilPresets.forItem(BuiltInRegistries.ITEM.getKey(seed.getItem()).toString(),
                QuickCraftConfigs.Crafting.ANVIL_PICKAXE_PLAN.getOptionListValue() == QuickCraftConfigs.AnvilPickaxePlan.FORTUNE);
        pickaxe = preset == QuickCraftAnvilPresets.PICKAXE_SILK || preset == QuickCraftAnvilPresets.PICKAXE_FORTUNE;
        if (seed.isEmpty() || preset == null) throw new IllegalArgumentException("target");
        var registry = QuickCraftAnvilAccess.enchantments(client);
        for (String spec : preset.getStrings()) {
            var entrySpec = com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.parse(
                    com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.canonical(spec));
            String name = entrySpec.name();
            int level = entrySpec.level();
            Holder<Enchantment> found = null;
            for (var entry : registry) {
                String id = entry.unwrapKey().orElseThrow().identifier().toString();
                if (id.equals(name) || id.equals("minecraft:" + name)
                        || Component.translatable("enchantment." + id.replace(':', '.')).getString().equals(name)) {
                    found = entry;
                    break;
                }
            }
            if (found == null || level < 1 || level > found.value().getMaxLevel()
                    || !found.value().canEnchant(seed) || target.put(found, level) != null) {
                throw new IllegalArgumentException("config");
            }
        }
        if (target.isEmpty()) throw new IllegalArgumentException("config");
        for (var first : target.keySet()) for (var second : target.keySet()) {
            if (!first.equals(second) && !Enchantment.areCompatible(first, second)) {
                throw new IllegalArgumentException("conflict");
            }
        }
        if (creative) {
            if (complete(seed)) throw new IllegalArgumentException("already");
            ItemStack output = creativeOutput(seed, target);
            if (progress(output) <= progress(seed)) throw new IllegalArgumentException("no_route");
            simulation = null;
            search = null;
            creativePlan = new QuickCraftAnvilSearch.Result<>(new State(0, output, 0,
                    List.of(new Step(0, -1, ItemStack.EMPTY, seed.copy(), output, 0))), 0);
            return;
        }
        var materials = new ArrayList<QuickCraftAnvilCombinationSearch.Material<ItemStack>>();
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (slot == 1 || slot == 2 || QuickContainerLock.isLockedSlot(handler, slot)) continue;
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            if (stack.is(seed.getItem()) && stack.getCount() == 1 && !complete(stack)) {
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, stack.copy(), true));
            } else if (slot >= 3 && stack.is(Items.ENCHANTED_BOOK) && stack.getCount() == 1
                    && !enchantments(stack).isEmpty() && target.keySet().containsAll(enchantments(stack).keySet())) {
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, stack.copy(), false));
            }
        }
        if (materials.stream().noneMatch(QuickCraftAnvilCombinationSearch.Material::equipment)) {
            throw new IllegalArgumentException(complete(seed) ? "already" : "conflict");
        }
        simulation = new AnvilMenu(-1, client.player.getInventory()) {
            // 模拟输入批量设置后统一计算，避免每次换槽位都重算一次。
            @Override public void slotsChanged(Container inventory) {}
        };
        creativePlan = null;
        search = new QuickCraftAnvilCombinationSearch<>(materials,
                stack -> new Key(stack.getItem(), stack.getComponentsPatch()),
                this::combine, this::complete, this::progress);
    }

    static ItemStack creativeOutput(ItemStack seed, Map<Holder<Enchantment>, Integer> target) {
        ItemStack output = seed.copy();
        for (var desired : target.entrySet()) {
            var current = enchantments(output);
            if (current.getOrDefault(desired.getKey(), 0) >= desired.getValue()) continue;
            if (current.keySet().stream().anyMatch(existing -> !existing.equals(desired.getKey())
                    && !Enchantment.areCompatible(existing, desired.getKey()))) continue;
            output.enchant(desired.getKey(), desired.getValue());
        }
        return output;
    }

    private boolean complete(ItemStack stack) {
        var current = cachedEnchantments(stack);
        return target.entrySet().stream().allMatch(entry -> current.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private int progress(ItemStack stack) {
        return progress(cachedEnchantments(stack));
    }

    private int progress(Map<Holder<Enchantment>, Integer> current) {
        return target.entrySet().stream().mapToInt(entry -> Math.min(entry.getValue(), current.getOrDefault(entry.getKey(), 0))).sum();
    }

    private QuickCraftAnvilCombinationSearch.Merge<ItemStack> combine(ItemStack left, ItemStack right) {
        var inputEnchantments = cachedEnchantments(left);
        var addedEnchantments = cachedEnchantments(right);
        if (addedEnchantments.entrySet().stream().noneMatch(entry -> {
            int current = inputEnchantments.getOrDefault(entry.getKey(), 0);
            return current < target.getOrDefault(entry.getKey(), 0) && entry.getValue() >= current;
        })) return null;
        simulation.getSlot(1).set(ItemStack.EMPTY);
        simulation.getSlot(0).set(left.copy());
        simulation.setItemName(left.has(DataComponents.CUSTOM_NAME) ? left.getHoverName().getString() : "");
        simulation.getSlot(1).set(right.copy());
        simulation.createResult();
        ItemStack output = simulation.getSlot(2).getItem().copy();
        int cost = simulation.getCost();
        // 原版结果计算已处理费用上限；创造模式允许超过 39 级。
        if (output.isEmpty() || cost <= 0) return null;
        var outputEnchantments = enchantments(output);
        if (progress(outputEnchantments) <= progress(inputEnchantments)) return null;
        if (inputEnchantments.entrySet().stream().anyMatch(entry -> outputEnchantments.getOrDefault(entry.getKey(), 0)
                < entry.getValue())) return null;
        if (target.entrySet().stream().anyMatch(entry -> outputEnchantments.getOrDefault(entry.getKey(), 0)
                > Math.max(entry.getValue(), inputEnchantments.getOrDefault(entry.getKey(), 0)))) return null;
        return new QuickCraftAnvilCombinationSearch.Merge<>(output, cost);
    }

    private Map<Holder<Enchantment>, Integer> cachedEnchantments(ItemStack stack) {
        return searchEnchantments.computeIfAbsent(stack, QuickCraftAnvilPlanner::enchantments);
    }

    private static Map<Holder<Enchantment>, Integer> enchantments(ItemStack stack) {
        Map<Holder<Enchantment>, Integer> result = new HashMap<>();
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            result.put(entry.getKey(), entry.getIntValue());
        }
        return Map.copyOf(result);
    }

    public boolean advance() { return creative || search.advance(4_000_000); }
    public QuickCraftAnvilSearch.Result<State> result() {
        if (creative) return creativePlan;
        var result = search.result();
        if (result == null || result.steps().isEmpty()) return null;
        var steps = result.steps().stream().map(step -> new Step(step.leftSlot(), step.rightSlot(),
                step.right(), step.left(), step.output(), step.cost())).toList();
        return new QuickCraftAnvilSearch.Result<>(new State(result.sourceSlot(), result.item(), result.used(), steps), result.cost());
    }
    public boolean isPickaxe() { return pickaxe; }
    public boolean isCreative() { return creative; }
    public static List<Component> bookDescription(ItemStack book) {
        return enchantments(book).entrySet().stream()
                .map(entry -> Enchantment.getFullname(entry.getKey(), entry.getValue())).toList();
    }

    public static boolean supportsSpear() {
        return BuiltInRegistries.ITEM.keySet().stream()
                .anyMatch(id -> id.toString().equals("minecraft:diamond_spear"));
    }

    public List<Component> missingDescription() {
        var result = result();
        var current = enchantments(result == null ? initialEquipment : result.state().equipment);
        return target.entrySet().stream()
                .filter(entry -> current.getOrDefault(entry.getKey(), 0) < entry.getValue())
                .map(entry -> Enchantment.getFullname(entry.getKey(), entry.getValue())).toList();
    }

    public List<Component> targetDescription() {
        return target.entrySet().stream().map(entry -> Enchantment.getFullname(entry.getKey(), entry.getValue())).toList();
    }
}
