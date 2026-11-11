package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-33 生产路径回归：单次切石成功结束也要执行已开启的“结束后丢出产物”，且只执行一次；
 * 关闭开关或单次失败时不得尾丢。pump 为真实状态机，菜单槽位与点击按原版语义模拟。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftStonecutterTailDropTest {
    @Test
    void successfulSingleCraftRunsEnabledTailDropExactlyOnce() throws Exception {
        Fixture fixture = new Fixture(true);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.interactionManager).clickSlot(anyInt(), eq(2), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.stack(2).isEmpty()).isTrue();
        assertThat(fixture.stack(0).getCount()).isEqualTo(1);
    }

    @Test
    void successfulSingleCraftKeepsOutputWhenTailDropDisabled() throws Exception {
        Fixture fixture = new Fixture(false);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.stack(2).isOf(Items.STONE_SLAB)).isTrue();
    }

    @Test
    void failedSingleCraftDoesNotTailDropEvenWhenEnabled() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.setStack(0, ItemStack.EMPTY);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
    }

    private static final class Fixture {
        private static final int SLOT_COUNT = 38;
        private final boolean tailDropEnabled;
        private final MinecraftClient client = mock(MinecraftClient.class);
        private final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        private final ClientPlayerInteractionManager interactionManager =
                mock(ClientPlayerInteractionManager.class);
        private final ClientWorld world = mock(ClientWorld.class);
        private final ClientPlayNetworkHandler networkHandler = mock(ClientPlayNetworkHandler.class);
        private final StonecutterScreenHandler handler = mock(StonecutterScreenHandler.class);
        private final List<AtomicReference<ItemStack>> stacks = new ArrayList<>();
        private final QuickCraftStonecutter stonecutter = new QuickCraftStonecutter();

        private Fixture(boolean tailDropEnabled) throws Exception {
            this.tailDropEnabled = tailDropEnabled;
            PlayerInventory inventory = newPlayerInventory(player);
            when(player.getInventory()).thenReturn(inventory);
            doNothing().when(player).sendMessage(any(Text.class), anyBoolean());
            installMenu();
            installClicks();
            setField(player, "currentScreenHandler", handler);
            setField(client, "player", player);
            setField(client, "world", world);
            setField(client, "interactionManager", interactionManager);
            setField(client, "currentScreen", mock(StonecutterScreen.class));
            when(client.getNetworkHandler()).thenReturn(networkHandler);
            installState();
        }

        private void installMenu() {
            DefaultedList<Slot> slots = DefaultedList.of();
            for (int index = 0; index < SLOT_COUNT; index++) {
                AtomicReference<ItemStack> reference = new AtomicReference<>(ItemStack.EMPTY);
                stacks.add(reference);
                Slot slot = mock(Slot.class);
                when(slot.getStack()).thenAnswer(invocation -> reference.get());
                when(slot.hasStack()).thenAnswer(invocation -> !reference.get().isEmpty());
                when(slot.getMaxItemCount(any(ItemStack.class))).thenReturn(64);
                when(slot.canInsert(any(ItemStack.class))).thenReturn(true);
                doAnswer(invocation -> {
                    reference.set(invocation.getArgument(0));
                    return null;
                }).when(slot).setStack(any(ItemStack.class));
                slots.add(slot);
            }
            try {
                setField(handler, "slots", slots);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            when(handler.getSlot(anyInt())).thenAnswer(
                    invocation -> slots.get(invocation.getArgument(0)));
            when(handler.getCursorStack()).thenReturn(ItemStack.EMPTY);
            when(handler.getSelectedRecipe()).thenReturn(-1);
            stacks.get(0).set(new ItemStack(Items.STONE, 2));
            stacks.get(1).set(new ItemStack(Items.STONE_SLAB));
        }

        private void installClicks() {
            doAnswer(invocation -> {
                int slotId = invocation.getArgument(1);
                SlotActionType action = invocation.getArgument(3);
                if (action == SlotActionType.THROW) {
                    stacks.get(slotId).set(ItemStack.EMPTY);
                } else if (action == SlotActionType.QUICK_MOVE && slotId == 1) {
                    ItemStack result = stacks.get(1).get().copy();
                    stacks.get(1).set(ItemStack.EMPTY);
                    ItemStack input = stacks.get(0).get();
                    if (!input.isEmpty()) {
                        input.decrement(1);
                    }
                    for (int slot = 2; slot < SLOT_COUNT; slot++) {
                        if (stacks.get(slot).get().isEmpty()) {
                            stacks.get(slot).set(result);
                            break;
                        }
                    }
                }
                return null;
            }).when(interactionManager).clickSlot(anyInt(), anyInt(), anyInt(),
                    any(SlotActionType.class), any(PlayerEntity.class));
        }

        private void installState() throws Exception {
            setField(stonecutter, "sessionHandler", handler);
            setField(stonecutter, "sessionNetwork", networkHandler);
            setField(stonecutter, "singleCraftPending", true);
            setField(stonecutter, "lockedResultTemplate", new ItemStack(Items.STONE_SLAB));
            setField(stonecutter, "lockedInputTemplate", new ItemStack(Items.STONE, 2));
            setField(stonecutter, "predictionRecipeIndex", handler.getSelectedRecipe());
            setField(stonecutter, "pickupWaitTicks", 10);
            setField(stonecutter, "sessionId", 1);
        }

        private void pump() throws Exception {
            try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
                 MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class);
                 MockedStatic<QuickCraftKeyBindings> keys = mockStatic(QuickCraftKeyBindings.class)) {
                configs.when(QuickCraftConfigs::isStonecutterQuickCraftEnabled).thenReturn(true);
                configs.when(QuickCraftConfigs::isDropCraftResultsOnStopEnabled)
                        .thenReturn(tailDropEnabled);
                configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
                locks.when(() -> QuickContainerLock.isLockedSlot(any(ScreenHandler.class), anyInt()))
                        .thenReturn(false);
                keys.when(() -> QuickCraftKeyBindings.isHotkeyDown(any())).thenReturn(false);
                Method pump = QuickCraftStonecutter.class.getDeclaredMethod(
                        "pump", MinecraftClient.class);
                pump.setAccessible(true);
                pump.invoke(stonecutter, client);
            }
        }

        private ItemStack stack(int slotId) {
            return stacks.get(slotId).get();
        }

        private void setStack(int slotId, ItemStack stack) {
            stacks.get(slotId).set(stack);
        }

        private Object field(String name) throws Exception {
            return getField(stonecutter, name);
        }
    }

    private static PlayerInventory newPlayerInventory(ClientPlayerEntity player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : PlayerInventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (PlayerInventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player)
                    && parameters[1].getSimpleName().equals("EntityEquipment")) {
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

    private static Object getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
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
