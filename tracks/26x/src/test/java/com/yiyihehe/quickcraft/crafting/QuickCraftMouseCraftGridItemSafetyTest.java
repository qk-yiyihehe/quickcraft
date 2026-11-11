package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BUG-31（26x 轨）：26.x 客户端只同步配方展示，无法取得可核对的 RecipeHolder；
 * 默认返还推导不再授权 THROW/登记，合成格内非原料物品一律安全收回背包或明确中止。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftMouseCraftGridItemSafetyTest {
    private static final int GRID_START = 1;
    private static final int PLAYER_MAIN_START = 10;
    private static final int HOTBAR_START = 37;

    @Test
    void unrelatedSwordReachesSafeRecoveryThroughSingleCraftEntry() throws Exception {
        // 经 handleSingleCraft 真实入口触发旧计划补料：剑收回背包，不发 THROW。
        Fixture fixture = new Fixture(true);
        fixture.handler.getSlot(GRID_START + 2).set(new ItemStack(Items.IRON_SWORD));
        fixture.setPatternAt(0, new ItemStack(Items.OAK_PLANKS));
        fixture.handleSingleCraft();

        assertThat(fixture.handler.getSlot(GRID_START + 2).hasItem()).isFalse();
        assertThat(fixture.countInPlayerInventory(Items.IRON_SWORD)).isEqualTo(1);
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), eq(1),
                eq(ContainerInput.THROW), any());
    }
    @Test
    void unrelatedGridItemIsReturnedToInventoryInsteadOfThrown() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.handler.getSlot(GRID_START + 2).set(new ItemStack(Items.IRON_SWORD));
        fixture.setPatternAt(0, new ItemStack(Items.OAK_PLANKS));

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.handler.getSlot(GRID_START + 2).hasItem()).isFalse();
        assertThat(fixture.countInPlayerInventory(Items.IRON_SWORD)).isEqualTo(1);
        verify(fixture.gameMode).handleContainerInput(anyInt(), eq(GRID_START + 2), eq(0),
                eq(ContainerInput.QUICK_MOVE), any());
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), eq(1),
                eq(ContainerInput.THROW), any());
    }

    @Test
    void defaultRemainderWithoutAccurateRecipeIsRecoveredNotThrown() throws Exception {
        // 无 RecipeHolder 时，奶桶“默认会返桶”不能作为真实返还证据。
        Fixture fixture = new Fixture(true);
        fixture.handler.getSlot(GRID_START).set(new ItemStack(Items.BUCKET));
        fixture.setPatternAt(0, new ItemStack(Items.MILK_BUCKET));

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), eq(1),
                eq(ContainerInput.THROW), any());
    }

    @Test
    void unrelatedGridItemWithoutInventoryRoomAbortsWithoutThrowing() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.handler.getSlot(GRID_START + 2).set(new ItemStack(Items.IRON_SWORD));
        fixture.setPatternAt(0, new ItemStack(Items.OAK_PLANKS));

        int first = fixture.relocate();
        int second = fixture.relocate();

        assertThat(first).isZero();
        assertThat(second).isZero();
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.THROW), any());
        verify(fixture.player, org.mockito.Mockito.atLeastOnce()).sendOverlayMessage(any(Component.class));
        assertThat((Boolean) fixture.field("unrelatedGridItemNotified")).isTrue();
        assertThat(fixture.handler.getSlot(GRID_START + 2).getItem().is(Items.IRON_SWORD)).isTrue();
    }

    @Test
    void sameItemDifferentComponentsIsNotTreatedAsRecipeRemainder() throws Exception {
        Fixture fixture = new Fixture(true);
        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(DataComponents.CUSTOM_NAME, Component.literal("user bucket"));
        fixture.handler.getSlot(GRID_START).set(customBucket);
        fixture.setPatternAt(0, new ItemStack(Items.MILK_BUCKET));

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.THROW), any());
    }

    private static final class Fixture {
        private final Minecraft client = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        private final Inventory inventory;
        private final CraftingMenu handler;
        private final QuickCraftMouseCrafting crafting;
        private final List<ItemStack> pattern = new ArrayList<>();

        private Fixture(boolean inventoryHasRoom) throws Exception {
            inventory = newInventory(player);
            handler = new CraftingMenu(0, inventory);
            doNothing().when(player).sendOverlayMessage(any(Component.class));
            setField(player, "containerMenu", handler);
            for (int i = 0; i < 9; i++) {
                pattern.add(ItemStack.EMPTY);
            }
            for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
                if (!inventoryHasRoom) {
                    handler.getSlot(slot).set(new ItemStack(Items.DIRT, 64));
                }
            }
            installClicks();
            setField(client, "player", player);
            setField(client, "gameMode", gameMode);
            crafting = new QuickCraftMouseCrafting(
                    QuickCraftMouseCraftLayout.WORKBENCH, () -> true, () -> { });
            setField(crafting, "lockedCraftingPattern", new ArrayList<>(pattern));
            setField(crafting, "lockedResultTemplate", new ItemStack(Items.CRAFTING_TABLE));
        }

        private void setPatternAt(int logicalIndex, ItemStack stack) throws Exception {
            pattern.set(logicalIndex, stack);
            setField(crafting, "lockedCraftingPattern", new ArrayList<>(pattern));
        }


        private void handleSingleCraft() throws Exception {
            setField(client, "level", mock(net.minecraft.client.multiplayer.ClientLevel.class));
            Method method = QuickCraftMouseCrafting.class.getDeclaredMethod(
                    "handleSingleCraft", Minecraft.class, AbstractContainerMenu.class);
            method.setAccessible(true);
            method.invoke(crafting, client, handler);
        }
        private int relocate() throws Exception {
            try (MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class);
                 MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
                locks.when(() -> QuickContainerLock.isLockedSlot(any(AbstractContainerMenu.class), anyInt()))
                        .thenReturn(false);
                configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
                Method relocate = QuickCraftMouseCrafting.class.getDeclaredMethod(
                        "relocateMismatchedGridItems", Minecraft.class, AbstractContainerMenu.class);
                relocate.setAccessible(true);
                return (Integer) relocate.invoke(crafting, client, handler);
            }
        }

        private int countInPlayerInventory(Item item) {
            int total = 0;
            for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
                ItemStack stack = handler.getSlot(slot).getItem();
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        private Object field(String name) throws Exception {
            Field field = findField(QuickCraftMouseCrafting.class, name);
            field.setAccessible(true);
            return field.get(crafting);
        }

        private void installClicks() {
            doAnswer(invocation -> {
                int slotId = invocation.getArgument(1);
                ContainerInput action = invocation.getArgument(3);
                if (action == ContainerInput.QUICK_MOVE) {
                    ItemStack moving = handler.getSlot(slotId).getItem().copy();
                    if (!moving.isEmpty()) {
                        for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
                            if (handler.getSlot(slot).getItem().isEmpty()) {
                                handler.getSlot(slot).set(moving);
                                handler.getSlot(slotId).set(ItemStack.EMPTY);
                                break;
                            }
                        }
                    }
                } else if (action == ContainerInput.THROW) {
                    handler.getSlot(slotId).set(ItemStack.EMPTY);
                }
                return null;
            }).when(gameMode).handleContainerInput(anyInt(), anyInt(), anyInt(),
                    any(ContainerInput.class), any(Player.class));
        }
    }

    private static Inventory newInventory(LocalPlayer player) throws Exception {
        for (Constructor<?> constructor : Inventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (Inventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player)
                    && parameters[1].getSimpleName().equals("EntityEquipment")) {
                Object equipment = parameters[1].getConstructor().newInstance();
                return (Inventory) constructor.newInstance(player, equipment);
            }
        }
        throw new AssertionError("unsupported Inventory constructor");
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
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
