package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
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
        MinecraftClient client = mockClient(new ItemStack(Items.SHULKER_BOX), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isFalse();
        verify(client.interactionManager, never()).clickCreativeStack(any(), anyInt());
    }

    @Test
    void normalItemStillSubmitsBundleWithContents() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(true);
        MinecraftClient client = mockClient(new ItemStack(Items.DIRT, 64), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isTrue();
        ArgumentCaptor<ItemStack> submitted = ArgumentCaptor.forClass(ItemStack.class);
        verify(client.interactionManager).clickCreativeStack(submitted.capture(), anyInt());
        assertThat(submitted.getValue().getItem()).isEqualTo(Items.BUNDLE);
        assertFullDirtBundle(submitted.getValue());
    }

    @Test
    void bundleStopsAtCapacityWithoutEmptyResult() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING.setBooleanValue(true);
        QuickCraftConfigs.ContainerTools.CREATIVE_PACKING_BUNDLE_STACKS.setIntegerValue(2);
        MinecraftClient client = mockClient(new ItemStack(Items.DIRT, 64), new ItemStack(Items.BUNDLE));

        assertThat(QuickCreativePacking.handleHotkey(client)).isTrue();
        ArgumentCaptor<ItemStack> submitted = ArgumentCaptor.forClass(ItemStack.class);
        verify(client.interactionManager).clickCreativeStack(submitted.capture(), anyInt());
        assertFullDirtBundle(submitted.getValue());
    }

    private static void assertFullDirtBundle(ItemStack stack) {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        var contents = stack.get(net.minecraft.component.DataComponentTypes.BUNDLE_CONTENTS);
        assertThat(contents).isNotNull();
        for (ItemStack item : contents.iterate()) {
            items.add(item);
        }
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getItem()).isEqualTo(Items.DIRT);
        assertThat(items.getFirst().getCount()).isEqualTo(64);
    }

    private static MinecraftClient mockClient(ItemStack mainHand, ItemStack offHand) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        when(player.isCreative()).thenReturn(true);
        when(player.getMainHandStack()).thenReturn(mainHand);
        when(player.getOffHandStack()).thenReturn(offHand);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        QuickTradeTestSupport.setField(player, "playerScreenHandler", mock(PlayerScreenHandler.class));
        QuickTradeTestSupport.setField(client, "player", player);
        QuickTradeTestSupport.setField(client, "interactionManager", mock(ClientPlayerInteractionManager.class));
        return client;
    }
}
