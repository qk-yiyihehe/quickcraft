package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.ComponentChanges;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;
import java.util.*;

/** 生存模式模拟合书与装备加书，创造模式可直接预览目标；规划不修改玩家背包或经验。 */
public final class QuickCraftAnvilPlanner {
    /** bookSlot=-1 为创造模式直接附魔，不读取或生成附魔书。 */
    public record Step(int inputSlot, int bookSlot, ItemStack book, ItemStack input, ItemStack output, int cost) {}
    public record State(int equipmentSlot, ItemStack equipment, long used, List<Step> steps) {}
    // 组件改动也参与缓存键，避免串用不同名称、耐久或铁砧惩罚费用的结果。
    private record Key(Item item, ComponentChanges changes) {}
    private final Map<RegistryEntry<Enchantment>, Integer> target = new LinkedHashMap<>();
    private final Map<ItemStack, Map<RegistryEntry<Enchantment>, Integer>> searchEnchantments = new IdentityHashMap<>();
    private final ItemStack initialEquipment;
    private final boolean pickaxe;
    private final boolean creative;
    private final AnvilScreenHandler simulation;
    private final QuickCraftAnvilCombinationSearch<ItemStack, Key> search;
    private final QuickCraftAnvilSearch.Result<State> creativePlan;

    public QuickCraftAnvilPlanner(MinecraftClient client, AnvilScreenHandler handler) {
        ItemStack seed = handler.getSlot(0).getStack();
        initialEquipment = seed.copy();
        creative = client.player.getAbilities().creativeMode && QuickCraftConfigs.Crafting.ANVIL_CREATIVE_NO_BOOKS.getBooleanValue();
        var preset = QuickCraftAnvilPresets.forItem(Registries.ITEM.getId(seed.getItem()).toString(),
                QuickCraftConfigs.Crafting.ANVIL_PICKAXE_PLAN.getOptionListValue() == QuickCraftConfigs.AnvilPickaxePlan.FORTUNE);
        pickaxe = preset == QuickCraftAnvilPresets.PICKAXE_SILK || preset == QuickCraftAnvilPresets.PICKAXE_FORTUNE;
        if (seed.isEmpty() || preset == null) throw new IllegalArgumentException("target");
        var registry = QuickCraftAnvilAccess.enchantments(client);
        for (String spec : preset.getStrings()) {
            var entrySpec = com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.parse(
                    com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.canonical(spec));
            String name = entrySpec.name();
            int level = entrySpec.level();
            RegistryEntry<Enchantment> found = null;
            for (var entry : registry) {
                String id = entry.getKey().orElseThrow().getValue().toString();
                if (id.equals(name) || id.equals("minecraft:" + name)
                        || Text.translatable("enchantment." + id.replace(':', '.')).getString().equals(name)) {
                    found = entry;
                    break;
                }
            }
            if (found == null || level < 1 || level > found.value().getMaxLevel()
                    || !found.value().isAcceptableItem(seed) || target.put(found, level) != null) {
                throw new IllegalArgumentException("config");
            }
        }
        if (target.isEmpty()) throw new IllegalArgumentException("config");
        for (var first : target.keySet()) for (var second : target.keySet()) {
            if (!first.equals(second) && !Enchantment.canBeCombined(first, second)) {
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
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            if (stack.isOf(seed.getItem()) && stack.getCount() == 1 && !complete(stack)) {
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, stack.copy(), true));
            } else if (slot >= 3 && stack.isOf(Items.ENCHANTED_BOOK) && stack.getCount() == 1
                    && !enchantments(stack).isEmpty() && target.keySet().containsAll(enchantments(stack).keySet())) {
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, stack.copy(), false));
            }
        }
        if (materials.stream().noneMatch(QuickCraftAnvilCombinationSearch.Material::equipment)) {
            throw new IllegalArgumentException(complete(seed) ? "already" : "conflict");
        }
        simulation = new AnvilScreenHandler(-1, client.player.getInventory()) {
            // 模拟输入批量设置后统一计算，避免每次换槽位都重算一次。
            @Override public void onContentChanged(Inventory inventory) {}
        };
        creativePlan = null;
        search = new QuickCraftAnvilCombinationSearch<>(materials,
                stack -> new Key(stack.getItem(), stack.getComponentChanges()),
                this::combine, this::complete, this::progress);
    }

    static ItemStack creativeOutput(ItemStack seed, Map<RegistryEntry<Enchantment>, Integer> target) {
        ItemStack output = seed.copy();
        for (var desired : target.entrySet()) {
            var current = enchantments(output);
            if (current.getOrDefault(desired.getKey(), 0) >= desired.getValue()) continue;
            if (current.keySet().stream().anyMatch(existing -> !existing.equals(desired.getKey())
                    && !Enchantment.canBeCombined(existing, desired.getKey()))) continue;
            output.addEnchantment(desired.getKey(), desired.getValue());
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

    private int progress(Map<RegistryEntry<Enchantment>, Integer> current) {
        return target.entrySet().stream().mapToInt(entry -> Math.min(entry.getValue(), current.getOrDefault(entry.getKey(), 0))).sum();
    }

    private QuickCraftAnvilCombinationSearch.Merge<ItemStack> combine(ItemStack left, ItemStack right) {
        var inputEnchantments = cachedEnchantments(left);
        var addedEnchantments = cachedEnchantments(right);
        if (addedEnchantments.entrySet().stream().noneMatch(entry -> {
            int current = inputEnchantments.getOrDefault(entry.getKey(), 0);
            return current < target.getOrDefault(entry.getKey(), 0) && entry.getValue() >= current;
        })) return null;
        simulation.getSlot(1).setStack(ItemStack.EMPTY);
        simulation.getSlot(0).setStack(left.copy());
        simulation.setNewItemName(left.contains(DataComponentTypes.CUSTOM_NAME) ? left.getName().getString() : "");
        simulation.getSlot(1).setStack(right.copy());
        simulation.updateResult();
        ItemStack output = simulation.getSlot(2).getStack().copy();
        int cost = simulation.getLevelCost();
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

    private Map<RegistryEntry<Enchantment>, Integer> cachedEnchantments(ItemStack stack) {
        return searchEnchantments.computeIfAbsent(stack, QuickCraftAnvilPlanner::enchantments);
    }

    private static Map<RegistryEntry<Enchantment>, Integer> enchantments(ItemStack stack) {
        Map<RegistryEntry<Enchantment>, Integer> result = new HashMap<>();
        for (var entry : EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries()) {
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
    public static List<Text> bookDescription(ItemStack book) {
        return enchantments(book).entrySet().stream()
                .map(entry -> Enchantment.getName(entry.getKey(), entry.getValue())).toList();
    }

    public static boolean supportsSpear() {
        return Registries.ITEM.getIds().stream()
                .anyMatch(id -> id.toString().equals("minecraft:diamond_spear"));
    }

    public List<Text> missingDescription() {
        var result = result();
        var current = enchantments(result == null ? initialEquipment : result.state().equipment);
        return target.entrySet().stream()
                .filter(entry -> current.getOrDefault(entry.getKey(), 0) < entry.getValue())
                .map(entry -> Enchantment.getName(entry.getKey(), entry.getValue())).toList();
    }

    public List<Text> targetDescription() {
        return target.entrySet().stream().map(entry -> Enchantment.getName(entry.getKey(), entry.getValue())).toList();
    }
}
