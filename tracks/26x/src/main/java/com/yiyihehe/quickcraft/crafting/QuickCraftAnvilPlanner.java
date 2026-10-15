package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.network.chat.Component;
import java.util.*;

/** 只模拟装备加书；独立 handler 不领取产物，不修改玩家背包或经验。 */
public final class QuickCraftAnvilPlanner {
    public record Step(int bookSlot, ItemStack book, ItemStack input, ItemStack output, int cost) {}
    public record State(int equipmentSlot, ItemStack equipment, long used, List<Step> steps) {}
    private record Key(int slot, Map<Holder<Enchantment>, Integer> enchantments, int repairCost, long used) {}
    private record Book(int slot, ItemStack stack) {}
    private final Map<Holder<Enchantment>, Integer> target = new LinkedHashMap<>();
    private final List<Book> books = new ArrayList<>();
    private final ItemStack initialEquipment;
    private final boolean pickaxe;
    private final AnvilMenu simulation;
    private final QuickCraftAnvilSearch<State, Key> search;

    public QuickCraftAnvilPlanner(Minecraft client, AnvilMenu handler) {
        ItemStack seed = handler.getSlot(0).getItem();
        initialEquipment = seed.copy();
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
        List<State> roots = new ArrayList<>();
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (slot == 1 || slot == 2 || QuickContainerLock.isLockedSlot(handler, slot)) continue;
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            if (stack.is(seed.getItem()) && stack.getCount() == 1 && !complete(stack)) {
                roots.add(new State(slot, stack.copy(), 0, List.of()));
            } else if (slot >= 3 && stack.is(Items.ENCHANTED_BOOK) && stack.getCount() == 1
                    && !enchantments(stack).isEmpty() && target.keySet().containsAll(enchantments(stack).keySet())) {
                books.add(new Book(slot, stack.copy()));
            }
        }
        if (roots.isEmpty()) throw new IllegalArgumentException(complete(seed) ? "already" : "conflict");
        simulation = new AnvilMenu(-1, client.player.getInventory());
        search = new QuickCraftAnvilSearch<>(roots, state -> new Key(state.equipmentSlot,
                enchantments(state.equipment), state.equipment.getOrDefault(DataComponents.REPAIR_COST, 0), state.used),
                state -> complete(state.equipment), this::expand,
                state -> state.steps.isEmpty() ? -1 : progress(state.equipment));
    }

    private boolean complete(ItemStack stack) {
        var current = enchantments(stack);
        return target.entrySet().stream().allMatch(entry -> current.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private int progress(ItemStack stack) {
        var current = enchantments(stack);
        return target.entrySet().stream().mapToInt(entry -> Math.min(entry.getValue(), current.getOrDefault(entry.getKey(), 0))).sum();
    }

    private List<QuickCraftAnvilSearch.Edge<State>> expand(State state) {
        List<QuickCraftAnvilSearch.Edge<State>> edges = new ArrayList<>();
        List<ItemStack> seen = new ArrayList<>();
        for (int index = 0; index < books.size(); index++) {
            if ((state.used & (1L << index)) != 0) continue;
            Book book = books.get(index);
            if (seen.stream().anyMatch(previous -> ItemStack.isSameItemSameComponents(previous, book.stack))) continue;
            seen.add(book.stack);
            simulation.getSlot(1).set(ItemStack.EMPTY);
            simulation.getSlot(0).set(state.equipment.copy());
            simulation.setItemName(state.equipment.getHoverName().getString());
            simulation.getSlot(1).set(book.stack.copy());
            simulation.createResult();
            ItemStack output = simulation.getSlot(2).getItem().copy();
            int cost = simulation.getCost();
            if (output.isEmpty() || cost <= 0 || cost >= 40 || progress(output) <= progress(state.equipment)) continue;
            var outputEnchantments = enchantments(output);
            if (target.entrySet().stream().anyMatch(entry -> outputEnchantments.getOrDefault(entry.getKey(), 0)
                    > Math.max(entry.getValue(), enchantments(state.equipment).getOrDefault(entry.getKey(), 0)))) continue;
            List<Step> steps = new ArrayList<>(state.steps);
            steps.add(new Step(book.slot, book.stack, state.equipment.copy(), output, cost));
            edges.add(new QuickCraftAnvilSearch.Edge<>(new State(state.equipmentSlot, output,
                    state.used | (1L << index), List.copyOf(steps)), cost));
        }
        return edges;
    }

    private static Map<Holder<Enchantment>, Integer> enchantments(ItemStack stack) {
        Map<Holder<Enchantment>, Integer> result = new HashMap<>();
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            result.put(entry.getKey(), entry.getIntValue());
        }
        return Map.copyOf(result);
    }

    public boolean advance() { return search.advance(2_000_000); }
    public QuickCraftAnvilSearch.Result<State> result() {
        var result = search.result();
        return result != null && !result.state().steps.isEmpty() ? result : null;
    }
    public boolean isPickaxe() { return pickaxe; }
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
