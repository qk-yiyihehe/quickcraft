package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** BUG-28（26x 轨）：执行器算出的 allowTailDrop 必须传到结束回调的实际 THROW 位置。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftMouseCraftAckTailDropTest {
    @Test
    void unconfirmedAckFinishSkipsTailDropWhileConfirmedFinishDropsOnce() throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        Inventory inventory = newInventory(player);
        CraftingMenu handler = new CraftingMenu(0, inventory);
        handler.getSlot(10).set(new ItemStack(Items.STICK, 3));
        setField(player, "containerMenu", handler);
        doAnswer(invocation -> {
            if (invocation.getArgument(3) == ContainerInput.THROW) {
                handler.getSlot(invocation.getArgument(1)).set(ItemStack.EMPTY);
            }
            return null;
        }).when(gameMode).handleContainerInput(anyInt(), anyInt(), anyInt(),
                any(ContainerInput.class), any(Player.class));
        setField(client, "player", player);
        setField(client, "gameMode", gameMode);

        QuickCraftMouseCrafting crafting = new QuickCraftMouseCrafting(
                QuickCraftMouseCraftLayout.WORKBENCH, () -> true, () -> { });
        setField(crafting, "lockedResultTemplate", new ItemStack(Items.STICK, 3));
        Method finish = QuickCraftMouseCrafting.class.getDeclaredMethod(
                "finishMouseCraftAck", Minecraft.class, Component.class, boolean.class);
        finish.setAccessible(true);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
             MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class)) {
            configs.when(QuickCraftConfigs::isDropCraftResultsOnStopEnabled).thenReturn(true);
            configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
            locks.when(() -> QuickContainerLock.isLockedSlot(any(AbstractContainerMenu.class), anyInt()))
                    .thenReturn(false);

            Component stopMessage = Component.translatable("quickcraft.message.crafting.stopped");
            finish.invoke(crafting, client, stopMessage, false);
            verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                    eq(ContainerInput.THROW), any());
            assertThat(handler.getSlot(10).getItem().getCount()).isEqualTo(3);

            finish.invoke(crafting, client, stopMessage, true);
            verify(gameMode).handleContainerInput(anyInt(), eq(10), eq(1),
                    eq(ContainerInput.THROW), any());
            assertThat(handler.getSlot(10).hasItem()).isFalse();
        }
    }

    @Test
    void awaitingXorCanceledBatchRulesForbidTailDropBeforeCallback() {
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(true, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(false, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(true, true, true)).isTrue();
    }

    private static Inventory newInventory(LocalPlayer player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : Inventory.class.getConstructors()) {
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
