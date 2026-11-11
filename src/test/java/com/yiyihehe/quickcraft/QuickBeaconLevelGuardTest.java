package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** BUG-32 / DS-CFG-01 / JT-R1：低层信标在支付与发包前停止，零层等待有限超时，四层目标保持。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickBeaconLevelGuardTest {
    private static final int OPEN_TIMEOUT_TICKS = 20;

    private MinecraftClient client;
    private ClientPlayerEntity player;
    private ClientPlayNetworkHandler networkHandler;
    private HandledScreen<?> screen;
    private BeaconScreenHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(MinecraftClient.class);
        player = mock(ClientPlayerEntity.class);
        networkHandler = mock(ClientPlayNetworkHandler.class);
        screen = mock(HandledScreen.class);
        handler = mock(BeaconScreenHandler.class);

        client.player = player;
        client.currentScreen = screen;
        client.interactionManager = mock(ClientPlayerInteractionManager.class);
        when(client.getNetworkHandler()).thenReturn(networkHandler);
        when(screen.getScreenHandler()).thenReturn(handler);
        resetPending(0);
    }

    @Test
    @DisplayName("持续零层超过窗口：有限退出，零支付零发包")
    void zeroLevelPendingExitsAfterTimeout() throws Exception {
        when(handler.getProperties()).thenReturn(0);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            for (int tick = 0; tick < OPEN_TIMEOUT_TICKS; tick++) {
                invokeProcessPendingState();
            }
            verify(player, never()).sendMessage(any(Text.class), anyBoolean());
            verify(player, never()).closeHandledScreen();
            assertThat(pendingStageName()).isEqualTo("WAIT_INITIAL_OPEN");

            invokeProcessPendingState();
        }

        verify(player).sendMessage(any(Text.class), anyBoolean());
        verify(player).closeHandledScreen();
        verifyNoInteractions(networkHandler);
        assertThat(pendingStageName()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("零层在窗口内同步成四层：正常继续并只发包一次")
    void zeroToFourWithinWindowContinues() throws Exception {
        when(handler.getProperties()).thenReturn(0).thenReturn(4);
        when(handler.getCursorStack()).thenReturn(ItemStack.EMPTY);
        Slot paymentSlot = mock(Slot.class);
        when(paymentSlot.hasStack()).thenReturn(true);
        when(handler.getSlot(0)).thenReturn(paymentSlot);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            invokeProcessPendingState();
        }
        assertThat(pendingStageName()).isEqualTo("WAIT_INITIAL_OPEN");

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            invokeProcessPendingState();
        }

        verify(networkHandler).sendPacket(any());
        verify(player).closeHandledScreen();
        verify(player, never()).sendMessage(any(Text.class), anyBoolean());
        assertThat(pendingStageName()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("零层在窗口内同步成一至三层：提示停止，零支付零发包")
    void zeroToLowLevelWithinWindowStops() throws Exception {
        when(handler.getProperties()).thenReturn(0).thenReturn(2);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            invokeProcessPendingState();
            invokeProcessPendingState();
        }

        verify(player).sendMessage(any(Text.class), anyBoolean());
        verify(player).closeHandledScreen();
        verifyNoInteractions(networkHandler);
        assertThat(pendingStageName()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("三层信标：提示层数不足并关闭界面，不支付也不发包")
    void lowLevelBeaconStopsBeforePaymentAndPacket() throws Exception {
        when(handler.getProperties()).thenReturn(3);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            invokeProcessPendingState();
        }

        verify(player).sendMessage(any(Text.class), anyBoolean());
        verify(player).closeHandledScreen();
        verifyNoInteractions(networkHandler);
        assertThat(pendingStageName()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("四层信标：保持原有 II 级目标，支付后发包")
    void fourLevelBeaconStillSendsTarget() throws Exception {
        when(handler.getProperties()).thenReturn(4);
        when(handler.getCursorStack()).thenReturn(ItemStack.EMPTY);
        Slot paymentSlot = mock(Slot.class);
        when(paymentSlot.hasStack()).thenReturn(true);
        when(handler.getSlot(0)).thenReturn(paymentSlot);

        try (MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::getBeaconEffectOrderStrings).thenReturn(List.of("急迫2"));
            invokeProcessPendingState();
        }

        verify(networkHandler).sendPacket(any());
        verify(player).closeHandledScreen();
        verify(player, never()).sendMessage(any(Text.class), anyBoolean());
        assertThat(pendingStageName()).isEqualTo("NONE");
    }

    private void invokeProcessPendingState() throws Exception {
        Method method = QuickBeacon.class.getDeclaredMethod("processPendingState", MinecraftClient.class);
        method.setAccessible(true);
        method.invoke(new QuickBeacon(), client);
    }

    private static void resetPending(int pendingTicks) throws Exception {
        Method clear = QuickBeacon.class.getDeclaredMethod("clearPendingState");
        clear.setAccessible(true);
        clear.invoke(null);
        setPendingStage("WAIT_INITIAL_OPEN");
        setPendingTicks(pendingTicks);
    }

    private static String pendingStageName() throws Exception {
        Field field = QuickBeacon.class.getDeclaredField("pendingStage");
        field.setAccessible(true);
        return String.valueOf(field.get(null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setPendingStage(String name) throws Exception {
        Field field = QuickBeacon.class.getDeclaredField("pendingStage");
        field.setAccessible(true);
        field.set(null, Enum.valueOf((Class<? extends Enum>) field.getType(), name));
    }

    private static void setPendingTicks(int ticks) throws Exception {
        Field field = QuickBeacon.class.getDeclaredField("pendingTicks");
        field.setAccessible(true);
        field.set(null, ticks);
    }
}
