package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEnderChestMaterialsTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void cachesOnlyEnderChestAndKeepsHudCountsSeparateFromInventory() {
        var client = mock(Minecraft.class);
        client.player = mock(LocalPlayer.class);
        client.level = mock(ClientLevel.class);
        var handler = mock(ChestMenu.class);
        when(handler.getRowCount()).thenReturn(3);
        client.player.containerMenu = handler;
        var hit = mock(BlockHitResult.class);
        when(hit.getBlockPos()).thenReturn(BlockPos.ZERO);
        var contents = new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(27, ItemStack.EMPTY));
        contents.set(0, new ItemStack(Items.STONE, 12));
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 20))));
        contents.set(1, shulker);
        var inventory = mock(PlayerEnderChestContainer.class);
        when(inventory.getContainerSize()).thenReturn(27);
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents.get(call.getArgument(0, Integer.class)));
        when(handler.getContainer()).thenReturn(inventory);
        var list = mock(MaterialListBase.class);
        when(list.getMultiplier()).thenReturn(1);
        var source = new MaterialListEntry(new ItemStack(Items.STONE), 100, 100, 0, 4);
        var entries = List.of(source);
        var config = QuickCraftConfigs.ProjectionTools.INCLUDE_ENDER_CHEST_IN_MATERIAL_HUD;
        boolean enabled = config.getBooleanValue();
        config.setBooleanValue(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            when(client.level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CHEST.defaultBlockState());
            QuickLitematicaEnderChestMaterials.rememberContainerUse(client, hit);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.containerId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);

            when(client.level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.ENDER_CHEST.defaultBlockState());
            QuickLitematicaEnderChestMaterials.rememberContainerUse(client, hit);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.containerId);
            for (int pass = 0; pass < 2; pass++) {
                assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries).getFirst().getCountAvailable()).isEqualTo(36);
                assertThat(source.getCountAvailable()).isEqualTo(4);
            }
            config.setBooleanValue(false);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);
            config.setBooleanValue(true);
            contents.set(0, new ItemStack(Items.STONE, 80));
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.containerId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isEmpty();

            // 空箱完整快照必须覆盖旧库存。
            java.util.Collections.fill(contents, ItemStack.EMPTY);
            QuickLitematicaEnderChestMaterials.onContentsReceived(handler.containerId);
            assertThat(QuickLitematicaEnderChestMaterials.hud(list, entries)).isSameAs(entries);
        } finally {
            config.setBooleanValue(enabled);
        }
    }

    @Test
    void readsQuickShulkerSyncWithoutOpeningABlockAndDropsItOnLeave() {
        var client = mock(Minecraft.class);
        client.player = mock(LocalPlayer.class);
        client.level = mock(ClientLevel.class);
        var inventory = new PlayerEnderChestContainer();
        when(client.player.getEnderChestInventory()).thenReturn(inventory);
        inventory.setItem(0, new ItemStack(Items.STONE, 32));
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
            inventory.setItem(0, new ItemStack(Items.STONE, 8));
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
