package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-20：潜影盒逐槽替换必须保留原槽位，包括 slot 26 和多个空洞。 */
@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaShulkerSlotExportTest {
    private final RegistryAccess lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @AfterEach
    void clearRules() throws Exception {
        setRules(List.of());
    }

    @Test
    void slotTwentySixSurvivesNestedReplacement() throws Exception {
        List<ItemStack> nestedSlots = emptySlots();
        nestedSlots.set(26, new ItemStack(Items.STONE, 64));
        List<ItemStack> outerSlots = emptySlots();
        ItemStack shulker = shulkerWith(nestedSlots);
        outerSlots.set(0, shulker);
        String group = QuickLitematicaContainerReplacements.containerGroupSignature(shulker, nestedSlots);
        setRules(List.of(new QuickLitematicaContainerReplacements.ContainerReplacement(
                new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 64), "test", group, 26, true)));

        List<ItemStack> exported = export(new ItemStack(Items.CHEST), outerSlots);
        NonNullList<ItemStack> nested = NonNullList.withSize(27, ItemStack.EMPTY);
        exported.get(0).get(DataComponents.CONTAINER).copyInto(nested);
        assertThat(nested.get(26).getItem()).isEqualTo(Items.DIRT);
        assertThat(nested.get(26).getCount()).isEqualTo(64);
        assertThat(nested.get(0).isEmpty()).isTrue();
    }

    @Test
    void multipleHolesKeepTheirOwnIndices() throws Exception {
        List<ItemStack> nestedSlots = emptySlots();
        nestedSlots.set(2, new ItemStack(Items.STONE, 64));
        nestedSlots.set(19, new ItemStack(Items.STONE, 32));
        nestedSlots.set(26, new ItemStack(Items.STONE, 16));
        List<ItemStack> outerSlots = emptySlots();
        ItemStack shulker = shulkerWith(nestedSlots);
        outerSlots.set(0, shulker);
        String group = QuickLitematicaContainerReplacements.containerGroupSignature(shulker, nestedSlots);
        setRules(List.of(new QuickLitematicaContainerReplacements.ContainerReplacement(
                        new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 64), "test", group, 2, true),
                new QuickLitematicaContainerReplacements.ContainerReplacement(
                        new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 64), "test", group, 19, true),
                new QuickLitematicaContainerReplacements.ContainerReplacement(
                        new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 64), "test", group, 26, true)));

        List<ItemStack> exported = export(new ItemStack(Items.CHEST), outerSlots);
        NonNullList<ItemStack> result = NonNullList.withSize(27, ItemStack.EMPTY);
        exported.get(0).get(DataComponents.CONTAINER).copyInto(result);
        assertThat(result.get(2).getItem()).isEqualTo(Items.DIRT);
        assertThat(result.get(2).getCount()).isEqualTo(64);
        assertThat(result.get(19).getItem()).isEqualTo(Items.DIRT);
        assertThat(result.get(19).getCount()).isEqualTo(32);
        assertThat(result.get(26).getItem()).isEqualTo(Items.DIRT);
        assertThat(result.get(26).getCount()).isEqualTo(16);
        assertThat(result.get(1).isEmpty()).isTrue();
        assertThat(result.get(25).isEmpty()).isTrue();
    }

    @Test
    void noMatchingRuleKeepsOriginalContents() throws Exception {
        List<ItemStack> nestedSlots = emptySlots();
        nestedSlots.set(26, new ItemStack(Items.STONE, 64));
        List<ItemStack> outerSlots = emptySlots();
        outerSlots.set(0, shulkerWith(nestedSlots));
        setRules(List.of());

        List<ItemStack> exported = export(new ItemStack(Items.CHEST), outerSlots);
        NonNullList<ItemStack> result = NonNullList.withSize(27, ItemStack.EMPTY);
        exported.get(0).get(DataComponents.CONTAINER).copyInto(result);
        assertThat(result.get(26).getItem()).isEqualTo(Items.STONE);
        assertThat(result.get(26).getCount()).isEqualTo(64);
    }

    private static ItemStack shulkerWith(List<ItemStack> slots) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        return shulker;
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>(27);
        for (int i = 0; i < 27; i++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> export(ItemStack container, List<ItemStack> slots) throws Exception {
        Method method = QuickLitematicaContainerMaterials.class.getDeclaredMethod(
                "exportSlotStacks", ItemStack.class, List.class, String.class,
                HolderLookup.Provider.class, int.class, boolean.class);
        method.setAccessible(true);
        return (List<ItemStack>) method.invoke(null, container, slots, "test", lookup, 0, false);
    }

    private void setRules(List<QuickLitematicaContainerReplacements.ContainerReplacement> rules) throws Exception {
        List<String> entries = new ArrayList<>();
        for (QuickLitematicaContainerReplacements.ContainerReplacement rule : rules) {
            if (rule != null) {
                entries.add(QuickLitematicaContainerReplacements.encodeContainerRule(rule, lookup));
            }
        }
        Method method = QuickLitematicaContainerReplacements.class.getDeclaredMethod(
                "setConfiguredRules", QuickLitematicaContainerReplacements.Scope.class, List.class);
        method.setAccessible(true);
        method.invoke(null, QuickLitematicaContainerReplacements.Scope.CONTAINER, entries);
    }
}
