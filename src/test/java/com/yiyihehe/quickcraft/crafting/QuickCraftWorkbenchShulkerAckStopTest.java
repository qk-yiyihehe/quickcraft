package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BUG-25 生产路径回归：盒喷按钮松开后不得每 tick 早退，等待中的 ACK 批次仍要推进探针与超时清理。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftWorkbenchShulkerAckStopTest {
    @Test
    void releasedButtonStillAdvancesAwaitingAckProbeTimeoutToStop() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("active", true);
        fixture.set("startedByButton", true);
        fixture.set("ackBatchAwaiting", true);
        fixture.set("ackStatsProbePending", true);
        fixture.set("ackStatsProbeSentAtNanos",
                System.nanoTime() - 20_000_000_000L);
        fixture.set("ackStatsProbeNetworkHandler", fixture.networkHandler);

        try (StaticMocks mocks = new StaticMocks(fixture)) {
            fixture.tick();
        }

        assertThat((Boolean) fixture.field("active")).isFalse();
        verify(fixture.player).sendMessage(any(Text.class), anyBoolean());
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                any(SlotActionType.class), any());
    }

    @Test
    void releasedButtonWithoutAwaitingBatchStopsImmediately() throws Exception {
        Fixture fixture = new Fixture();
        fixture.set("active", true);
        fixture.set("startedByButton", true);
        fixture.set("ackBatchAwaiting", false);

        try (StaticMocks mocks = new StaticMocks(fixture)) {
            fixture.tick();
        }

        assertThat((Boolean) fixture.field("active")).isFalse();
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                any(SlotActionType.class), any());
    }

    private static final class Fixture {
        private final MinecraftClient client = mock(MinecraftClient.class);
        private final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        private final ClientPlayerInteractionManager interactionManager =
                mock(ClientPlayerInteractionManager.class);
        private final ClientWorld world = mock(ClientWorld.class);
        private final ClientPlayNetworkHandler networkHandler = mock(ClientPlayNetworkHandler.class);
        private final CraftingScreenHandler screenHandler;
        private final QuickCraftWorkbenchShulkerCraft shulker =
                new QuickCraftWorkbenchShulkerCraft();

        private Fixture() throws Exception {
            PlayerInventory inventory = newPlayerInventory(player);
            screenHandler = new CraftingScreenHandler(0, inventory);
            doNothing().when(player).sendMessage(any(Text.class), anyBoolean());
            setField(player, "currentScreenHandler", screenHandler);
            doReturn(new Box(0, 0, 0, 1, 1, 1)).when(player).getBoundingBox();
            doReturn(false).when(player).hasVehicle();
            doReturn(List.of()).when(world).getEntitiesByClass(any(), any(), any());
            doReturn(networkHandler).when(client).getNetworkHandler();
            setField(client, "player", player);
            setField(client, "world", world);
            setField(client, "interactionManager", interactionManager);
            setField(client, "currentScreen", mock(CraftingScreen.class));
        }

        private void tick() throws Exception {
            Method onTick = QuickCraftWorkbenchShulkerCraft.class.getDeclaredMethod(
                    "onClientTick", MinecraftClient.class);
            onTick.setAccessible(true);
            onTick.invoke(shulker, client);
        }

        private void set(String name, Object value) throws Exception {
            setField(shulker, name, value);
        }

        private Object field(String name) throws Exception {
            return getField(shulker, name);
        }
    }

    private static final class StaticMocks implements AutoCloseable {
        private final MockedStatic<QuickCraftConfigs> configs;
        private final MockedStatic<QuickCraftKeyBindings> keys;
        private final MockedStatic<QuickCraftWorkbenchShulker> helper;
        private final MockedStatic<QuickCraftWorkbenchShulkerContents> contents;

        private StaticMocks(Fixture fixture) {
            configs = mockStatic(QuickCraftConfigs.class);
            keys = mockStatic(QuickCraftKeyBindings.class);
            helper = mockStatic(QuickCraftWorkbenchShulker.class);
            contents = mockStatic(QuickCraftWorkbenchShulkerContents.class);
            configs.when(QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled).thenReturn(true);
            configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
            keys.when(() -> QuickCraftKeyBindings.isHotkeyDown(any())).thenReturn(false);
            keys.when(QuickCraftKeyBindings::isAltDown).thenReturn(false);
            helper.when(QuickCraftWorkbenchShulker::isShulkerCraftBusy).thenReturn(false);
            contents.when(QuickCraftWorkbenchShulkerContents::clear).thenAnswer(invocation -> null);
        }

        @Override
        public void close() {
            contents.close();
            helper.close();
            keys.close();
            configs.close();
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
