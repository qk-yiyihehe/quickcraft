package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-25（26x 轨）：盒喷按钮松开后等待中的 ACK 批次仍要推进探针与超时清理。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftWorkbenchShulkerAckStopTest {
    @Test
    void releasedButtonStillAdvancesAwaitingAckProbeTimeoutToStop() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("active", true);
        fixture.set("startedByButton", true);
        fixture.set("ackBatchAwaiting", true);
        fixture.set("ackStatsProbePending", true);
        fixture.set("ackStatsProbeSentAtNanos", System.nanoTime() - 20_000_000_000L);
        fixture.set("ackStatsProbeNetworkHandler", fixture.networkHandler);

        fixture.tick();

        assertThat((Boolean) fixture.field("active")).isFalse();
        verify(fixture.player).sendOverlayMessage(any(Component.class));
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                any(ContainerInput.class), any());
    }

    @Test
    void releasedButtonWithoutAwaitingBatchStopsImmediately() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("active", true);
        fixture.set("startedByButton", true);
        fixture.set("ackBatchAwaiting", false);

        fixture.tick();

        assertThat((Boolean) fixture.field("active")).isFalse();
        verify(fixture.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                any(ContainerInput.class), any());
    }

    private static final class Fixture {
        private final Minecraft client = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        private final ClientLevel level = mock(ClientLevel.class);
        private final ClientPacketListener networkHandler = mock(ClientPacketListener.class);
        private final CraftingScreen screen = mock(CraftingScreen.class);
        private final Inventory inventory;
        private final CraftingMenu menu;
        private final QuickCraftWorkbenchShulkerCraft shulker =
                new QuickCraftWorkbenchShulkerCraft();

        private Fixture() throws Exception {
            inventory = newInventory(player);
            menu = new CraftingMenu(0, inventory);
            doNothing().when(player).sendOverlayMessage(any(Component.class));
            setField(player, "containerMenu", menu);
            doReturn(new AABB(0, 0, 0, 1, 1, 1)).when(player).getBoundingBox();
            doReturn(false).when(player).isPassenger();
            doReturn(List.of()).when(level).getEntitiesOfClass(any(), any(), any());
            when(client.getConnection()).thenReturn(networkHandler);
            setField(client, "player", player);
            setField(client, "level", level);
            setField(client, "gameMode", gameMode);
        }

        private void tick() throws Exception {
            Method onTick = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "onClientTick", Minecraft.class);
            onTick.setAccessible(true);
            try (MockedStatic<QuickClientScreenAccess> screens = mockStatic(QuickClientScreenAccess.class);
                 MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
                 MockedStatic<QuickCraftKeyBindings> keys = mockStatic(QuickCraftKeyBindings.class);
                 MockedStatic<QuickCraftWorkbenchShulker> helper = mockStatic(QuickCraftWorkbenchShulker.class);
                 MockedStatic<QuickCraftWorkbenchShulkerContents> contents =
                         mockStatic(QuickCraftWorkbenchShulkerContents.class)) {
                screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(screen);
                configs.when(QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled).thenReturn(true);
                configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
                keys.when(() -> QuickCraftKeyBindings.isHotkeyDown(any())).thenReturn(false);
                keys.when(QuickCraftKeyBindings::isAltDown).thenReturn(false);
                helper.when(QuickCraftWorkbenchShulker::isShulkerCraftBusy).thenReturn(false);
                contents.when(QuickCraftWorkbenchShulkerContents::clear).thenAnswer(invocation -> null);
                onTick.invoke(shulker, client);
            }
        }

        private void set(String name, Object value) throws Exception {
            setField(shulker, name, value);
        }

        private Object field(String name) throws Exception {
            return getField(shulker, name);
        }
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
