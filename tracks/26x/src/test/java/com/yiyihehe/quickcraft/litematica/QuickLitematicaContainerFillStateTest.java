package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerFillStateTest {
    @AfterEach
    void clearWorldState() {
        QuickLitematicaContainerFillState.clear();
    }

    @Test
    void readsInventoryOnServerExecutorAndPublishesBooleanOnClientExecutor() throws Exception {
        ClientLevel clientWorld = mock(ClientLevel.class);
        ServerLevel serverWorld = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Minecraft client = mock(Minecraft.class);
        client.level = clientWorld;
        BlockPos pos = new BlockPos(1, 64, 2);
        BlockEntity clientContainer = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        BlockEntity serverContainer = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        when(clientWorld.getBlockEntity(pos)).thenReturn(clientContainer);
        when(serverWorld.getServer()).thenReturn(server);
        when(serverWorld.hasChunkAt(pos)).thenReturn(true);
        when(serverWorld.getBlockEntity(pos)).thenReturn(serverContainer);
        when(serverWorld.getBlockState(pos)).thenReturn(mock(BlockState.class));
        when(((Container) serverContainer).isEmpty()).thenReturn(false);
        setWorld("cachedWorld", clientWorld);
        setWorld("inventoryWorld", serverWorld);
        var serverTasks = new ArrayList<Runnable>();
        var clientTasks = new ArrayList<Runnable>();
        doAnswer(call -> {
            serverTasks.add(call.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        doAnswer(call -> {
            clientTasks.add(call.getArgument(0));
            return null;
        }).when(client).execute(any(Runnable.class));

        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        verify(serverWorld, never()).getBlockEntity(pos);
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
        serverTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isTrue();
        verify(serverWorld, times(1)).getBlockEntity(pos);

        when(((Container) serverContainer).isEmpty()).thenReturn(true);
        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        serverTasks.removeFirst().run();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();

        when(((Container) serverContainer).isEmpty()).thenReturn(false);
        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        serverTasks.removeFirst().run();
        QuickLitematicaContainerFillState.clear();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
    }

    private static void setWorld(String name, Level world) throws ReflectiveOperationException {
        var field = QuickLitematicaContainerFillState.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, world);
    }

    @Test
    void cancelledBlockedChestPendingDoesNotCaptureMinecartContents() {
        ClientLevel clientLevel = mock(ClientLevel.class);
        LocalPlayer player = mock(LocalPlayer.class);
        Minecraft client = mock(Minecraft.class);
        client.level = clientLevel;
        client.player = player;
        BlockPos pos = new BlockPos(1, 64, 2);
        BlockEntity container = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        when(clientLevel.getBlockEntity(pos)).thenReturn(container);
        var config = QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS;
        boolean previous = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var minecraft = mockStatic(Minecraft.class); var worldUtils = mockStatic(WorldUtils.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(clientLevel);
            QuickLitematicaContainerFillState.rememberContainerUse(
                    client,
                    new BlockHitResult(new Vec3(1.5, 64.5, 2.5), Direction.NORTH, pos, false)
            );
            QuickLitematicaContainerVerifier.clearPendingContainerUses();
            QuickLitematicaContainerFillState.onContentsReceived(5);
            assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
        } finally {
            config.setBooleanValue(previous);
        }
    }

    @ParameterizedTest
    @CsvSource(
            value = { "true, true, true", "true, false, true", "true, unknown, true", "false, true, true",
                    "false, false, false", "false, unknown, unknown", "unknown, true, true", "unknown, false, unknown",
                    "unknown, unknown, unknown" },
            nullValues = "unknown")
    void doubleChestRequiresBothKnownEmptyOrEitherNonempty(Boolean first, Boolean second, Boolean expected) {
        assertThat(QuickLitematicaContainerFillState.combine(first, second)).isEqualTo(expected);
    }
}
