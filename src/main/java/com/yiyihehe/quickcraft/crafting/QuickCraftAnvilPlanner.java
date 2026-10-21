package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import java.util.*;

/** 以原版费用公式规划，只为最终路线生成物品副本；规划不修改玩家背包或经验。 */
public final class QuickCraftAnvilPlanner {
    /** bookSlot=-1 为创造模式直接附魔，不读取或生成附魔书。 */
    public record Step(int inputSlot, int bookSlot, ItemStack book, ItemStack input, ItemStack output, int cost) {}
    public record State(int equipmentSlot, ItemStack equipment, long used, List<Step> steps) {}
    public record Plan(State state, int cost) {}
    private final Map<RegistryEntry<Enchantment>, Integer> target = new LinkedHashMap<>();
    private final List<RegistryEntry<Enchantment>> order = new ArrayList<>();
    private final Map<Integer, ItemStack> sources = new HashMap<>();
    private final ItemStack initialEquipment;
    private final boolean pickaxe;
    private final boolean creative;
    private final QuickCraftAnvilCombinationSearch<QuickCraftAnvilCostModel.Item, QuickCraftAnvilCostModel.Item> search;
    private final Plan creativePlan;
    private Plan planned;

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
        order.addAll(target.keySet());
        for (var first : target.keySet()) for (var second : target.keySet()) {
            if (!first.equals(second) && !Enchantment.canBeCombined(first, second)) {
                throw new IllegalArgumentException("conflict");
            }
        }
        if (creative) {
            if (complete(seed)) throw new IllegalArgumentException("already");
            ItemStack output = creativeOutput(seed, target);
            if (progress(output) <= progress(seed)) throw new IllegalArgumentException("no_route");
            search = null;
            creativePlan = new Plan(new State(0, output, 0,
                    List.of(new Step(0, -1, ItemStack.EMPTY, seed.copy(), output, 0))), 0);
            return;
        }
        var materials = new ArrayList<QuickCraftAnvilCombinationSearch.Material<QuickCraftAnvilCostModel.Item>>();
        var profiles = new ArrayList<int[]>();
        var profileIds = new HashMap<List<Integer>, Integer>();
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (slot == 1 || slot == 2 || QuickContainerLock.isLockedSlot(handler, slot)) continue;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            if (stack.isOf(seed.getItem()) && stack.getCount() == 1 && !complete(stack)) {
                int[] conflicts = new int[order.size()];
                for (int index = 0; index < order.size(); index++) {
                    var desired = order.get(index);
                    for (var existing : enchantments(stack).keySet()) {
                        if (!existing.equals(desired) && !Enchantment.canBeCombined(existing, desired)) conflicts[index]++;
                    }
                }
                var profileKey = Arrays.stream(conflicts).boxed().toList();
                int profile = profileIds.computeIfAbsent(profileKey, ignored -> {
                    profiles.add(conflicts);
                    return profiles.size() - 1;
                });
                sources.put(slot, stack.copy());
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, model(stack, profile), true));
            } else if (slot >= 3 && stack.isOf(Items.ENCHANTED_BOOK) && stack.getCount() == 1
                    && !enchantments(stack).isEmpty() && target.keySet().containsAll(enchantments(stack).keySet())) {
                sources.put(slot, stack.copy());
                materials.add(new QuickCraftAnvilCombinationSearch.Material<>(slot, model(stack, -1), false));
            }
        }
        if (materials.stream().noneMatch(QuickCraftAnvilCombinationSearch.Material::equipment)) {
            throw new IllegalArgumentException(complete(seed) ? "already" : "conflict");
        }
        creativePlan = null;
        var formula = new QuickCraftAnvilCostModel(order.stream().mapToInt(target::get).toArray(),
                order.stream().mapToInt(entry -> entry.value().getMaxLevel()).toArray(),
                order.stream().mapToInt(entry -> Math.max(1, entry.value().getAnvilCost() / 2)).toArray(),
                profiles, client.player.getAbilities().creativeMode);
        int reachable = formula.reachableProgress(materials);
        search = new QuickCraftAnvilCombinationSearch<>(materials, item -> item, formula::combine,
                item -> formula.progress(item) >= reachable, formula::progress, QuickCraftAnvilCostModel.Item::dominanceKey,
                (left, right) -> left.repairCost() <= right.repairCost(), formula::redundantStock);
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
        var current = enchantments(stack);
        return target.entrySet().stream().allMatch(entry -> current.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private int progress(ItemStack stack) {
        return progress(enchantments(stack));
    }

    private int progress(Map<RegistryEntry<Enchantment>, Integer> current) {
        return target.entrySet().stream().mapToInt(entry -> Math.min(entry.getValue(), current.getOrDefault(entry.getKey(), 0))).sum();
    }

    private QuickCraftAnvilCostModel.Item model(ItemStack stack, int profile) {
        var current = enchantments(stack);
        return new QuickCraftAnvilCostModel.Item(order.stream().map(entry -> current.getOrDefault(entry, 0)).toList(),
                stack.getOrDefault(DataComponentTypes.REPAIR_COST, 0), profile,
                stack.contains(DataComponentTypes.CUSTOM_NAME) && StringHelper.isBlank(stack.getName().getString()));
    }

    private ItemStack materialize(ItemStack left, QuickCraftAnvilCostModel.Item expected) {
        ItemStack output = left.copy();
        var enchantments = new ItemEnchantmentsComponent.Builder(EnchantmentHelper.getEnchantments(left));
        for (int index = 0; index < order.size(); index++) enchantments.set(order.get(index), expected.levels().get(index));
        EnchantmentHelper.set(output, enchantments.build());
        output.set(DataComponentTypes.REPAIR_COST, expected.repairCost());
        if (left.contains(DataComponentTypes.CUSTOM_NAME) && StringHelper.isBlank(left.getName().getString())) {
            output.remove(DataComponentTypes.CUSTOM_NAME);
        }
        return output;
    }

    private static Map<RegistryEntry<Enchantment>, Integer> enchantments(ItemStack stack) {
        Map<RegistryEntry<Enchantment>, Integer> result = new HashMap<>();
        for (var entry : EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries()) {
            result.put(entry.getKey(), entry.getIntValue());
        }
        return Map.copyOf(result);
    }

    public boolean advance() { return creative || search.advance(4_000_000); }
    public Plan result() {
        if (creative) return creativePlan;
        if (planned != null) return planned;
        var result = search.result();
        if (result == null || result.steps().isEmpty()) return null;
        var current = new HashMap<>(sources);
        var steps = new ArrayList<Step>();
        for (var step : result.steps()) {
            var left = current.get(step.leftSlot());
            var right = current.remove(step.rightSlot());
            var output = materialize(left, step.output());
            steps.add(new Step(step.leftSlot(), step.rightSlot(), right, left, output, step.cost()));
            current.put(step.leftSlot(), output);
        }
        planned = new Plan(new State(result.sourceSlot(), current.get(result.sourceSlot()),
                result.used(), List.copyOf(steps)), result.cost());
        return planned;
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
