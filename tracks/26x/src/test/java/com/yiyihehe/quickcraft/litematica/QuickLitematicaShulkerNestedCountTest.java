package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-19：嵌套潜影盒的分组数量必须继承父盒数量倍率。 */
@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaShulkerNestedCountTest {
    @Test
    void twoParentsDoubleOneChildAndItsStone() throws Exception {
        ItemStack child = shulkerWith(slot(0, new ItemStack(Items.STONE, 64)));
        ItemStack parent = shulkerWith(slot(0, child));
        List<?> groups = collect(parent, 2);

        Object childGroup = findGroup(groups, child);
        assertThat(childGroup).isNotNull();
        assertThat(containerCount(childGroup)).isEqualTo(2);
        assertThat(contents(childGroup)).singleElement().satisfies(content -> {
            assertThat(content.stack().getItem()).isEqualTo(Items.STONE);
            assertThat(content.count()).isEqualTo(64);
        });
    }

    @Test
    void singleParentKeepsSingleChildAndStone() throws Exception {
        ItemStack child = shulkerWith(slot(0, new ItemStack(Items.STONE, 64)));
        ItemStack parent = shulkerWith(slot(0, child));
        Object childGroup = findGroup(collect(parent, 1), child);

        assertThat(childGroup).isNotNull();
        assertThat(containerCount(childGroup)).isEqualTo(1);
        assertThat(contents(childGroup)).singleElement().satisfies(content -> {
            assertThat(content.stack().getItem()).isEqualTo(Items.STONE);
            assertThat(content.count()).isEqualTo(64);
        });
    }

    @Test
    void hudCountsExpandedTailAndNestedBoxesWithoutChangingOtherGroups() throws Exception {
        List<ItemStack> childSlots = new ArrayList<>(java.util.Collections.nCopies(54, ItemStack.EMPTY));
        childSlots.set(53, new ItemStack(Items.STONE, 64));
        ItemStack child = shulkerWith(childSlots);
        List<ItemStack> parentSlots = new ArrayList<>(java.util.Collections.nCopies(54, ItemStack.EMPTY));
        parentSlots.set(0, new ItemStack(Items.DIRT));
        parentSlots.set(53, child);
        ItemStack parent = shulkerWith(parentSlots);

        List<?> hudGroups = collect(parent, 2, true);
        List<?> otherGroups = collect(parent, 2);
        Object childGroup = findGroup(hudGroups, child);
        assertThat(childGroup).isNotNull();
        assertThat(containerCount(childGroup)).isEqualTo(2);
        assertThat(contents(childGroup)).singleElement().satisfies(content -> {
            assertThat(content.stack().getItem()).isEqualTo(Items.STONE);
            assertThat(content.count()).isEqualTo(64);
        });
        assertThat(findGroup(otherGroups, child)).isNull();
        Object hudParent = findGroup(hudGroups, parent);
        Object otherParent = findGroup(otherGroups, parent);
        assertThat(hudParent.getClass().getMethod("signature").invoke(hudParent)).isEqualTo(
                otherParent.getClass().getMethod("signature").invoke(otherParent)
        );
    }

    private static List<?> collect(ItemStack parent, int parentCount) throws Exception {
        return collect(parent, parentCount, false);
    }

    private static List<?> collect(ItemStack parent, int parentCount, boolean hudStatistics) throws Exception {
        Class<?> accumulatorType = Class.forName(
                "com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials$GroupAccumulator"
        );
        Constructor<?> constructor = accumulatorType.getDeclaredConstructor(boolean.class);
        constructor.setAccessible(true);
        Object accumulator = constructor.newInstance(hudStatistics);

        Class<?> itemCountType = Class.forName(
                "com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials$ItemCount"
        );
        Constructor<?> itemCountConstructor = itemCountType.getDeclaredConstructor(
                ItemStack.class,
                int.class,
                String.class
        );
        itemCountConstructor.setAccessible(true);
        Object itemCount = itemCountConstructor.newInstance(parent, parentCount, "");

        Method add = QuickLitematicaContainerMaterials.class.getDeclaredMethod(
                "addStoredShulkerGroups",
                accumulatorType,
                String.class,
                List.class,
                int.class,
                int.class
        );
        add.setAccessible(true);
        add.invoke(null, accumulator, "source", List.of(itemCount), 0, 1);

        Method toGroups = accumulatorType.getDeclaredMethod("toGroups");
        toGroups.setAccessible(true);
        return (List<?>) toGroups.invoke(accumulator);
    }

    private static Object findGroup(List<?> groups, ItemStack stack) throws Exception {
        for (Object group : groups) {
            ItemStack container = (ItemStack) group.getClass().getMethod("containerStack").invoke(group);
            if (ItemStack.isSameItemSameComponents(container, stack)) {
                return group;
            }
        }
        return null;
    }

    private static int containerCount(Object group) throws Exception {
        return (int) group.getClass().getMethod("containerCount").invoke(group);
    }

    @SuppressWarnings("unchecked")
    private static List<QuickLitematicaContainerMaterials.ItemCount> contents(Object group) throws Exception {
        return (List<QuickLitematicaContainerMaterials.ItemCount>) group.getClass().getMethod("contents").invoke(group);
    }

    private static ItemStack shulkerWith(List<ItemStack> slots) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        return shulker;
    }

    private static List<ItemStack> slot(int index, ItemStack stack) {
        List<ItemStack> slots = new ArrayList<>(27);
        for (int i = 0; i < 27; i++) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(index, stack);
        return slots;
    }
}
