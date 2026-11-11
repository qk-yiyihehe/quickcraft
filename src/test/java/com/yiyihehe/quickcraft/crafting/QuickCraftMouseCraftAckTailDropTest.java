package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BUG-28 生产路径回归：执行器算出的 allowTailDrop 必须真正传到结束回调的 THROW 位置。
 * 未确认批次回调时不得执行“结束后丢出产物”，正常确认完成时仍执行。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftMouseCraftAckTailDropTest {
    @Test
    void unconfirmedAckFinishSkipsTailDropWhileConfirmedFinishDropsOnce() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        CraftingScreenHandler handler = new CraftingScreenHandler(0, playerInventory);
        handler.getSlot(10).setStack(new ItemStack(Items.STICK, 3));
        setField(player, "currentScreenHandler", handler);
        doAnswer(invocation -> {
            if (invocation.getArgument(3) == SlotActionType.THROW) {
                handler.getSlot(invocation.getArgument(1)).setStack(ItemStack.EMPTY);
            }
            return null;
        }).when(interactionManager).clickSlot(anyInt(), anyInt(), anyInt(),
                any(SlotActionType.class), any(PlayerEntity.class));
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);

        QuickCraftMouseCrafting crafting = new QuickCraftMouseCrafting(
                QuickCraftMouseCraftLayout.WORKBENCH, () -> true, () -> { });
        setField(crafting, "lockedResultTemplate", new ItemStack(Items.STICK, 3));
        Method finish = QuickCraftMouseCrafting.class.getDeclaredMethod(
                "finishMouseCraftAck", MinecraftClient.class, Text.class, boolean.class);
        finish.setAccessible(true);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);
             MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class)) {
            configs.when(QuickCraftConfigs::isDropCraftResultsOnStopEnabled).thenReturn(true);
            configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
            locks.when(() -> QuickContainerLock.isLockedSlot(any(ScreenHandler.class), anyInt()))
                    .thenReturn(false);

            Text stopMessage = Text.translatable("quickcraft.message.crafting.stopped");
            finish.invoke(crafting, client, stopMessage, false);
            verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                    eq(SlotActionType.THROW), any());
            assertThat(handler.getSlot(10).getStack().getCount()).isEqualTo(3);

            finish.invoke(crafting, client, stopMessage, true);
            verify(interactionManager).clickSlot(anyInt(), eq(10), eq(1),
                    eq(SlotActionType.THROW), any());
            assertThat(handler.getSlot(10).hasStack()).isFalse();
        }
    }

    @Test
    void awaitingXorCanceledBatchRulesForbidTailDropBeforeCallback() {
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(true, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canDropTail(false, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(true, true, true)).isTrue();
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
