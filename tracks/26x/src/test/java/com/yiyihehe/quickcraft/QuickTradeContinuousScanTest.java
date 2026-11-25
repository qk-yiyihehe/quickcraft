package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
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
        Minecraft client = mockClient();
        AbstractVillager near = merchant(NEAR_KEY);
        AbstractVillager far = merchant(FAR_KEY);
        when(
                client.level.getEntities(
                        org.mockito.ArgumentMatchers.<
                                net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                        any(),
                        any()
                )
        ).thenReturn(List.of(near, far));

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            verify(client.gameMode).interact(eq(client.player), eq(near), any(), eq(InteractionHand.MAIN_HAND));

            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }
            verify(client.gameMode).interact(eq(client.player), eq(far), any(), eq(InteractionHand.MAIN_HAND));
        }

        when(
                client.level.getEntities(
                        org.mockito.ArgumentMatchers.<
                                net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                        any(),
                        any()
                )
        ).thenReturn(List.of(near));
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            for (int tick = 0; tick < 130; tick++) {
                new QuickTrade().onClientTick(client);
            }
        }

        verify(client.gameMode, times(2)).interact(eq(client.player), eq(near), any(), eq(InteractionHand.MAIN_HAND));
    }

    @Test
    void turningContinuousTradeOffClearsCooldown() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(NEAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        Minecraft client = mockClient();
        AbstractVillager near = merchant(NEAR_KEY);
        when(
                client.level.getEntities(
                        org.mockito.ArgumentMatchers.<
                                net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                        any(),
                        any()
                )
        ).thenReturn(List.of(near));

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }

            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
            new QuickTrade().onClientTick(client);
        }

        verify(client.gameMode, times(2)).interact(eq(client.player), eq(near), any(), eq(InteractionHand.MAIN_HAND));
    }

    @Test
    void leavingRangeClearsCooldown() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(NEAR_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        Minecraft client = mockClient();
        AbstractVillager near = merchant(NEAR_KEY);
        when(
                client.level.getEntities(
                        org.mockito.ArgumentMatchers.<
                                net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                        any(),
                        any()
                )
        ).thenReturn(List.of(near));

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }

            when(
                    client.level.getEntities(
                            org.mockito.ArgumentMatchers.<
                                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                            any(),
                            any()
                    )
            ).thenReturn(List.of());
            new QuickTrade().onClientTick(client);
            when(
                    client.level.getEntities(
                            org.mockito.ArgumentMatchers.<
                                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                            any(),
                            any()
                    )
            ).thenReturn(List.of(near));
            new QuickTrade().onClientTick(client);
        }

        verify(client.gameMode, times(2)).interact(eq(client.player), eq(near), any(), eq(InteractionHand.MAIN_HAND));
    }

    private static AbstractVillager merchant(String key) {
        AbstractVillager merchant = mock(AbstractVillager.class);
        when(merchant.getStringUUID()).thenReturn(key);
        when(merchant.isRemoved()).thenReturn(false);
        return merchant;
    }

    private static Minecraft mockClient() throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 1, 1));
        when(player.isWithinEntityInteractionRange(any(net.minecraft.world.entity.Entity.class), eq(0.0))).thenReturn(
                true
        );
        QuickTradeTestSupport.setField(client, "player", player);
        QuickTradeTestSupport.setField(client, "level", mock(ClientLevel.class));
        QuickTradeTestSupport.setField(client, "gameMode", mock(MultiPlayerGameMode.class));
        QuickTradeTestSupport.setField(client, "gui", mock(net.minecraft.client.gui.Gui.class));
        QuickTradeTestSupport.setClientOptions(client);
        when(player.distanceToSqr(any(AbstractVillager.class))).thenAnswer(invocation -> {
            AbstractVillager merchant = invocation.getArgument(0);
            return NEAR_KEY.equals(merchant.getStringUUID()) ? 4.0 : 9.0;
        });
        return client;
    }
}
