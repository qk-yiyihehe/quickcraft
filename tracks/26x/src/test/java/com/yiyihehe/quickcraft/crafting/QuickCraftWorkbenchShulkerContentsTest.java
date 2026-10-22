package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftWorkbenchShulkerContentsTest {
    @AfterEach
    void clearCache() {
        QuickCraftWorkbenchShulkerContents.clear();
    }

    @Test
    void reusesUnchangedContentsAcrossFullUpdatesAndOnlyRefreshesChangedSlots() {
        ItemStack box = box(new ItemStack(Items.STONE, 40), new ItemStack(Items.STONE, 20));
        ItemStack other = box(new ItemStack(Items.STONE, 64));
        var original = QuickCraftWorkbenchShulkerContents.get(9, box);
        var unchanged = QuickCraftWorkbenchShulkerContents.get(10, other);
        assertThat(original.count()).isEqualTo(60);
        assertThat(original.capacity(new ItemStack(Items.STONE))).isEqualTo(27 * 64 - 60);
        assertThat(QuickCraftWorkbenchShulkerContents.get(9, box)).isSameAs(original);

        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 40), new ItemStack(Items.STONE, 20))));
        assertThat(QuickCraftWorkbenchShulkerContents.get(9, box)).isSameAs(original);

        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 4))));
        var reduced = QuickCraftWorkbenchShulkerContents.get(9, box);
        assertThat(reduced).isNotSameAs(original);
        assertThat(reduced.count()).isEqualTo(4);
        assertThat(QuickCraftWorkbenchShulkerContents.get(10, other)).isSameAs(unchanged);

        box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        var empty = QuickCraftWorkbenchShulkerContents.get(9, box);
        assertThat(empty.count()).isZero();
        assertThat(empty.containsOnly(new ItemStack(Items.STONE))).isTrue();
        assertThat(empty.capacity(new ItemStack(Items.STONE))).isEqualTo(27 * 64);

        QuickCraftWorkbenchShulkerContents.clear();
        assertThat(QuickCraftWorkbenchShulkerContents.get(10, other)).isNotSameAs(unchanged);
    }

    @Test
    void refreshesCursorContentsAndRejectsMixedOrComponentMismatchedBoxes() {
        ItemStack box = box(new ItemStack(Items.STONE, 12));
        var original = QuickCraftWorkbenchShulkerContents.get(box);
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 20), new ItemStack(Items.DIRT, 10))));
        var mixed = QuickCraftWorkbenchShulkerContents.get(box);
        assertThat(mixed).isNotSameAs(original);
        assertThat(mixed.homogeneous()).isFalse();
        assertThat(mixed.containsOnly(new ItemStack(Items.STONE))).isFalse();
        assertThat(mixed.capacity(new ItemStack(Items.DIRT))).isEqualTo(25 * 64 + 54);

        ItemStack named = new ItemStack(Items.STONE, 3);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("named"));
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 2), named)));
        assertThat(QuickCraftWorkbenchShulkerContents.get(box).homogeneous()).isFalse();
    }

    private static ItemStack box(ItemStack... contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }
}
