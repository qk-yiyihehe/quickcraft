package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DS-CRF-04 生产路径回归：盒喷返还物必须按物品与组件身份比较。
 * 场景：已捕获返还物配方快照后停止（同菜单快照保留），把对应格换成同 Item 不同组件物品，
 * 空光标重启/继续时不得把该用户物品当作返还物整组丢出。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftWorkbenchShulkerRemainderComponentTest {
    @Test
    void changedComponentRemainderSlotIsNotDiscardedButRealRemainderStillIs() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("recipeHasRemainder", true);
        fixture.set("remainderPattern", remainderPatternOf(Items.BUCKET));
        fixture.set("pattern", ingredientPatternOf(Items.MILK_BUCKET));

        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(DataComponentTypes.CUSTOM_NAME, Text.literal("user bucket"));
        fixture.handler.getSlot(1).setStack(customBucket);
        int discarded = fixture.discardGridRemainders();
        assertThat(discarded).isZero();
        assertThat(fixture.handler.getSlot(1).getStack().isOf(Items.BUCKET)).isTrue();
        verify(fixture.interactionManager, never()).clickSlot(
                anyInt(),
                anyInt(),
                anyInt(),
                any(SlotActionType.class),
                any()
        );

        fixture.handler.getSlot(1).setStack(new ItemStack(Items.BUCKET));
        int genuine = fixture.discardGridRemainders();
        assertThat(genuine).isEqualTo(1);
        assertThat(fixture.handler.getSlot(1).hasStack()).isFalse();
        verify(fixture.interactionManager).clickSlot(anyInt(), eq(1), eq(1), eq(SlotActionType.THROW), any());
    }

    @Test
    void outputBoxSlotWithSameItemDifferentComponentsIsNotDiscarded() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("recipeHasRemainder", true);
        fixture.set("remainderPattern", remainderPatternOf(Items.BUCKET));
        fixture.set("pattern", ingredientPatternOf(Items.MILK_BUCKET));

        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(DataComponentTypes.CUSTOM_NAME, Text.literal("user bucket"));
        fixture.handler.getSlot(10).setStack(customBucket);

        boolean returned = fixture.returnOutputBox(10);

        assertThat(returned).isFalse();
        assertThat(fixture.handler.getSlot(10).getStack().isOf(Items.BUCKET)).isTrue();
        verify(fixture.interactionManager, never()).clickSlot(
                anyInt(),
                anyInt(),
                anyInt(),
                any(SlotActionType.class),
                any()
        );
    }

    private static List<ItemStack> remainderPatternOf(net.minecraft.item.Item item) {
        List<ItemStack> pattern = new ArrayList<>();
        pattern.add(new ItemStack(item));
        for (int i = 1; i < 9; i++) {
            pattern.add(ItemStack.EMPTY);
        }
        return pattern;
    }

    private static List<ItemStack> ingredientPatternOf(net.minecraft.item.Item item) {
        List<ItemStack> pattern = new ArrayList<>();
        pattern.add(new ItemStack(item));
        for (int i = 1; i < 9; i++) {
            pattern.add(ItemStack.EMPTY);
        }
        return pattern;
    }

    private static final class Fixture {
        private final MinecraftClient client = mock(MinecraftClient.class);
        private final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        private final ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        private final CraftingScreenHandler handler;
        private final QuickCraftWorkbenchShulkerCraft shulker = new QuickCraftWorkbenchShulkerCraft();

        private Fixture() throws Exception {
            PlayerInventory inventory = newPlayerInventory(player);
            handler = new CraftingScreenHandler(0, inventory);
            doAnswer(invocation -> {
                if (invocation.getArgument(3) == SlotActionType.THROW) {
                    handler.getSlot(invocation.getArgument(1)).setStack(ItemStack.EMPTY);
                }
                return null;
            }).when(interactionManager).clickSlot(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    any(SlotActionType.class),
                    any(PlayerEntity.class)
            );
            setField(client, "player", player);
            setField(client, "interactionManager", interactionManager);
        }

        private int discardGridRemainders() throws Exception {
            Method method = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "discardGridRemainders",
                    MinecraftClient.class,
                    CraftingScreenHandler.class
            );
            method.setAccessible(true);
            return (Integer) method.invoke(shulker, client, handler);
        }

        private boolean returnOutputBox(int sourceSlotId) throws Exception {
            Method method = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "returnOutputBox",
                    MinecraftClient.class,
                    CraftingScreenHandler.class,
                    int.class
            );
            method.setAccessible(true);
            return (Boolean) method.invoke(shulker, client, handler, sourceSlotId);
        }

        private void set(String name, Object value) throws Exception {
            setField(shulker, name, value);
        }
    }

    private static PlayerInventory newPlayerInventory(ClientPlayerEntity player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : PlayerInventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (PlayerInventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player) && parameters[1].getSimpleName().equals(
                    "EntityEquipment"
            )) {
                Object equipment = parameters[1].getConstructor().newInstance();
                return (PlayerInventory) constructor.newInstance(player, equipment);
            }
        }
        throw new AssertionError("unsupported PlayerInventory constructor");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
