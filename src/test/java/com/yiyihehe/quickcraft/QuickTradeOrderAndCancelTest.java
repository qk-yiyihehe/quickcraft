package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.MerchantScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
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

        MinecraftClient client = mockClient();
        MerchantEntity merchant = merchant(MERCHANT_KEY);
        startContinuousTask(client, merchant);
        ClientPlayNetworkHandler network = mock(ClientPlayNetworkHandler.class);
        when(client.getNetworkHandler()).thenReturn(network);
        MerchantScreen screen = mockScreen();
        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(handler.getRecipes()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(handler.getSlot(2)).thenReturn(output);
        when(output.hasStack()).thenReturn(false);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
            QuickTradeTestSupport.setField(client, "currentScreen", screen);
            new QuickTrade().onClientTick(client);
        }

        verify(client.player, never()).closeHandledScreen();
        verify(network, never()).sendPacket(any());
        verify(client.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void disablingContinuousTradeClosesClaimedMerchantMenuWithoutSelecting() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));

        MinecraftClient client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getScreenHandler().getRecipes()).thenReturn(new TradeOfferList());
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }

        verify(client.player).closeHandledScreen();
    }

    @Test
    void manualBWhileContinuousWaitsForADoesNotCloseB() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.loadFavorite("manual-b", new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));

        MinecraftClient client = mockClient();
        ClientPlayNetworkHandler network = mock(ClientPlayNetworkHandler.class);
        when(client.getNetworkHandler()).thenReturn(network);
        MerchantEntity merchantA = merchant(MERCHANT_KEY);
        MerchantEntity merchantB = merchant("manual-b");
        startContinuousTask(client, merchantA);

        try (var minecraft = mockStatic(MinecraftClient.class); var keys = mockStatic(QuickCraftKeyBindings.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            keys.when(() -> QuickCraftKeyBindings.isVanillaKeyDown(any(), any())).thenReturn(true, false);
            QuickTradeTestSupport.setField(client, "crosshairTarget", new EntityHitResult(merchantB));
            new QuickTrade().onClientTick(client);
            new QuickTrade().onClientTick(client);

            MerchantScreen screenB = mockScreen();
            MerchantScreenHandler handlerB = screenB.getScreenHandler();
            TradeOfferList offers = new TradeOfferList();
            offers.add(offer(Items.EMERALD, Items.APPLE));
            when(handlerB.getRecipes()).thenReturn(offers);
            QuickTradeTestSupport.setField(client, "currentScreen", screenB);
            new QuickTrade().onClientTick(client);

            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }

        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "currentScreenMerchantKey")).isEqualTo(
                "manual-b"
        );
        verify(client.player, never()).closeHandledScreen();
        verify(network, never()).sendPacket(any());
    }

    @Test
    void normalQuickTradeStillSelectsAfterContinuousSwitchIsOff() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(true);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.setPending(MERCHANT_KEY, false);

        MinecraftClient client = mockClient();
        ClientPlayNetworkHandler network = mock(ClientPlayNetworkHandler.class);
        when(client.getNetworkHandler()).thenReturn(network);
        MerchantScreen screen = mockScreen();
        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(handler.getRecipes()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(handler.getSlot(2)).thenReturn(output);
        when(output.hasStack()).thenReturn(false);
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
        }

        ArgumentCaptor<SelectMerchantTradeC2SPacket> packet = ArgumentCaptor.forClass(
                SelectMerchantTradeC2SPacket.class
        );
        verify(network).sendPacket(packet.capture());
        assertThat(packet.getValue().getTradeId()).isZero();
    }

    @Test
    void reenabledContinuousTradeStillProcessesPending() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        QuickTradeTestSupport.setPending(MERCHANT_KEY, true);

        MinecraftClient client = mockClient();
        ClientPlayNetworkHandler network = mock(ClientPlayNetworkHandler.class);
        when(client.getNetworkHandler()).thenReturn(network);
        MerchantScreen screen = mockScreen();
        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.EMERALD, Items.BREAD));
        when(handler.getRecipes()).thenReturn(offers);
        Slot output = mock(Slot.class);
        when(handler.getSlot(2)).thenReturn(output);
        when(output.hasStack()).thenReturn(false);
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
        }

        ArgumentCaptor<SelectMerchantTradeC2SPacket> packet = ArgumentCaptor.forClass(
                SelectMerchantTradeC2SPacket.class
        );
        verify(network).sendPacket(packet.capture());
        assertThat(packet.getValue().getTradeId()).isZero();
    }

    @Test
    void replacingOffersWithSameLengthRebuildsOrderAndSendsMatchingIndex() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
        QuickTradeTestSupport.setCurrentMerchantKey(MERCHANT_KEY);

        MinecraftClient client = mockClient();
        ClientPlayNetworkHandler network = mock(ClientPlayNetworkHandler.class);
        when(client.getNetworkHandler()).thenReturn(network);
        MerchantScreen screen = mockScreen();
        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList first = new TradeOfferList();
        first.add(offer(Items.EMERALD, Items.BREAD));
        first.add(offer(Items.EMERALD, Items.APPLE));
        when(handler.getRecipes()).thenReturn(first);
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(screen);
            assertThat(first.get(0).getSellItem().getItem()).isEqualTo(Items.APPLE);
            assertThat(first.get(1).getSellItem().getItem()).isEqualTo(Items.BREAD);

            TradeOfferList replacement = new TradeOfferList();
            replacement.add(offer(Items.EMERALD, Items.BREAD));
            replacement.add(offer(Items.EMERALD, Items.APPLE));
            when(handler.getRecipes()).thenReturn(replacement);

            QuickTrade.prepareTradeOrder(screen);
            assertThat(replacement.get(0).getSellItem().getItem()).isEqualTo(Items.APPLE);
            assertThat(replacement.get(1).getSellItem().getItem()).isEqualTo(Items.BREAD);

            ((MerchantScreenAccessor) screen).quickcraft$setSelectedIndex(0);
            QuickTrade.syncRecipeIndex(screen);
            QuickTrade.prepareTradeOrder(screen);
            assertThat(replacement.get(0).getSellItem().getItem()).isEqualTo(Items.APPLE);
        }

        ArgumentCaptor<SelectMerchantTradeC2SPacket> packet = ArgumentCaptor.forClass(
                SelectMerchantTradeC2SPacket.class
        );
        verify(network).sendPacket(packet.capture());
        assertThat(packet.getValue().getTradeId()).isEqualTo(1);
    }

    @Test
    void newScreenDoesNotReusePreviousOrderMapping() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_FAVORITE_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.APPLE));
        QuickTradeTestSupport.setCurrentMerchantKey(MERCHANT_KEY);

        MinecraftClient client = mockClient();
        MerchantScreen firstScreen = mockScreen();
        MerchantScreenHandler firstHandler = firstScreen.getScreenHandler();
        TradeOfferList first = new TradeOfferList();
        first.add(offer(Items.EMERALD, Items.BREAD));
        first.add(offer(Items.EMERALD, Items.APPLE));
        when(firstHandler.getRecipes()).thenReturn(first);
        QuickTradeTestSupport.setField(client, "currentScreen", firstScreen);

        MerchantScreen secondScreen = mockScreen();
        MerchantScreenHandler secondHandler = secondScreen.getScreenHandler();
        TradeOfferList second = new TradeOfferList();
        second.add(offer(Items.EMERALD, Items.APPLE));
        second.add(offer(Items.EMERALD, Items.BREAD));
        when(secondHandler.getRecipes()).thenReturn(second);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(firstScreen);
            QuickTrade.prepareTradeOrder(secondScreen);
        }

        assertThat(second.get(0).getSellItem().getItem()).isEqualTo(Items.APPLE);
        assertThat(second.get(1).getSellItem().getItem()).isEqualTo(Items.BREAD);
    }

    @Test
    void initPreparationClaimsContinuousMenuBeforeTick() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        MinecraftClient client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getScreenHandler().getRecipes()).thenReturn(new TradeOfferList());
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            QuickTrade.prepareTradeOrder(screen);
            new QuickTrade().onClientTick(client);
            QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(false);
            new QuickTrade().onClientTick(client);
        }
        verify(client.player).closeHandledScreen();
    }

    @Test
    void handlerLossTimeoutReleasesContinuousOwnership() throws Exception {
        QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(false);
        QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE.setBooleanValue(true);
        QuickTradeTestSupport.loadFavorite(MERCHANT_KEY, new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD));
        MinecraftClient client = mockClient();
        startContinuousTask(client, merchant(MERCHANT_KEY));
        MerchantScreen screen = mockScreen();
        when(screen.getScreenHandler().getRecipes()).thenReturn(new TradeOfferList());
        QuickTradeTestSupport.setField(client, "currentScreen", screen);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            new QuickTrade().onClientTick(client);
            QuickTradeTestSupport.setField(client, "currentScreen", mock(net.minecraft.client.gui.screen.Screen.class));
            for (int tick = 0; tick < 41; tick++) {
                new QuickTrade().onClientTick(client);
            }
        }
        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "pendingAutoTrade")).isEqualTo(false);
        assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, "pendingContinuousTrade")).isEqualTo(false);
        for (String field : List.of(
                "continuousTaskMerchantKey",
                "continuousClaimedScreen",
                "continuousClaimedHandler"
        )) {
            assertThat(QuickTradeTestSupport.getStaticField(QuickTrade.class, field)).as(field).isNull();
        }
        verify(client.player, never()).closeHandledScreen();
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
        return client;
    }

    private static void startContinuousTask(MinecraftClient client, MerchantEntity merchant) throws Exception {
        when(client.world.getEntitiesByType(any(), any(), any())).thenReturn(List.of(merchant));
        when(client.player.squaredDistanceTo(merchant)).thenReturn(4.0);
        new QuickTrade().onClientTick(client);
        verify(client.interactionManager).interactEntity(client.player, merchant, Hand.MAIN_HAND);
    }

    private static MerchantEntity merchant(String key) {
        MerchantEntity merchant = mock(MerchantEntity.class);
        when(merchant.getUuidAsString()).thenReturn(key);
        when(merchant.isRemoved()).thenReturn(false);
        return merchant;
    }

    private static MerchantScreen mockScreen() {
        MerchantScreen screen = mock(
                MerchantScreen.class,
                withSettings().extraInterfaces(HandledScreenAccessor.class, MerchantScreenAccessor.class)
        );
        MerchantScreenHandler handler = mock(MerchantScreenHandler.class);
        when(screen.getScreenHandler()).thenReturn(handler);
        when(((HandledScreenAccessor) screen).quickcraft$getGuiLeft()).thenReturn(0);
        when(((HandledScreenAccessor) screen).quickcraft$getGuiTop()).thenReturn(0);
        when(((MerchantScreenAccessor) screen).quickcraft$getIndexStartOffset()).thenReturn(0);
        when(((MerchantScreenAccessor) screen).quickcraft$getSelectedIndex()).thenReturn(0);
        return screen;
    }

    private static TradeOffer offer(net.minecraft.item.Item first, net.minecraft.item.Item sell) {
        return new TradeOffer(new TradedItem(first, 1), Optional.empty(), new ItemStack(sell), 12, 1, 0.05f);
    }
}
