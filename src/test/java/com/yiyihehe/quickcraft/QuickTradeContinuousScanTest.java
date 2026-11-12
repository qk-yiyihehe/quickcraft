package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-23：最近收藏村民开屏失败时短期退避，不永久占用扫描。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickTradeContinuousScanTest {
    private static final String NEAR_KEY = "near-merchant";
    private static final String FAR_KEY = "far-merchant";

    @AfterEach
    void reset() {
        QuickTradeTestSupport.resetState();
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_FAVORITE_TRADE.setBooleanValue(true);
    }

    @Test
    void blockedNearestTargetLetsSecondTargetRunAndRetriesAfterCooldown() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(NEAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.loadFavorite(FAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
        MinecraftClient client = mockClient();
        MerchantEntity near = merchant(NEAR_KEY);
        MerchantEntity far = merchant(FAR_KEY);
        when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(near, far));

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            verify(client.interactionManager).interactEntity(client.player, near, Hand.MAIN_HAND);

            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }
            verify(client.interactionManager).interactEntity(client.player, far, Hand.MAIN_HAND);
        }

        when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(near));
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            for (int tick = 0; tick < 130; tick++) {
                new QuickTrade().onClientTick(client);
            }
        }

        verify(client.interactionManager, times(2)).interactEntity(client.player, near, Hand.MAIN_HAND);
    }

    @Test
    void turningContinuousTradeOffClearsCooldown() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(NEAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        MinecraftClient client = mockClient();
        MerchantEntity near = merchant(NEAR_KEY);
        when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(near));

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }

            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
            new QuickTrade().onClientTick(client);
        }

        verify(client.interactionManager, times(2)).interactEntity(client.player, near, Hand.MAIN_HAND);
    }

    @Test
    void leavingRangeClearsCooldown() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(NEAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        MinecraftClient client = mockClient();
        MerchantEntity near = merchant(NEAR_KEY);
        when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(near));

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }

            when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of());
            new QuickTrade().onClientTick(client);
            when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(near));
            new QuickTrade().onClientTick(client);
        }

        verify(client.interactionManager, times(2)).interactEntity(client.player, near, Hand.MAIN_HAND);
    }

    private static MerchantEntity merchant(String key) {
        MerchantEntity merchant = mock(MerchantEntity.class);
        when(merchant.getUuidAsString()).thenReturn(key);
        when(merchant.isRemoved()).thenReturn(false);
        return merchant;
    }

    private static MinecraftClient mockClient() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        when(player.getBoundingBox()).thenReturn(new Box(0, 0, 0, 1, 1, 1));
        when(player.canInteractWithEntity(any(), eq(0.0))).thenReturn(true);
        QuickTradeTestSupport.setField(client, "player", player);
        QuickTradeTestSupport.setField(client, "world", mock(ClientWorld.class));
        QuickTradeTestSupport.setField(client, "interactionManager", mock(ClientPlayerInteractionManager.class));
        QuickTradeTestSupport.setClientOptions(client);
        when(player.squaredDistanceTo(any(MerchantEntity.class))).thenAnswer(invocation -> {
            MerchantEntity merchant = invocation.getArgument(0);
            return NEAR_KEY.equals(merchant.getUuidAsString()) ? 4.0 : 9.0;
        });
        return client;
    }
}
