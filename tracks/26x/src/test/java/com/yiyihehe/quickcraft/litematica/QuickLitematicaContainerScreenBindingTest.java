package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.HopperMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QuickLitematicaContainerScreenBindingTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void staleScreenAndTemporaryScreenTransitionsPreserveNewBindingAndPendingTarget() throws Exception {
        var client = mock(Minecraft.class);
        client.hitResult = null;
        var handler = mock(HopperMenu.class);
        AbstractContainerScreen<?> first = mock(AbstractContainerScreen.class);
        AbstractContainerScreen<?> second = mock(AbstractContainerScreen.class);
        AbstractContainerScreen<?> third = mock(AbstractContainerScreen.class);
        when(first.getMenu()).thenReturn(handler);
        when(second.getMenu()).thenReturn(handler);
        when(third.getMenu()).thenReturn(handler);
        var currentScreen = new AtomicReference<Screen>(first);

        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        try (var minecraft = mockStatic(Minecraft.class);
             var screens = mockStatic(QuickClientScreenAccess.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            screens.when(() -> QuickClientScreenAccess.currentScreen(client))
                    .thenAnswer(invocation -> currentScreen.get());

            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(first);

            currentScreen.set(second);
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(second);
            // 旧屏幕的迟到回调不能覆盖新屏幕绑定。
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(second);

            setStaticField("pendingContainerPos", BlockPos.ZERO);
            setStaticField("currentScreenContainerPos", new BlockPos(1, 2, 3));
            setStaticField("currentScreenContainerInventory", mock(net.minecraft.world.Container.class));
            setStaticField("currentScreenSlotOverlays", java.util.List.of(mock(QuickLitematicaContainerVerifier.SlotOverlay.class)));
            // 聊天等临时子屏不是关屏，不应丢弃新绑定或下一次打开容器的目标。
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(mock(Screen.class));
            assertThat(staticField("currentHandledScreen")).isSameAs(second);
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ZERO);

            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            assertThat(staticField("currentHandledScreen")).isNull();
            assertThat(staticField("currentScreenContainerPos")).isNull();
            assertThat(staticField("currentScreenContainerInventory")).isNull();
            assertThat(staticField("currentScreenSlotOverlays")).isEqualTo(java.util.List.of());
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ZERO);

            currentScreen.set(third);
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(third);
            assertThat(staticField("currentHandledScreen")).isSameAs(third);
            assertThat(staticField("currentScreenContainerPos")).isEqualTo(BlockPos.ZERO);
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
