package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QuickLitematicaContainerScreenBindingTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void staleScreenAndTemporaryScreenTransitionsPreserveNewBindingAndPendingTarget() throws Exception {
        var client = mock(MinecraftClient.class);
        client.crosshairTarget = null;
        var handler = mock(GenericContainerScreenHandler.class);
        HandledScreen<?> first = mock(HandledScreen.class);
        HandledScreen<?> second = mock(HandledScreen.class);
        HandledScreen<?> third = mock(HandledScreen.class);
        when(first.getScreenHandler()).thenReturn(handler);
        when(second.getScreenHandler()).thenReturn(handler);
        when(third.getScreenHandler()).thenReturn(handler);

        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);

            client.currentScreen = first;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(first);

            client.currentScreen = second;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(second);
            // 旧屏幕的迟到回调不能覆盖新屏幕绑定。
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(second);

            setStaticField("pendingContainerPos", BlockPos.ORIGIN);
            setStaticField("currentScreenContainerPos", new BlockPos(1, 2, 3));
            setStaticField("currentScreenContainerInventory", mock(net.minecraft.inventory.Inventory.class));
            setStaticField("currentScreenSlotOverlays", java.util.List.of(mock(QuickLitematicaContainerVerifier.SlotOverlay.class)));
            // 聊天等临时子屏不是关屏，不应丢弃新绑定或下一次打开容器的目标。
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(mock(Screen.class));
            assertThat(staticField("currentHandledScreen")).isSameAs(second);
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ORIGIN);

            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            assertThat(staticField("currentHandledScreen")).isNull();
            assertThat(staticField("currentScreenContainerPos")).isNull();
            assertThat(staticField("currentScreenContainerInventory")).isNull();
            assertThat(staticField("currentScreenSlotOverlays")).isEqualTo(java.util.List.of());
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ORIGIN);

            client.currentScreen = third;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(third);
            assertThat(staticField("currentHandledScreen")).isSameAs(third);
            assertThat(staticField("currentScreenContainerPos")).isEqualTo(BlockPos.ORIGIN);
            assertThat(staticField("pendingContainerPos")).isNull();
        } finally {
            QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        }
    }

    private static Object staticField(String name) throws Exception {
        Field field = QuickLitematicaContainerScreenBinding.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = QuickLitematicaContainerScreenBinding.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
