package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-27：26.x 实体交互临时潜行必须恢复真实输入，原潜行和关闭状态不得发送恢复包。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickFreeCameraEntitySneakTest {
    @AfterEach
    void resetState() throws Exception {
        setBoolean("sneakPlacementCommandSent", false);
        setBoolean("entitySneakingReleasePending", false);
        set("restoredPlayerInput", null);
        set("temporarySneakConnection", null);
        set("activeBlockUseHand", null);
    }

    @Test
    void successfulUseSendsSneakThenRestoresOriginalInput() throws Exception {
        Fixture fixture = fixture(new Input(true, false, false, false, false, false, false));
        try (var configs = enabledConfig();
             var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true);
             var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            assertThat(invokeUse(args -> {
                assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                return InteractionResult.SUCCESS;
            })).isEqualTo(InteractionResult.SUCCESS);

            ArgumentCaptor<ServerboundPlayerInputPacket> packets =
                    ArgumentCaptor.forClass(ServerboundPlayerInputPacket.class);
            verify(fixture.connection, times(2)).send(packets.capture());
            assertThat(packets.getAllValues().get(0).input().shift()).isTrue();
            assertThat(packets.getAllValues().get(1).input()).isEqualTo(fixture.original);
        }
    }

    @Test
    void failurePathRestoresAndRepeatedEndDoesNotSendAgain() throws Exception {
        Fixture fixture = fixture(new Input(false, true, false, false, false, false, false));
        try (var configs = enabledConfig();
             var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true);
             var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            assertThatThrownBy(() -> invokeUse(args -> {
                assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                throw new IllegalStateException("entity interaction failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("entity interaction failed");
            QuickFreeCameraInteractions.endEntityUseFromFreeCamera(fixture.client);

            ArgumentCaptor<ServerboundPlayerInputPacket> packets =
                    ArgumentCaptor.forClass(ServerboundPlayerInputPacket.class);
            verify(fixture.connection, times(2)).send(packets.capture());
            assertThat(packets.getAllValues().get(1).input()).isEqualTo(fixture.original);
        }
    }

    @Test
    void originallySneakingIsPreservedWithoutTemporaryPackets() throws Exception {
        Input sneaking = new Input(false, false, false, false, false, true, false);
        Fixture fixture = fixture(sneaking);
        try (var configs = enabledConfig();
             var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true);
             var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, true)).isTrue();
            QuickFreeCameraInteractions.endEntityUseFromFreeCamera(fixture.client);
            verify(fixture.connection, never()).send(any(ServerboundPlayerInputPacket.class));
        }
    }

    @Test
    void disabledFeatureKeepsOriginalFalseWithoutTemporaryPackets() throws Exception {
        Fixture fixture = fixture(new Input(false, false, false, false, false, false, false));
        try (var configs = enabledConfig();
             var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true);
             var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            configs.when(QuickCraftConfigs::areFreeCameraEntityInteractionsEnabled).thenReturn(false);
            assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isFalse();
            QuickFreeCameraInteractions.endEntityUseFromFreeCamera(fixture.client);
            verify(fixture.connection, never()).send(any(ServerboundPlayerInputPacket.class));
        }
    }

    @Test
    void nestedEntityUseDoesNotRestoreTheOuterInputEarly() throws Exception {
        Fixture fixture = fixture(new Input(false, false, true, false, false, false, false));
        try (var configs = enabledConfig(); var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true); var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            invokeUse(args -> {
                assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                try {
                    invokeUse(inner -> {
                        assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                        return InteractionResult.PASS;
                    });
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                verify(fixture.connection, times(1)).send(any(ServerboundPlayerInputPacket.class));
                return InteractionResult.SUCCESS;
            });
            verify(fixture.connection, times(2)).send(any(ServerboundPlayerInputPacket.class));
        }
    }

    @Test
    void entityUseDoesNotReleaseAnOuterBlockUse() throws Exception {
        Fixture fixture = fixture(new Input(false, false, false, true, false, false, false));
        try (var configs = enabledConfig(); var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true); var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            configs.when(QuickCraftConfigs::areFreeCameraBlockInteractionsEnabled).thenReturn(true);
            QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(fixture.client, InteractionHand.MAIN_HAND, null);
            invokeUse(args -> {
                assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                return InteractionResult.PASS;
            });
            verify(fixture.connection, times(1)).send(any(ServerboundPlayerInputPacket.class));
            QuickFreeCameraInteractions.endBlockUseFromFreeCamera(fixture.client);
            verify(fixture.connection, times(2)).send(any(ServerboundPlayerInputPacket.class));
            assertThat(read("entitySneakingReleasePending")).isEqualTo(false);
        }
    }

    @Test
    void reconnectDoesNotSendTheOldInputToTheNewConnection() throws Exception {
        Fixture fixture = fixture(new Input(false, false, false, false, false, false, false));
        ClientPacketListener replacement = mock(ClientPacketListener.class);
        try (var configs = enabledConfig(); var keys = sneakKey(true);
             var free = freeCamera(fixture.client, true); var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            invokeUse(args -> {
                assertThat(QuickFreeCameraInteractions.resolveEntitySneaking(fixture.client, false)).isTrue();
                when(fixture.client.getConnection()).thenReturn(replacement);
                return InteractionResult.PASS;
            });
            verify(fixture.connection, times(1)).send(any(ServerboundPlayerInputPacket.class));
            verify(replacement, never()).send(any(ServerboundPlayerInputPacket.class));
            assertThat(read("restoredPlayerInput")).isNull();
            assertThat(read("temporarySneakConnection")).isNull();
        }
    }

    private static InteractionResult invokeUse(Operation<InteractionResult> original) {
        return QuickFreeCameraInteractions.withEntityUseFromFreeCamera(
                Minecraft.getInstance(), () -> original.call(null, null, null, InteractionHand.MAIN_HAND));
    }

    private static Object read(String name) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Fixture fixture(Input original) throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        client.player = player;
        setField(Minecraft.class, "options", client, mock(Options.class));
        player.input = new ClientInput();
        player.input.keyPresses = original;
        when(client.getConnection()).thenReturn(connection);
        return new Fixture(client, player, connection, original);
    }

    private static org.mockito.MockedStatic<QuickCraftConfigs> enabledConfig() {
        var configs = mockStatic(QuickCraftConfigs.class);
        configs.when(QuickCraftConfigs::areFreeCameraEntityInteractionsEnabled).thenReturn(true);
        return configs;
    }

    private static org.mockito.MockedStatic<QuickCraftKeyBindings> sneakKey(boolean down) {
        var keys = mockStatic(QuickCraftKeyBindings.class);
        keys.when(() -> QuickCraftKeyBindings.isVanillaKeyDown(any(), any())).thenReturn(down);
        return keys;
    }

    private static org.mockito.MockedStatic<QuickFreeCameraInteractions> freeCamera(
            Minecraft client, boolean active) {
        var free = mockStatic(QuickFreeCameraInteractions.class, CALLS_REAL_METHODS);
        free.when(() -> QuickFreeCameraInteractions.shouldOverrideCrosshair(client)).thenReturn(active);
        return free;
    }

    private static void set(String name, Object value) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setBoolean(String name, boolean value) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static void setField(Class<?> owner, String name, Object target, Object value) throws Exception {
        Field field = owner.getField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(Minecraft client, LocalPlayer player, ClientPacketListener connection, Input original) {
    }
}
