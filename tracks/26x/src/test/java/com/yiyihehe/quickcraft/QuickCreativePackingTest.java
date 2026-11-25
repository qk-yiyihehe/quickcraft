package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-38：收纳袋无法装入时不得提交空结果，正常装入仍提交。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCreativePackingTest {
    @AfterEach
    void disable() {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.CREATIVE_PACKING_BUNDLE_STACKS.setIntegerValue(1);
    }

    @Test
    void emptyShulkerCannotEnterBundleAndIsNotSubmitted() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(true);
        Minecraft client = mockClient(new ItemStack(Items.SHULKER_BOX), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isFalse();
        verify(client.gameMode, never()).handleCreativeModeItemAdd(any(), anyInt());
        verify(client.player.getInventory(), never()).setItem(anyInt(), any());
    }

    @Test
    void normalItemStillSubmitsBundleWithContents() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(true);
        Minecraft client = mockClient(new ItemStack(Items.DIRT, 64), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isTrue();
        ArgumentCaptor<ItemStack> submitted = ArgumentCaptor.forClass(ItemStack.class);
        verify(client.gameMode).handleCreativeModeItemAdd(submitted.capture(), anyInt());
        assertThat(submitted.getValue().getItem()).isEqualTo(Items.BUNDLE);
        assertFullDirtBundle(submitted.getValue());
    }

    @Test
    void bundleStopsAtCapacityWithoutEmptyResult() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(true);
        QuickCraftConfigs.ContainerTools.CREATIVE_PACKING_BUNDLE_STACKS.setIntegerValue(2);
        Minecraft client = mockClient(new ItemStack(Items.DIRT, 64), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isTrue();
        ArgumentCaptor<ItemStack> submitted = ArgumentCaptor.forClass(ItemStack.class);
        verify(client.gameMode).handleCreativeModeItemAdd(submitted.capture(), anyInt());
        assertFullDirtBundle(submitted.getValue());
    }

    private static void assertFullDirtBundle(ItemStack stack) {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        var contents = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
        assertThat(contents).isNotNull();
        for (ItemStack item : QuickCreativePackingBundleAccess.itemCopies(contents)) {
            items.add(item);
        }
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getItem()).isEqualTo(Items.DIRT);
        assertThat(items.getFirst().getCount()).isEqualTo(64);
    }

    private static Minecraft mockClient(ItemStack mainHand, ItemStack offHand) throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.hasInfiniteMaterials()).thenReturn(true);
        when(player.getMainHandItem()).thenReturn(mainHand);
        when(player.getOffhandItem()).thenReturn(offHand);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSelectedSlot()).thenReturn(0);
        when(player.getInventory()).thenReturn(inventory);
        QuickTradeTestSupport.setField(player, "inventoryMenu", mock(InventoryMenu.class));
        QuickTradeTestSupport.setField(client, "player", player);
        QuickTradeTestSupport.setField(client, "gameMode", mock(MultiPlayerGameMode.class));
        return client;
    }
}
