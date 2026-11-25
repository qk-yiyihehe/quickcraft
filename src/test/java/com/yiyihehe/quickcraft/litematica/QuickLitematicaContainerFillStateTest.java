package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import fi.dy.masa.malilib.util.WorldUtils;
import com.yiyihehe.quickcraft.QuickCraftTestInventories;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
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

    @Test
    void normalContainerContentsStillCaptureAfterPendingUse() {
        ClientWorld clientWorld = mock(ClientWorld.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = clientWorld;
        client.player = player;
        BlockPos pos = new BlockPos(1, 64, 2);
        BlockEntity container = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(clientWorld.getBlockEntity(pos)).thenReturn(container);
        when(clientWorld.getBlockState(pos)).thenReturn(net.minecraft.block.Blocks.CHEST.getDefaultState());
        GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x3(
                5,
                QuickCraftTestInventories.newPlayerInventory(player)
        );
        handler.getSlot(0).setStack(new ItemStack(Items.STONE, 2));
        player.currentScreenHandler = handler;
        var config = QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS;
        boolean previous = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var minecraft = mockStatic(MinecraftClient.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifierAccess = mockStatic(QuickLitematicaVerifierAccess.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(clientWorld);
            verifierAccess.when(() -> QuickLitematicaVerifierAccess.getBlockEntityNbtFromStorage(any(BlockPos.class)))
                    .thenReturn(null);
            QuickLitematicaContainerFillState.rememberContainerUse(
                    client,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false)
            );
            QuickLitematicaContainerFillState.onContentsReceived(5);
            assertThat(QuickLitematicaContainerFillState.isFilled(pos)).isTrue();
        } finally {
            config.setBooleanValue(previous);
        }
    }

    @Test
    void cancelledBlockedChestPendingDoesNotCaptureMinecartContents() {
        ClientWorld clientWorld = mock(ClientWorld.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = clientWorld;
        client.player = player;
        BlockPos pos = new BlockPos(1, 64, 2);
        BlockEntity container = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(clientWorld.getBlockEntity(pos)).thenReturn(container);
        var config = QuickCraftConfigs.ProjectionTools.HIGHLIGHT_NONEMPTY_PROJECTION_CONTAINERS;
        boolean previous = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var minecraft = mockStatic(MinecraftClient.class); var worldUtils = mockStatic(WorldUtils.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(clientWorld);
            QuickLitematicaContainerFillState.rememberContainerUse(
                    client,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false)
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
