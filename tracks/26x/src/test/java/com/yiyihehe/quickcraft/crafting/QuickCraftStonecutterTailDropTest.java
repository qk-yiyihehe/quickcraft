package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-33（26x 轨）：单次切石成功结束也要执行已开启的“结束后丢出产物”，且只执行一次。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftStonecutterTailDropTest {
    @Test
    void successfulSingleCraftRunsEnabledTailDropExactlyOnce() throws Exception {
        Fixture fixture = new Fixture(true);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.gameMode).handleContainerInput(anyInt(), eq(2), eq(1), eq(ContainerInput.THROW), any());
        assertThat(fixture.stack(2).isEmpty()).isTrue();
        assertThat(fixture.stack(0).getCount()).isEqualTo(1);
    }

    @Test
    void successfulSingleCraftKeepsOutputWhenTailDropDisabled() throws Exception {
        Fixture fixture = new Fixture(false);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.gameMode, never()).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                eq(ContainerInput.THROW),
                any()
        );
        assertThat(fixture.stack(2).is(Items.STONE_SLAB)).isTrue();
    }

    @Test
    void failedSingleCraftDoesNotTailDropEvenWhenEnabled() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.setStack(0, ItemStack.EMPTY);

        fixture.pump();

        assertThat((Boolean) fixture.field("singleCraftPending")).isFalse();
        verify(fixture.gameMode, never()).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                eq(ContainerInput.THROW),
                any()
        );
    }

    @Test
    void unlimitedRecyclingRecipeYieldsAndContinuesOnNextTickUntilReleased() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.recycleOutput = true;
        fixture.rapidHeld = true;
        fixture.setStack(0, new ItemStack(Items.STONE));
        fixture.setStack(1, new ItemStack(Items.STONE));
        setField(fixture.stonecutter, "singleCraftPending", false);
        setField(fixture.stonecutter, "rapidCraftingActive", true);
        setField(fixture.stonecutter, "lockedInputTemplate", new ItemStack(Items.STONE));
        setField(fixture.stonecutter, "lockedResultTemplate", new ItemStack(Items.STONE));

        fixture.pump();
        assertThat(fixture.outputClicks).isEqualTo(64);
        assertThat(fixture.field("rapidCraftingActive")).isEqualTo(true);
        assertThat(fixture.field("predicting")).isEqualTo(false);
        fixture.run("onClientTick");
        assertThat(fixture.outputClicks).isEqualTo(128);
        fixture.rapidHeld = false;
        fixture.run("onClientTick");
        assertThat(fixture.outputClicks).isEqualTo(128);
        assertThat(fixture.field("rapidCraftingActive")).isEqualTo(false);
    }

    @Test
    void positiveRateStillWaitsAfterFirstBatch() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.rapidHeld = true;
        fixture.rate = 1;
        setField(fixture.stonecutter, "singleCraftPending", false);
        setField(fixture.stonecutter, "rapidCraftingActive", true);
        fixture.pump();
        fixture.pump();
        assertThat(fixture.outputClicks).isEqualTo(1);
        assertThat(fixture.field("rapidCraftingActive")).isEqualTo(true);
    }

    private static final class Fixture {
        private static final int SLOT_COUNT = 38;
        private final boolean tailDropEnabled;
        private boolean recycleOutput;
        private boolean rapidHeld;
        private int outputClicks;
        private int rate;
        private final Minecraft client = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        private final ClientPacketListener networkHandler = mock(ClientPacketListener.class);
        private final StonecutterScreen screen = mock(StonecutterScreen.class);
        private final StonecutterMenu menu = mock(StonecutterMenu.class);
        private final List<AtomicReference<ItemStack>> stacks = new ArrayList<>();
        private final QuickCraftStonecutter stonecutter = new QuickCraftStonecutter();
        private final Inventory inventory;

        private Fixture(boolean tailDropEnabled) throws Exception {
            this.tailDropEnabled = tailDropEnabled;
            inventory = newInventory(player);
            doNothing().when(player).sendOverlayMessage(any(Component.class));
            installMenu();
            installClicks();
            setField(player, "containerMenu", menu);
            setField(client, "player", player);
            setField(client, "level", mock(net.minecraft.client.multiplayer.ClientLevel.class));
            setField(client, "gameMode", gameMode);
            when(client.getConnection()).thenReturn(networkHandler);
            installState(inventory);
        }

        private void installMenu() {
            NonNullList<Slot> slots = NonNullList.create();
            for (int index = 0; index < SLOT_COUNT; index++) {
                AtomicReference<ItemStack> reference = new AtomicReference<>(ItemStack.EMPTY);
                stacks.add(reference);
                Slot slot = mock(Slot.class);
                when(slot.getItem()).thenAnswer(invocation -> reference.get());
                when(slot.hasItem()).thenAnswer(invocation -> !reference.get().isEmpty());
                when(slot.getMaxStackSize(any(ItemStack.class))).thenReturn(64);
                when(slot.mayPlace(any(ItemStack.class))).thenReturn(true);
                doAnswer(invocation -> {
                    reference.set(invocation.getArgument(0));
                    return null;
                }).when(slot).set(any(ItemStack.class));
                slots.add(slot);
            }
            try {
                setField(menu, "slots", slots);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            when(menu.getSlot(anyInt())).thenAnswer(invocation -> slots.get(invocation.getArgument(0)));
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
            when(menu.getSelectedRecipeIndex()).thenReturn(-1);
            stacks.get(0).set(new ItemStack(Items.STONE, 2));
            stacks.get(1).set(new ItemStack(Items.STONE_SLAB));
        }

        private void installClicks() {
            doAnswer(invocation -> {
                int slotId = invocation.getArgument(1);
                ContainerInput action = invocation.getArgument(3);
                if (recycleOutput && action == ContainerInput.QUICK_MOVE && slotId == 2) {
                    stacks.get(0).set(stacks.get(2).get());
                    stacks.get(1).set(new ItemStack(Items.STONE));
                    stacks.get(2).set(ItemStack.EMPTY);
                    inventory.setItem(9, ItemStack.EMPTY);
                } else if (action == ContainerInput.THROW) {
                    stacks.get(slotId).set(ItemStack.EMPTY);
                } else if (action == ContainerInput.QUICK_MOVE && slotId == 1) {
                    if (++outputClicks > 128) {
                        throw new AssertionError("pump did not yield");
                    }
                    ItemStack result = stacks.get(1).get().copy();
                    stacks.get(1).set(ItemStack.EMPTY);
                    ItemStack input = stacks.get(0).get();
                    if (!input.isEmpty()) {
                        input.shrink(1);
                    }
                    for (int slot = 2; slot < SLOT_COUNT; slot++) {
                        if (stacks.get(slot).get().isEmpty()) {
                            stacks.get(slot).set(result);
                            if (recycleOutput) {
                                inventory.setItem(9, result);
                            }
                            break;
                        }
                    }
                }
                return null;
            }).when(gameMode).handleContainerInput(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    any(ContainerInput.class),
                    any(Player.class)
            );
        }

        private void installState(Inventory inventory) throws Exception {
            when(player.getInventory()).thenReturn(inventory);
            setField(stonecutter, "sessionHandler", menu);
            setField(stonecutter, "sessionNetwork", networkHandler);
            setField(stonecutter, "singleCraftPending", true);
            setField(stonecutter, "lockedResultTemplate", new ItemStack(Items.STONE_SLAB));
            setField(stonecutter, "lockedInputTemplate", new ItemStack(Items.STONE, 2));
            setField(stonecutter, "predictionRecipeIndex", menu.getSelectedRecipeIndex());
            setField(stonecutter, "pickupWaitTicks", 10);
            setField(stonecutter, "sessionId", 1);
        }

        private void pump() throws Exception {
            run("pump");
        }

        private void run(String entry) throws Exception {
            try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
                    MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class);
                    MockedStatic<QuickCraftKeyBindings> keys = mockStatic(QuickCraftKeyBindings.class);
                    MockedStatic<QuickClientScreenAccess> screens = mockStatic(QuickClientScreenAccess.class)) {
                configs.when(QuickCraftConfigs::isStonecutterQuickCraftEnabled).thenReturn(true);
                configs.when(QuickCraftConfigs::isDropCraftResultsOnStopEnabled).thenReturn(tailDropEnabled);
                configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
                configs.when(QuickCraftConfigs::getCraftsPerSecond).thenReturn(rate);
                locks.when(() -> QuickContainerLock.isLockedSlot(any(AbstractContainerMenu.class), anyInt()))
                        .thenReturn(false);
                keys.when(() -> QuickCraftKeyBindings.isHotkeyDown(any())).thenAnswer(ignored -> rapidHeld);
                screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(screen);
                Method pump = QuickCraftStonecutter.class.getDeclaredMethod(entry, Minecraft.class);
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

    private static Inventory newInventory(LocalPlayer player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : Inventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (Inventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player) && parameters[1].getSimpleName().equals(
                    "EntityEquipment"
            )) {
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

    private static Object getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
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
