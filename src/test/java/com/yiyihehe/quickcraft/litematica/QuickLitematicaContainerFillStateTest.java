package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class QuickLitematicaContainerFillStateTest {
    @AfterEach
    void clearWorldState() {
        QuickLitematicaContainerFillState.clear();
    }

    @Test
    void readsInventoryOnServerExecutorAndPublishesBooleanOnClientExecutor() throws Exception {
        ClientWorld clientWorld = mock(ClientWorld.class);
        ServerWorld serverWorld = mock(ServerWorld.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = clientWorld;
        BlockPos pos = new BlockPos(1, 64, 2);
        BlockEntity clientContainer = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        BlockEntity serverContainer = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(clientWorld.getBlockEntity(pos)).thenReturn(clientContainer);
        when(serverWorld.getServer()).thenReturn(server);
        when(serverWorld.isChunkLoaded(pos)).thenReturn(true);
        when(serverWorld.getBlockEntity(pos)).thenReturn(serverContainer);
        when(serverWorld.getBlockState(pos)).thenReturn(mock(BlockState.class));
        when(((Inventory) serverContainer).isEmpty()).thenReturn(false);
        setWorld("cachedWorld", clientWorld);
        setWorld("inventoryWorld", serverWorld);
        var serverTasks = new ArrayList<Runnable>();
        var clientTasks = new ArrayList<Runnable>();
        doAnswer(call -> { serverTasks.add(call.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
        doAnswer(call -> { clientTasks.add(call.getArgument(0)); return null; }).when(client).execute(any(Runnable.class));

        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        verify(serverWorld, never()).getBlockEntity(pos);
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
        serverTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isTrue();
        verify(serverWorld, times(1)).getBlockEntity(pos);

        when(((Inventory) serverContainer).isEmpty()).thenReturn(true);
        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        serverTasks.removeFirst().run();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();

        when(((Inventory) serverContainer).isEmpty()).thenReturn(false);
        QuickLitematicaContainerFillState.requestIntegratedInventory(client, serverWorld, List.of(pos));
        serverTasks.removeFirst().run();
        QuickLitematicaContainerFillState.clear();
        clientTasks.removeFirst().run();
        assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isFalse();
    }

    private static void setWorld(String name, World world) throws ReflectiveOperationException {
        var field = QuickLitematicaContainerFillState.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, world);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "true, true, true",
            "true, false, true",
            "true, unknown, true",
            "false, true, true",
            "false, false, false",
            "false, unknown, unknown",
            "unknown, true, true",
            "unknown, false, unknown",
            "unknown, unknown, unknown"
    }, nullValues = "unknown")
    void doubleChestRequiresBothKnownEmptyOrEitherNonempty(Boolean first, Boolean second, Boolean expected) {
        assertThat(QuickLitematicaContainerFillState.combine(first, second)).isEqualTo(expected);
    }
}
