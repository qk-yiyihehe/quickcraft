package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.MerchantScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** BUG-22/24：持续交易关闭后的 pending 取消，以及同长度 offers 替换后的排序映射。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickTradeOrderAndCancelTest {
    private static final String MERCHANT_KEY = "merchant-key";

    @AfterEach
    void reset() {
        QuickTradeTestSupport.resetState();
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_FAVORITE_TRADE.setBooleanValue(true);
    }

    @Test
    void disablingContinuousTradeStopsPendingBeforeLateReply() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));

        Minecraft client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(connection);
        MerchantScreen screen = mockScreen();
        MerchantMenu menu = screen.getMenu();
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(menu.getOffers()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(menu.getSlot(2)).thenReturn(output);
        when(output.hasItem()).thenReturn(false);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
            QuickTradeTestSupport.setScreen(client, screen);
            new QuickTrade().onClientTick(client);
        }

        verify(client.player, never()).closeContainer();
        verify(connection, never()).send(any());
        verify(client.gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void disablingContinuousTradeClosesClaimedMerchantMenuWithoutSelecting() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));

        Minecraft client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getMenu().getOffers()).thenReturn(new MerchantOffers());
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }

        verify(client.player).closeContainer();
    }

    @Test
    void manualBWhileContinuousWaitsForADoesNotCloseB() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.loadFavorite("manual-b", new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));

        Minecraft client = mockClient();
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(connection);
        AbstractVillager merchantA = merchant(MERCHANT_KEY);
        AbstractVillager merchantB = merchant("manual-b");
        startContinuousTask(client, merchantA);

        try (var minecraft = mockStatic(Minecraft.class);
             var keys = mockStatic(QuickCraftKeyBindings.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            keys.when(() -> QuickCraftKeyBindings.isVanillaKeyDown(any(), any())).thenReturn(true, false);
            QuickTradeTestSupport.setField(client, "hitResult", new EntityHitResult(merchantB));
            new QuickTrade().onClientTick(client);
            new QuickTrade().onClientTick(client);

            MerchantScreen screenB = mockScreen();
            MerchantMenu menuB = screenB.getMenu();
            MerchantOffers offers = new MerchantOffers();
            offers.add(offer(Items.EMERALD, Items.APPLE));
            when(menuB.getOffers()).thenReturn(offers);
            QuickTradeTestSupport.setScreen(client, screenB);
            new QuickTrade().onClientTick(client);

            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }

        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "currentScreenMerchantKey"))
                .isEqualTo("manual-b");
        verify(client.player, never()).closeContainer();
        verify(connection, never()).send(any());
    }

    @Test
    void normalQuickTradeStillSelectsAfterContinuousSwitchIsOff() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(true);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.setPending(MERCHANT_KEY, false);

        Minecraft client = mockClient();
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(connection);
        MerchantScreen screen = mockScreen();
        MerchantMenu menu = screen.getMenu();
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(menu.getOffers()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(menu.getSlot(2)).thenReturn(output);
        when(output.hasItem()).thenReturn(false);
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
        }

        ArgumentCaptor<ServerboundSelectTradePacket> packet = ArgumentCaptor.forClass(ServerboundSelectTradePacket.class);
        verify(connection).send(packet.capture());
        assertThat(packet.getValue().getItem()).isZero();
    }

    @Test
    void reenabledContinuousTradeStillProcessesPending() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.setPending(MERCHANT_KEY, true);

        Minecraft client = mockClient();
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(connection);
        MerchantScreen screen = mockScreen();
        MerchantMenu menu = screen.getMenu();
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(menu.getOffers()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(menu.getSlot(2)).thenReturn(output);
        when(output.hasItem()).thenReturn(false);
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
        }

        ArgumentCaptor<ServerboundSelectTradePacket> packet = ArgumentCaptor.forClass(ServerboundSelectTradePacket.class);
        verify(connection).send(packet.capture());
        assertThat(packet.getValue().getItem()).isZero();
    }

    @Test
    void replacingOffersWithSameLengthRebuildsOrderAndSendsMatchingIndex() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
        QuickTradeTestSupport.setCurrentMerchantKey(MERCHANT_KEY);

        Minecraft client = mockClient();
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(client.getConnection()).thenReturn(connection);
        MerchantScreen screen = mockScreen();
        MerchantMenu menu = screen.getMenu();
        MerchantOffers first = new MerchantOffers();
        first.add(offer(Items.EMERALD, Items.BREAD));
        first.add(offer(Items.EMERALD, Items.APPLE));
        when(menu.getOffers()).thenReturn(first);
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(screen);
            assertThat(first.get(0).getResult().getItem()).isEqualTo(Items.APPLE);
            assertThat(first.get(1).getResult().getItem()).isEqualTo(Items.BREAD);

            MerchantOffers replacement = new MerchantOffers();
            replacement.add(offer(Items.EMERALD, Items.BREAD));
            replacement.add(offer(Items.EMERALD, Items.APPLE));
            when(menu.getOffers()).thenReturn(replacement);

            QuickTrade.prepareTradeOrder(screen);
            assertThat(replacement.get(0).getResult().getItem()).isEqualTo(Items.APPLE);
            assertThat(replacement.get(1).getResult().getItem()).isEqualTo(Items.BREAD);

            ((MerchantScreenAccessor) screen).quickcraft$setSelectedIndex(0);
            QuickTrade.syncRecipeIndex(screen);
            QuickTrade.prepareTradeOrder(screen);
            assertThat(replacement.get(0).getResult().getItem()).isEqualTo(Items.APPLE);
        }

        ArgumentCaptor<ServerboundSelectTradePacket> packet = ArgumentCaptor.forClass(ServerboundSelectTradePacket.class);
        verify(connection).send(packet.capture());
        assertThat(packet.getValue().getItem()).isEqualTo(1);
    }

    @Test
    void newScreenDoesNotReusePreviousOrderMapping() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_FAVORITE_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
        QuickTradeTestSupport.setCurrentMerchantKey(MERCHANT_KEY);

        Minecraft client = mockClient();
        MerchantScreen firstScreen = mockScreen();
        MerchantMenu firstMenu = firstScreen.getMenu();
        MerchantOffers first = new MerchantOffers();
        first.add(offer(Items.EMERALD, Items.BREAD));
        first.add(offer(Items.EMERALD, Items.APPLE));
        when(firstMenu.getOffers()).thenReturn(first);
        QuickTradeTestSupport.setScreen(client, firstScreen);

        MerchantScreen secondScreen = mockScreen();
        MerchantMenu secondMenu = secondScreen.getMenu();
        MerchantOffers second = new MerchantOffers();
        second.add(offer(Items.EMERALD, Items.APPLE));
        second.add(offer(Items.EMERALD, Items.BREAD));
        when(secondMenu.getOffers()).thenReturn(second);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(firstScreen);
            QuickTrade.prepareTradeOrder(secondScreen);
        }

        assertThat(second.get(0).getResult().getItem()).isEqualTo(Items.APPLE);
        assertThat(second.get(1).getResult().getItem()).isEqualTo(Items.BREAD);
    }


    @Test
    void initPreparationClaimsContinuousMenuBeforeTick() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        Minecraft client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getMenu().getOffers()).thenReturn(new MerchantOffers());
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(screen);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }
        verify(client.player).closeContainer();
    }

    @Test
    void handlerLossTimeoutReleasesContinuousOwnership() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        Minecraft client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getMenu().getOffers()).thenReturn(new MerchantOffers());
        QuickTradeTestSupport.setScreen(client, screen);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            QuickTradeTestSupport.setScreen(client,
                    mock(net.minecraft.client.gui.screens.Screen.class));
            for (int tick = 0; tick < 41; tick++) new QuickTrade().onClientTick(client);
        }
        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "pendingAutoTrade")).isEqualTo(false);
        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "pendingContinuousTrade")).isEqualTo(false);
        for (String field : List.of("continuousTaskMerchantKey", "continuousClaimedScreen", "continuousClaimedHandler")) {
            assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, field)).as(field).isNull();
        }
        verify(client.player, never()).closeContainer();
    }


    private static Minecraft mockClient() throws Exception {
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 1, 1));
        when(player.isWithinEntityInteractionRange(any(net.minecraft.world.entity.Entity.class), eq(0.0)))
                .thenReturn(true);
        QuickTradeTestSupport.setField(client, "player", player);
        QuickTradeTestSupport.setField(client, "level", mock(ClientLevel.class));
        QuickTradeTestSupport.setField(client, "gameMode", mock(MultiPlayerGameMode.class));
        QuickTradeTestSupport.setField(client, "gui", mock(net.minecraft.client.gui.Gui.class));
        QuickTradeTestSupport.setClientOptions(client);
        return client;
    }

    private static void startContinuousTask(Minecraft client, AbstractVillager merchant) throws Exception {
        when(client.level.getEntities(
                org.mockito.ArgumentMatchers.<net.minecraft.world.level.entity.EntityTypeTest<
                        net.minecraft.world.entity.Entity, AbstractVillager>>any(),
                any(), any())).thenReturn(List.of(merchant));
        when(client.player.distanceToSqr(merchant)).thenReturn(4.0);
        new QuickTrade().onClientTick(client);
        verify(client.gameMode).interact(eq(client.player), eq(merchant), any(), eq(InteractionHand.MAIN_HAND));
    }

    private static AbstractVillager merchant(String key) {
        AbstractVillager merchant = mock(AbstractVillager.class);
        when(merchant.getStringUUID()).thenReturn(key);
        when(merchant.isRemoved()).thenReturn(false);
        return merchant;
    }

    private static MerchantScreen mockScreen() {
        MerchantScreen screen = mock(MerchantScreen.class, withSettings().extraInterfaces(
                HandledScreenAccessor.class, MerchantScreenAccessor.class));
        MerchantMenu menu = mock(MerchantMenu.class);
        when(screen.getMenu()).thenReturn(menu);
        when(((HandledScreenAccessor) screen).quickcraft$getGuiLeft()).thenReturn(0);
        when(((HandledScreenAccessor) screen).quickcraft$getGuiTop()).thenReturn(0);
        when(((MerchantScreenAccessor) screen).quickcraft$getIndexStartOffset()).thenReturn(0);
        when(((MerchantScreenAccessor) screen).quickcraft$getSelectedIndex()).thenReturn(0);
        return screen;
    }

    private static MerchantOffer offer(net.minecraft.world.level.ItemLike first, net.minecraft.world.level.ItemLike sell) {
        return new MerchantOffer(
                new ItemCost(first, 1),
                Optional.empty(),
                new ItemStack(sell),
                12,
                1,
                0.05f
        );
    }
}
