package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEnderChestMaterialsTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void cachesOnlyEnderChestAndKeepsHudCountsSeparateFromInventory() {
        var client = mock(MinecraftClient.class);
        client.player = mock(ClientPlayerEntity.class);
        client.world = mock(ClientWorld.class);
        var handler = mock(GenericContainerScreenHandler.class);
        when(handler.getRows()).thenReturn(3);
        client.player.currentScreenHandler = handler;
        var hit = mock(BlockHitResult.class);
        when(hit.getBlockPos()).thenReturn(BlockPos.ORIGIN);
        var contents = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(27, ItemStack.EMPTY));
        contents.set(0, new ItemStack(Items.STONE, 12));
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(new ItemStack(Items.STONE, 20))));
        contents.set(1, shulker);
        var inventory = mock(EnderChestInventory.class);
        when(inventory.size()).thenReturn(27);
        when(inventory.getStack(anyInt())).thenAnswer(call -> contents.get(call.getArgument(0, Integer.class)));
        when(handler.getInventory()).thenReturn(inventory);
        var list = mock(MaterialListBase.class);
        when(list.getMultiplier()).thenReturn(1);
        var source = new MaterialListEntry(new ItemStack(Items.STONE), 100, 100, 0, 4);
        var entries = List.of(source);
        var config = QuickCraftConfigs.ProjectionTools.INCLUDE_ENDER_CHEST_IN_MATERIAL_HUD;
        boolean enabled = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            when(client.world.getBlockState(BlockPos.ORIGIN)).thenReturn(Blocks.CHEST.getDefaultState());
            QuickLitematicaEnderChestMaterials.rememberContainerUse(client, hit);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.syncId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);

            when(client.world.getBlockState(BlockPos.ORIGIN)).thenReturn(Blocks.ENDER_CHEST.getDefaultState());
            QuickLitematicaEnderChestMaterials.rememberContainerUse(client, hit);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.syncId);
            for (int pass = 0; pass < 2; pass++) {
                assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries).getFirst().getCountAvailable()).isEqualTo(36);
                assertThat(source.getCountAvailable()).isEqualTo(4);
            }
            config.setBooleanValue(false);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);
            config.setBooleanValue(true);
            contents.set(0, new ItemStack(Items.STONE, 80));
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.syncId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isEmpty();

            // 空箱完整快照必须覆盖旧库存。
            java.util.Collections.fill(contents, ItemStack.EMPTY);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.syncId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);
        } finally {
            config.setBooleanValue(enabled);
        }
    }

    @Test
    void readsQuickShulkerSyncWithoutOpeningABlockAndDropsItOnLeave() {
        var client = mock(MinecraftClient.class);
        client.player = mock(ClientPlayerEntity.class);
        client.world = mock(ClientWorld.class);
        var inventory = new EnderChestInventory();
        when(client.player.getEnderChestInventory()).thenReturn(inventory);
        inventory.setStack(0, new ItemStack(Items.STONE, 32));
        var list = mock(MaterialListBase.class);
        when(list.getMultiplier()).thenReturn(1);
        var source = new MaterialListEntry(new ItemStack(Items.STONE), 100, 100, 0, 4);
        var entries = List.of(source);
        var config = QuickCraftConfigs.ProjectionTools.INCLUDE_ENDER_CHEST_IN_MATERIAL_HUD;
        boolean enabled = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var quickShulker = mockStatic(QuickShulkerConnection.class)) {
            quickShulker.when(QuickShulkerConnection::canOpen).thenReturn(true);
            QuickLitematicaEnderChestMaterials.tick(client);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries).getFirst().getCountAvailable()).isEqualTo(36);
            inventory.setStack(0, new ItemStack(Items.STONE, 8));
            QuickLitematicaEnderChestMaterials.tick(client);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries).getFirst().getCountAvailable()).isEqualTo(12);
            client.player = null;
            QuickLitematicaEnderChestMaterials.tick(client);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);
            assertThat(source.getCountAvailable()).isEqualTo(4);
        } finally {
            config.setBooleanValue(enabled);
        }
    }
}
