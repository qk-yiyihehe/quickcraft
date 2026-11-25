package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.MerchantScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 村民交易增强：
 * - 中键收藏多条交易，再次中键已收藏交易会逐条取消
 * - 收藏交易会按收藏顺序排在原版交易列表前面
 * - 右键一条交易时，尽可能连续完成该交易
 * - 开启快速交易后，对着村民右键会按顺序自动完成全部收藏交易并关闭界面
 * - 开启持续交易后，自动扫描交互距离内的村民并按顺序完成全部收藏交易
 */
public final class QuickTrade implements ClientModInitializer {
    private static final int ROW_X_OFFSET = 5;
    private static final int ROW_Y_OFFSET = 18;
    private static final int ROW_WIDTH = 88;
    private static final int ROW_HEIGHT = 20;
    private static final int VISIBLE_ROW_COUNT = 7;
    private static final int STAR_X_OFFSET = 1;
    private static final int STAR_Y_OFFSET = 1;
    private static final int OUTPUT_SLOT_ID = 2;
    private static final int MAX_BATCH_TRADES = 512;
    private static final int AUTO_TRADE_TIMEOUT_TICKS = 40;
    private static final int MERCHANT_OPEN_TIMEOUT_TICKS = 20;
    // 开屏失败后的短期退避；离开范围、关闭功能或换档案时由扫描/状态清理负责释放。
    private static final int MERCHANT_RETRY_TICKS = 100;
    // 扫描盒按原版默认实体交互距离外扩，实际目标仍由玩家实体交互范围校验。
    private static final double CONTINUOUS_TRADE_SCAN_RADIUS = 4.5;

    private static final Map<String, List<FavoriteTrade>> FAVORITE_TRADES = new HashMap<>();
    private static final Set<String> CONTINUOUS_HANDLED_MERCHANTS = new HashSet<>();
    private static final Map<String, Integer> MERCHANT_OPEN_BACKOFF = new HashMap<>();
    private static boolean lastUseDown;
    private static boolean pendingAutoTrade;
    private static boolean pendingContinuousTrade;
    private static int pendingAutoTradeTicks;
    private static int pendingMerchantTicks;
    private static String pendingMerchantKey;
    private static String currentScreenMerchantKey;
    private static String continuousTaskMerchantKey;
    private static MerchantScreen continuousClaimedScreen;
    private static MerchantMenu continuousClaimedHandler;
    private static TradeOrderState currentOrderState;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    void onClientTick(Minecraft client) {
        // 关闭持续交易后不再执行等待中的自动选择/付款/取出；已认领的商人菜单在此关闭并归还付款。
        if (pendingContinuousTrade && !QuickCraftConfigs.isContinuousTradeEnabled()) {
            cancelPendingContinuousTrade(client);
        }

        handleMerchantUseAttempt(client);
        processPendingMerchantOpen(client);
        clearCurrentMerchantKeyIfNeeded(client);

        if (!QuickCraftConfigs.isQuickTradeEnabled() && !pendingContinuousTrade) {
            clearPendingAutoTradeState();
        } else {
            processPendingAutoTrade(client);
        }

        processContinuousTrade(client);

        if (currentOrderState != null && getCurrentScreen(client) != currentOrderState.screen()) {
            restoreTradeOrder(
                    currentOrderState.screen(),
                    currentOrderState.offers(),
                    currentOrderState.originalOffers()
            );
            currentOrderState = null;
        }
    }

    public static boolean handleMerchantMouseClicked(MerchantScreen screen, double mouseX, double mouseY, int button) {
        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        int tradeIndex = findVisibleTradeIndexAt(screen, mouseX, mouseY);
        if (tradeIndex < 0) {
            return false;
        }

        if (button == getMiddleMouseButton() && QuickCraftConfigs.isFavoriteTradeEnabled()) {
            toggleFavorite(screen, tradeIndex);
            return true;
        }

        // 交易界面里的右键连续成交由独立开关控制；关闭时交还原版右键行为。
        if (button == getRightMouseButton() && QuickCraftConfigs.isQuickTradeRightClickEnabled()) {
            showTradeResult(screen, tradeAllAvailable(screen, tradeIndex, false));
            return true;
        }

        return false;
    }

    public static void prepareTradeOrder(MerchantScreen screen) {
        bindCurrentMerchant(screen);
        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers == null || offers.isEmpty()) {
            return;
        }

        if (currentOrderState != null && currentOrderState.screen() == screen && currentOrderState.offers() == offers
                && currentOrderState.originalOffers().length == offers.size()) {
            return;
        }

        MerchantOffer[] originalOffers = offers.toArray(new MerchantOffer[0]);
        TradeDisplayOrder displayOrder = buildDisplayOrder(originalOffers, getFavoriteTrades(screen));
        applyDisplayOrder(offers, originalOffers, displayOrder.displayToServerIndex());
        currentOrderState = new TradeOrderState(
                screen,
                offers,
                originalOffers,
                displayOrder.displayToServerIndex(),
                displayOrder.favoriteCount()
        );

        setIndexStartOffset(screen, 0);
    }

    public static void renderFavoriteStar(MerchantScreen screen, GuiGraphicsExtractor context) {
        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        if (!QuickCraftConfigs.isFavoriteTradeEnabled() || currentOrderState == null || currentOrderState
                .screen() != screen || currentOrderState.favoriteCount() == 0) {
            return;
        }

        int startOffset = getIndexStartOffset(screen);
        Minecraft client = Minecraft.getInstance();
        if (client.font == null) {
            return;
        }

        int rowLeft = getGuiLeft(screen) + ROW_X_OFFSET;
        int firstVisibleFavorite = Math.max(0, startOffset);
        int favoriteEnd = Math.min(currentOrderState.favoriteCount(), startOffset + VISIBLE_ROW_COUNT);
        for (int favoriteIndex = firstVisibleFavorite; favoriteIndex < favoriteEnd; favoriteIndex++) {
            int visibleRow = favoriteIndex - startOffset;
            int rowTop = getGuiTop(screen) + ROW_Y_OFFSET + visibleRow * ROW_HEIGHT;
            context.text(client.font, "★", rowLeft + STAR_X_OFFSET, rowTop + STAR_Y_OFFSET, 0xFFFFE066);
        }
    }

    private static void handleMerchantUseAttempt(Minecraft client) {
        if (client == null || client.player == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown && getCurrentScreen(client) == null) {
            AbstractVillager merchant = getLookedAtMerchant(client);
            if (merchant != null) {
                // 手动使用另一商人即接管旧持续任务；旧任务不再拥有后续菜单或自动操作。
                abandonContinuousTaskForManualUse();
                pendingMerchantKey = buildMerchantKey(merchant);
                pendingMerchantTicks = 0;

                if (QuickCraftConfigs.isQuickTradeEnabled()) {
                    if (getFavoriteTrades(pendingMerchantKey).isEmpty()) {
                        sendStatusMessage(client, Component.translatable("quickcraft.message.trade.no_favorite_saved"));
                    } else {
                        pendingAutoTrade = true;
                        pendingContinuousTrade = false;
                        pendingAutoTradeTicks = 0;
                    }
                }
            }
        }

        lastUseDown = useDown;
    }

    private static void processPendingAutoTrade(Minecraft client) {
        if (!pendingAutoTrade) {
            return;
        }

        pendingAutoTradeTicks++;
        MerchantScreen screen = getCurrentScreen(client) instanceof MerchantScreen merchantScreen ? merchantScreen
                : null;
        MerchantMenu handler = screen != null ? screen.getMenu() : getOpenMerchantHandler(client);
        if (handler == null || (!pendingContinuousTrade && screen == null)) {
            if (pendingAutoTradeTicks > AUTO_TRADE_TIMEOUT_TICKS) {
                clearPendingAutoTradeState();
            }
            return;
        }

        if (client.player == null || client.gameMode == null) {
            clearPendingAutoTradeState();
            return;
        }

        if (screen != null) {
            bindCurrentMerchant(screen);
            prepareTradeOrder(screen);
        }

        MerchantOffers offers = handler.getOffers();
        if (offers.isEmpty()) {
            if (pendingAutoTradeTicks > AUTO_TRADE_TIMEOUT_TICKS) {
                if (pendingContinuousTrade) {
                    finishPendingAutoTrade(client, TradeResult.BLOCKED);
                } else {
                    clearPendingAutoTradeState();
                }
            }
            return;
        }

        List<FavoriteTrade> favoriteTrades = screen != null ? getFavoriteTrades(screen)
                : getFavoriteTrades(currentScreenMerchantKey);
        if (favoriteTrades.isEmpty()) {
            if (pendingContinuousTrade) {
                sendStatusMessage(
                        client,
                        Component.translatable("quickcraft.message.trade.current_villager_no_favorite")
                );
                finishPendingAutoTrade(client, TradeResult.COMPLETE);
            } else {
                clearPendingAutoTradeState();
                QuickCrossBoxSortStatus.show(
                        screen,
                        Component.translatable("quickcraft.message.trade.current_villager_no_favorite")
                );
            }
            return;
        }

        boolean dropOutput = QuickCraftConfigs.ContainerTools.DROP_FAVORITE_TRADE_RESULTS.getBooleanValue();
        boolean foundFavorite = false;
        TradeResult result = TradeResult.COMPLETE;
        for (FavoriteTrade favoriteTrade : favoriteTrades) {
            int favoriteIndex = findFavoriteOfferIndex(offers, favoriteTrade);
            if (favoriteIndex < 0) {
                continue;
            }

            foundFavorite = true;
            MerchantOffer favoriteOffer = getOffer(offers, favoriteIndex);
            if (favoriteOffer == null || favoriteOffer.isOutOfStock()) {
                continue;
            }

            TradeResult trade = screen != null
                    ? tradeAllAvailable(screen, favoriteIndex, dropOutput)
                    : tradeAllAvailable(handler, favoriteIndex, dropOutput);
            if (result != TradeResult.LOCKED_PAYMENT_AMBIGUOUS && trade != TradeResult.COMPLETE) {
                result = trade;
            }
        }

        if (!foundFavorite) {
            var message = Component.translatable("quickcraft.message.trade.current_villager_no_favorite");
            if (pendingContinuousTrade) {
                sendStatusMessage(client, message);
                finishPendingAutoTrade(client, TradeResult.COMPLETE);
            } else {
                clearPendingAutoTradeState();
                QuickCrossBoxSortStatus.show(screen, message);
            }
            return;
        }

        finishPendingAutoTrade(client, result);
    }

    private static void processContinuousTrade(Minecraft client) {
        if (!QuickCraftConfigs.isContinuousTradeEnabled() || !QuickCraftConfigs.isFavoriteTradeEnabled()) {
            CONTINUOUS_HANDLED_MERCHANTS.clear();
            MERCHANT_OPEN_BACKOFF.clear();
            return;
        }

        tickMerchantBackoff();

        if (client.player == null || client.level == null || client.gameMode == null || getCurrentScreen(client) != null
                || pendingAutoTrade || pendingMerchantKey != null) {
            return;
        }

        AABB scanAABB = client.player.getBoundingBox().inflate(CONTINUOUS_TRADE_SCAN_RADIUS);
        List<AbstractVillager> nearbyMerchants = client.level.getEntities(
                EntityTypeTest.forClass(AbstractVillager.class),
                scanAABB,
                merchant -> client.player.isWithinEntityInteractionRange(merchant, 0.0)
        );
        Set<String> nearbyKeys = new HashSet<>();
        for (AbstractVillager merchant : nearbyMerchants) {
            nearbyKeys.add(buildMerchantKey(merchant));
        }
        CONTINUOUS_HANDLED_MERCHANTS.retainAll(nearbyKeys);
        MERCHANT_OPEN_BACKOFF.keySet().retainAll(nearbyKeys);

        AbstractVillager target = nearbyMerchants.stream().filter(
                merchant -> !CONTINUOUS_HANDLED_MERCHANTS.contains(buildMerchantKey(merchant))
        ).filter(merchant -> !MERCHANT_OPEN_BACKOFF.containsKey(buildMerchantKey(merchant))).filter(
                merchant -> !getFavoriteTrades(buildMerchantKey(merchant)).isEmpty()
        ).min((left, right) -> Double.compare(client.player.distanceToSqr(left), client.player.distanceToSqr(right)))
                .orElse(null);
        if (target == null) {
            return;
        }

        pendingMerchantKey = buildMerchantKey(target);
        pendingMerchantTicks = 0;
        pendingAutoTrade = true;
        pendingContinuousTrade = true;
        pendingAutoTradeTicks = 0;
        continuousTaskMerchantKey = pendingMerchantKey;
        continuousClaimedScreen = null;
        continuousClaimedHandler = null;
        client.gameMode.interact(client.player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
    }

    private static void tickMerchantBackoff() {
        MERCHANT_OPEN_BACKOFF.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
    }

    /**
     * 兼容未被 setScreen 拦截的边界调用；正常持续交易不会创建前台 MerchantScreen。
     */
    public static boolean shouldHideContinuousTradeScreen(MerchantScreen screen) {
        return pendingContinuousTrade && QuickCraftConfigs.isContinuousTradeEnabled() && getCurrentScreen(
                Minecraft.getInstance()
        ) == screen;
    }

    public static boolean shouldSuppressContinuousTradeScreenOpen() {
        return pendingContinuousTrade && QuickCraftConfigs.isContinuousTradeEnabled();
    }

    private static void processPendingMerchantOpen(Minecraft client) {
        if (pendingMerchantKey == null) {
            return;
        }

        pendingMerchantTicks++;
        MerchantScreen screen = getCurrentScreen(client) instanceof MerchantScreen merchantScreen ? merchantScreen
                : null;
        MerchantMenu handler = screen != null ? screen.getMenu() : getOpenMerchantHandler(client);
        if (screen != null || handler != null) {
            bindPendingMerchant(screen, handler);
            return;
        }

        if (pendingMerchantTicks > MERCHANT_OPEN_TIMEOUT_TICKS) {
            if (pendingContinuousTrade) {
                MERCHANT_OPEN_BACKOFF.put(pendingMerchantKey, MERCHANT_RETRY_TICKS);
            }
            pendingMerchantKey = null;
            pendingMerchantTicks = 0;
        }
    }

    private static void clearCurrentMerchantKeyIfNeeded(Minecraft client) {
        if (!(getCurrentScreen(client) instanceof MerchantScreen) && getOpenMerchantHandler(client) == null) {
            currentScreenMerchantKey = null;
        }
    }

    private static MerchantMenu getOpenMerchantHandler(Minecraft client) {
        if (client == null || client.player == null) {
            return null;
        }

        if (client.player.containerMenu instanceof MerchantMenu handler && handler.containerId != 0) {
            return handler;
        }

        return null;
    }

    private static TradeResult tradeAllAvailable(MerchantScreen screen, int tradeIndex, boolean dropOutput) {
        return tradeAllAvailable(screen.getMenu(), tradeIndex, () -> selectTrade(screen, tradeIndex), dropOutput);
    }

    private static TradeResult tradeAllAvailable(MerchantMenu handler, int tradeIndex, boolean dropOutput) {
        return tradeAllAvailable(handler, tradeIndex, () -> selectTrade(handler, tradeIndex), dropOutput);
    }

    private static TradeResult tradeAllAvailable(
            MerchantMenu handler,
            int tradeIndex,
            Runnable selectTradeAction,
            boolean dropOutput
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return TradeResult.BLOCKED;
        }

        MerchantOffer offer = getOffer(handler.getOffers(), tradeIndex);
        if (offer == null) {
            return TradeResult.BLOCKED;
        }
        if (offer.isOutOfStock()) {
            return TradeResult.COMPLETE;
        }

        if (needsExplicitPayments(handler, offer)) {
            return tradeWithExplicitPayments(client, handler, tradeIndex, offer, dropOutput);
        }
        selectTradeAction.run();

        for (int attempt = 0; attempt < MAX_BATCH_TRADES; attempt++) {
            if (!handler.getSlot(OUTPUT_SLOT_ID).hasItem()) {
                if (needsExplicitPayments(handler, offer)) {
                    return tradeWithExplicitPayments(client, handler, tradeIndex, offer, dropOutput);
                }
                selectTradeAction.run();
                if (!handler.getSlot(OUTPUT_SLOT_ID).hasItem()) {
                    MerchantOffer currentOffer = getOffer(handler.getOffers(), tradeIndex);
                    return currentOffer == null || !currentOffer.isOutOfStock() ? TradeResult.BLOCKED
                            : TradeResult.COMPLETE;
                }
            }

            if (!takeTradeOutput(client, handler, offer, dropOutput)) {
                return TradeResult.BLOCKED;
            }
        }

        return TradeResult.COMPLETE;
    }

    private static boolean takeTradeOutput(
            Minecraft client,
            MerchantMenu handler,
            MerchantOffer offer,
            boolean dropOutput
    ) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT_ID).getItem();
        if (dropOutput) {
            if (!handler.getCarried().isEmpty() || !ItemStack.matches(output, offer.getResult())) {
                return false;
            }

            // 丢弃后背包数量不变，用客户端配方的成交次数判断是否继续。
            int previousUses = offer.getUses();
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    OUTPUT_SLOT_ID,
                    1,
                    ContainerInput.THROW,
                    client.player
            );
            return offer.getUses() > previousUses;
        }

        ItemStack resultTemplate = output.copy();
        int beforeCount = countMatchingItems(client.player.getInventory(), resultTemplate);
        QuickSlotTransfer.quickMove(client, handler, OUTPUT_SLOT_ID, QuickSlotTransfer::isPlayerStorageSlot, true);
        return countMatchingItems(client.player.getInventory(), resultTemplate) > beforeCount;
    }

    private static boolean needsExplicitPayments(MerchantMenu handler, MerchantOffer offer) {
        ItemStack first = offer.getCostA();
        ItemStack second = offer.getCostB();
        boolean hasLockedDestination = false;
        for (int id = 3; id < handler.slots.size(); id++) {
            if (!QuickContainerLock.isLockedSlot(handler, id)) {
                continue;
            }
            hasLockedDestination = true;
            ItemStack stack = handler.getSlot(id).getItem();
            if (!stack.isEmpty() && (stack.is(first.getItem()) || (!second.isEmpty() && stack.is(second.getItem())))) {
                return true;
            }
        }
        // 选择交易也会把旧付款退回背包，目的锁格同样不能交给服务端自行选择。
        return hasLockedDestination && (handler.getSlot(0).hasItem() || handler.getSlot(1).hasItem());
    }

    private static TradeResult tradeWithExplicitPayments(
            Minecraft client,
            MerchantMenu handler,
            int tradeIndex,
            MerchantOffer offer,
            boolean dropOutput
    ) {
        ItemStack first = offer.getCostA();
        ItemStack second = offer.getCostB();
        MerchantOffer[] serverOrder = currentOrderState != null && currentOrderState.offers() == handler.getOffers()
                ? currentOrderState.originalOffers()
                : handler.getOffers().toArray(MerchantOffer[]::new);
        MerchantOffer matched = null;
        for (MerchantOffer candidate : serverOrder) {
            if (candidate.satisfiedBy(first, second)) {
                matched = candidate;
                break;
            }
        }
        if (matched != offer) {
            return TradeResult.LOCKED_PAYMENT_AMBIGUOUS;
        }
        if (!handler.getCarried().isEmpty() || client.getConnection() == null) {
            return TradeResult.BLOCKED;
        }
        // 原版对 -1 不执行自动补料；清除选中提示后，只发送明确来源的取放。
        client.getConnection().send(new ServerboundSelectTradePacket(-1));
        handler.setSelectionHint(tradeIndex);
        if (!clearExplicitPayments(client, handler)) {
            return TradeResult.BLOCKED;
        }
        try {
            for (int attempt = 0; attempt < MAX_BATCH_TRADES && !offer.isOutOfStock(); attempt++) {
                if (!fillExplicitPayment(client, handler, 0, offer.getCostA()) || !fillExplicitPayment(
                        client,
                        handler,
                        1,
                        offer.getCostB()
                )) {
                    return TradeResult.BLOCKED;
                }
                ItemStack expected = offer.assemble();
                ItemStack actual = handler.getSlot(OUTPUT_SLOT_ID).getItem();
                if (!ItemStack.isSameItemSameComponents(actual, expected) || actual.getCount() != expected.getCount()) {
                    return TradeResult.BLOCKED;
                }
                if (!takeTradeOutput(client, handler, offer, dropOutput) || !handler.getCarried().isEmpty()) {
                    return TradeResult.BLOCKED;
                }
            }
            return TradeResult.COMPLETE;
        } finally {
            clearExplicitPayments(client, handler);
        }
    }

    private static boolean clearExplicitPayments(Minecraft client, MerchantMenu handler) {
        if (client.player.containerMenu != handler || !handler.getCarried().isEmpty()) {
            return false;
        }
        for (int id = 0; id < 2; id++) {
            if (handler.getSlot(id).hasItem()) {
                QuickSlotTransfer.quickMove(client, handler, id, QuickSlotTransfer::isPlayerStorageSlot, true);
                if (handler.getSlot(id).hasItem() || !handler.getCarried().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean fillExplicitPayment(Minecraft client, MerchantMenu handler, int target, ItemStack cost) {
        if (cost.isEmpty()) {
            return !handler.getSlot(target).hasItem();
        }
        ItemStack existing = handler.getSlot(target).getItem();
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, cost)) {
            return false;
        }
        for (int id = 3; id < handler.slots.size(); id++) {
            int missing = cost.getCount() - handler.getSlot(target).getItem().getCount();
            if (missing <= 0) {
                return true;
            }
            var source = handler.getSlot(id);
            if (QuickContainerLock.isLockedSlot(handler, source) || !source.mayPickup(client.player) || !ItemStack
                    .isSameItemSameComponents(source.getItem(), cost)) {
                continue;
            }
            int amount = Math.min(missing, source.getItem().getCount());
            client.gameMode.handleContainerInput(handler.containerId, id, 0, ContainerInput.PICKUP, client.player);
            ItemStack cursor = handler.getCarried();
            if (!ItemStack.isSameItemSameComponents(cursor, cost)) {
                return false;
            }
            if (cursor.getCount() <= amount) {
                client.gameMode.handleContainerInput(
                        handler.containerId,
                        target,
                        0,
                        ContainerInput.PICKUP,
                        client.player
                );
            } else {
                for (int placed = 0; placed < amount; placed++) {
                    client.gameMode.handleContainerInput(
                            handler.containerId,
                            target,
                            1,
                            ContainerInput.PICKUP,
                            client.player
                    );
                }
                client.gameMode.handleContainerInput(handler.containerId, id, 0, ContainerInput.PICKUP, client.player);
            }
            if (!handler.getCarried().isEmpty()) {
                return false;
            }
        }
        return handler.getSlot(target).getItem().getCount() >= cost.getCount();
    }

    private static void selectTrade(MerchantScreen screen, int tradeIndex) {
        Minecraft client = Minecraft.getInstance();
        MerchantMenu handler = screen.getMenu();
        int serverTradeIndex = toServerTradeIndex(screen, tradeIndex);

        setSelectedIndex(screen, tradeIndex);
        handler.setSelectionHint(tradeIndex);
        handler.tryMoveItems(tradeIndex);

        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSelectTradePacket(serverTradeIndex));
        }
    }

    private static void selectTrade(MerchantMenu handler, int tradeIndex) {
        Minecraft client = Minecraft.getInstance();
        handler.setSelectionHint(tradeIndex);
        handler.tryMoveItems(tradeIndex);

        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSelectTradePacket(tradeIndex));
        }
    }

    public static void syncRecipeIndex(MerchantScreen screen) {
        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        MerchantMenu handler = screen.getMenu();
        int displayTradeIndex = getSelectedIndex(screen);
        if (displayTradeIndex < 0) {
            return;
        }
        int serverTradeIndex = toServerTradeIndex(screen, displayTradeIndex);

        handler.setSelectionHint(displayTradeIndex);
        handler.tryMoveItems(displayTradeIndex);

        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            connection.send(new ServerboundSelectTradePacket(serverTradeIndex));
        }
    }

    private static int findVisibleTradeIndexAt(MerchantScreen screen, double mouseX, double mouseY) {
        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers.isEmpty()) {
            return -1;
        }

        int guiLeft = getGuiLeft(screen);
        int guiTop = getGuiTop(screen);
        int startOffset = getIndexStartOffset(screen);

        for (int visibleRow = 0; visibleRow < VISIBLE_ROW_COUNT; visibleRow++) {
            int rowLeft = guiLeft + ROW_X_OFFSET;
            int rowTop = guiTop + ROW_Y_OFFSET + visibleRow * ROW_HEIGHT;
            if (mouseX < rowLeft || mouseX >= rowLeft + ROW_WIDTH) {
                continue;
            }
            if (mouseY < rowTop || mouseY >= rowTop + ROW_HEIGHT) {
                continue;
            }

            int tradeIndex = startOffset + visibleRow;
            return tradeIndex < offers.size() ? tradeIndex : -1;
        }

        return -1;
    }

    private static void toggleFavorite(MerchantScreen screen, int tradeIndex) {
        if (!QuickCraftConfigs.isFavoriteTradeEnabled()) {
            return;
        }

        bindCurrentMerchant(screen);
        String merchantKey = getCurrentMerchantKey(screen);
        if (merchantKey == null) {
            return;
        }

        MerchantOffers offers = screen.getMenu().getOffers();
        MerchantOffer offer = getOffer(offers, tradeIndex);
        if (offer == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        List<FavoriteTrade> favoriteTrades = FAVORITE_TRADES.computeIfAbsent(merchantKey, ignored -> new ArrayList<>());
        int favoriteIndex = findFavoriteTradeIndex(favoriteTrades, offer);
        if (favoriteIndex >= 0) {
            favoriteTrades.remove(favoriteIndex);
            if (favoriteTrades.isEmpty()) {
                FAVORITE_TRADES.remove(merchantKey);
            }
            refreshTradeOrder(screen, true);
            QuickPersistentState.saveCurrentProfileState();
            return;
        }

        favoriteTrades.add(FavoriteTrade.from(offer));
        refreshTradeOrder(screen, true);
        QuickPersistentState.saveCurrentProfileState();
    }

    private static int findFavoriteTradeIndex(List<FavoriteTrade> favoriteTrades, MerchantOffer offer) {
        for (int index = 0; index < favoriteTrades.size(); index++) {
            if (favoriteTrades.get(index).matches(offer)) {
                return index;
            }
        }
        return -1;
    }

    private static int findFavoriteOfferIndex(MerchantOffers offers, FavoriteTrade favoriteTrade) {
        if (!QuickCraftConfigs.isFavoriteTradeEnabled() || favoriteTrade == null || offers == null) {
            return -1;
        }

        for (int index = 0; index < offers.size(); index++) {
            if (favoriteTrade.matches(offers.get(index))) {
                return index;
            }
        }

        return -1;
    }

    private static MerchantOffer getOffer(MerchantOffers offers, int tradeIndex) {
        if (offers == null || tradeIndex < 0 || tradeIndex >= offers.size()) {
            return null;
        }
        return offers.get(tradeIndex);
    }

    public static MerchantOffer getValidOfferForCurrentOrder(
            MerchantOffers offers,
            ItemStack firstBuyItem,
            ItemStack secondBuyItem,
            int tradeIndex
    ) {
        return getValidOfferForCurrentOrder(
                offers,
                firstBuyItem,
                secondBuyItem,
                tradeIndex,
                () -> offers.getRecipeFor(firstBuyItem, secondBuyItem, tradeIndex)
        );
    }

    public static MerchantOffer getValidOfferForCurrentOrder(
            MerchantOffers offers,
            ItemStack firstBuyItem,
            ItemStack secondBuyItem,
            int tradeIndex,
            java.util.function.Supplier<MerchantOffer> original
    ) {
        if (tradeIndex == 0 && !offers.isEmpty() && currentOrderState != null && currentOrderState.favoriteCount() > 0
                && currentOrderState.offers() == offers && currentOrderState.screen().getMenu().getOffers() == offers) {
            MerchantOffer firstOffer = offers.get(0);
            return firstOffer.satisfiedBy(firstBuyItem, secondBuyItem) ? firstOffer : null;
        }

        return original.get();
    }

    private static int countMatchingItems(Inventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void sendStatusMessage(Minecraft client, Component text) {
        if (client.player == null) {
            return;
        }
        client.player.sendOverlayMessage(text);
    }

    private static void showTradeResult(MerchantScreen screen, TradeResult result) {
        if (result.messageKey != null) {
            QuickCrossBoxSortStatus.show(screen, Component.translatable(result.messageKey));
        }
    }

    private static void finishPendingAutoTrade(Minecraft client, TradeResult result) {
        showTradeResult(null, result);

        if (pendingContinuousTrade && currentScreenMerchantKey != null) {
            CONTINUOUS_HANDLED_MERCHANTS.add(currentScreenMerchantKey);
        }

        if (client.player != null && (getCurrentScreen(client) instanceof MerchantScreen || getOpenMerchantHandler(
                client
        ) != null)) {
            client.player.closeContainer();
        }

        clearPendingAutoTradeState();
    }

    private static void clearPendingAutoTradeState() {
        pendingAutoTrade = false;
        pendingContinuousTrade = false;
        pendingAutoTradeTicks = 0;
        continuousTaskMerchantKey = null;
        continuousClaimedScreen = null;
        continuousClaimedHandler = null;
    }

    private static void abandonContinuousTaskForManualUse() {
        if (!pendingContinuousTrade) {
            return;
        }
        clearPendingAutoTradeState();
    }

    private static void cancelPendingContinuousTrade(Minecraft client) {
        MerchantScreen screen = getCurrentScreen(client) instanceof MerchantScreen merchantScreen ? merchantScreen
                : null;
        MerchantMenu handler = screen != null ? screen.getMenu() : getOpenMerchantHandler(client);
        boolean ownsClaimedMenu = continuousTaskMerchantKey != null && ((continuousClaimedScreen != null
                && screen == continuousClaimedScreen) || (continuousClaimedHandler != null
                        && handler == continuousClaimedHandler));
        boolean ownsPendingOpen = pendingMerchantKey != null && pendingMerchantKey.equals(continuousTaskMerchantKey);
        boolean ownsCurrentBinding = ownsClaimedMenu && continuousTaskMerchantKey.equals(currentScreenMerchantKey);

        clearPendingAutoTradeState();
        if (ownsPendingOpen) {
            pendingMerchantKey = null;
        }
        pendingMerchantTicks = 0;
        if (ownsClaimedMenu && client.player != null) {
            client.player.closeContainer();
        }
        if (ownsCurrentBinding) {
            currentScreenMerchantKey = null;
        }
    }

    private static void refreshTradeOrder(MerchantScreen screen, boolean resetScroll) {
        bindCurrentMerchant(screen);
        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers == null || offers.isEmpty()) {
            return;
        }

        MerchantOffer[] originalOffers = currentOrderState != null && currentOrderState.screen() == screen
                ? currentOrderState.originalOffers()
                : offers.toArray(new MerchantOffer[0]);

        TradeDisplayOrder displayOrder = buildDisplayOrder(originalOffers, getFavoriteTrades(screen));
        applyDisplayOrder(offers, originalOffers, displayOrder.displayToServerIndex());
        currentOrderState = new TradeOrderState(
                screen,
                offers,
                originalOffers,
                displayOrder.displayToServerIndex(),
                displayOrder.favoriteCount()
        );

        if (resetScroll) {
            setIndexStartOffset(screen, 0);
        }
    }

    private static void restoreTradeOrder(
            MerchantScreen screen,
            MerchantOffers expectedOffers,
            MerchantOffer[] originalOffers
    ) {
        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers == null || offers != expectedOffers || originalOffers == null || offers
                .size() != originalOffers.length) {
            return;
        }

        for (int index = 0; index < originalOffers.length; index++) {
            offers.set(index, originalOffers[index]);
        }
    }

    private static TradeDisplayOrder buildDisplayOrder(
            MerchantOffer[] originalOffers,
            List<FavoriteTrade> favoriteTrades
    ) {
        int[] displayToServerIndex = new int[originalOffers.length];
        boolean[] pinnedServerIndices = new boolean[originalOffers.length];
        int displayIndex = 0;

        if (QuickCraftConfigs.isFavoriteTradeEnabled()) {
            for (FavoriteTrade favoriteTrade : favoriteTrades) {
                int serverIndex = findFavoriteOfferIndex(originalOffers, favoriteTrade, pinnedServerIndices);
                if (serverIndex >= 0) {
                    displayToServerIndex[displayIndex++] = serverIndex;
                    pinnedServerIndices[serverIndex] = true;
                }
            }
        }

        int favoriteCount = displayIndex;
        for (int serverIndex = 0; serverIndex < originalOffers.length; serverIndex++) {
            if (!pinnedServerIndices[serverIndex]) {
                displayToServerIndex[displayIndex++] = serverIndex;
            }
        }
        return new TradeDisplayOrder(displayToServerIndex, favoriteCount);
    }

    private static void applyDisplayOrder(
            MerchantOffers offers,
            MerchantOffer[] originalOffers,
            int[] displayToServerIndex
    ) {
        if (offers.size() != originalOffers.length || originalOffers.length != displayToServerIndex.length) {
            return;
        }

        for (int displayIndex = 0; displayIndex < displayToServerIndex.length; displayIndex++) {
            offers.set(displayIndex, originalOffers[displayToServerIndex[displayIndex]]);
        }
    }

    private static int toServerTradeIndex(MerchantScreen screen, int displayTradeIndex) {
        if (currentOrderState == null || currentOrderState.screen() != screen) {
            return displayTradeIndex;
        }
        if (displayTradeIndex < 0 || displayTradeIndex >= currentOrderState.displayToServerIndex().length) {
            return displayTradeIndex;
        }
        return currentOrderState.displayToServerIndex()[displayTradeIndex];
    }

    private static Screen getCurrentScreen(Minecraft client) {
        return QuickClientScreenAccess.currentScreen(client);
    }

    private static int getMiddleMouseButton() {
        return QuickCraftKeyBindingsAccess.middleMouseButton();
    }

    private static int getRightMouseButton() {
        return QuickCraftKeyBindingsAccess.rightMouseButton();
    }

    private static int getGuiLeft(AbstractContainerScreen<?> screen) {
        return ((HandledScreenAccessor) screen).quickcraft$getGuiLeft();
    }

    private static int getGuiTop(AbstractContainerScreen<?> screen) {
        return ((HandledScreenAccessor) screen).quickcraft$getGuiTop();
    }

    private static int getIndexStartOffset(MerchantScreen screen) {
        return ((MerchantScreenAccessor) screen).quickcraft$getIndexStartOffset();
    }

    private static void setIndexStartOffset(MerchantScreen screen, int value) {
        ((MerchantScreenAccessor) screen).quickcraft$setIndexStartOffset(Math.max(0, value));
    }

    private static void setSelectedIndex(MerchantScreen screen, int value) {
        ((MerchantScreenAccessor) screen).quickcraft$setSelectedIndex(value);
    }

    private static int getSelectedIndex(MerchantScreen screen) {
        return ((MerchantScreenAccessor) screen).quickcraft$getSelectedIndex();
    }

    private static void bindCurrentMerchant(MerchantScreen screen) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || getCurrentScreen(client) != screen) {
            return;
        }

        if (currentScreenMerchantKey == null && pendingMerchantKey != null) {
            bindPendingMerchant(screen, screen.getMenu());
        }
    }

    private static void bindPendingMerchant(MerchantScreen screen, MerchantMenu handler) {
        currentScreenMerchantKey = pendingMerchantKey;
        if (pendingContinuousTrade && pendingMerchantKey.equals(continuousTaskMerchantKey)) {
            continuousClaimedScreen = screen;
            continuousClaimedHandler = handler;
        }
        pendingMerchantKey = null;
        pendingMerchantTicks = 0;
    }

    private static String getCurrentMerchantKey(MerchantScreen screen) {
        bindCurrentMerchant(screen);
        return currentScreenMerchantKey;
    }

    private static List<FavoriteTrade> getFavoriteTrades(MerchantScreen screen) {
        String merchantKey = getCurrentMerchantKey(screen);
        return getFavoriteTrades(merchantKey);
    }

    private static List<FavoriteTrade> getFavoriteTrades(String merchantKey) {
        if (merchantKey == null) {
            return List.of();
        }
        return FAVORITE_TRADES.getOrDefault(merchantKey, List.of());
    }

    private static String buildMerchantKey(AbstractVillager merchant) {
        return merchant.getStringUUID();
    }

    private static AbstractVillager getLookedAtMerchant(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return null;
        }

        Entity entity = entityHitResult.getEntity();
        return entity instanceof AbstractVillager merchant ? merchant : null;
    }

    private static int findFavoriteOfferIndex(
            MerchantOffer[] offers,
            FavoriteTrade favoriteTrade,
            boolean[] excludedIndices
    ) {
        if (!QuickCraftConfigs.isFavoriteTradeEnabled() || favoriteTrade == null || offers == null
                || excludedIndices == null || offers.length != excludedIndices.length) {
            return -1;
        }

        for (int index = 0; index < offers.length; index++) {
            if (!excludedIndices[index] && favoriteTrade.matches(offers[index])) {
                return index;
            }
        }

        return -1;
    }

    static void clearPersistentState() {
        FAVORITE_TRADES.clear();
        CONTINUOUS_HANDLED_MERCHANTS.clear();
        MERCHANT_OPEN_BACKOFF.clear();
        currentOrderState = null;
        clearPendingAutoTradeState();
        pendingMerchantKey = null;
        currentScreenMerchantKey = null;
        pendingMerchantTicks = 0;
        lastUseDown = false;
    }

    static void loadPersistentState(JsonObject root, HolderLookup.Provider registryLookup) {
        JsonObject state = getObject(root, "quickTrade");
        if (state == null) {
            return;
        }

        JsonObject favoriteTrades = getObject(state, "favoriteTrades");
        if (favoriteTrades == null) {
            return;
        }

        for (Map.Entry<String, JsonElement> entry : favoriteTrades.entrySet()) {
            List<FavoriteTrade> loadedFavorites = new ArrayList<>();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                FavoriteTrade favoriteTrade = FavoriteTrade.fromJson(value.getAsJsonObject(), registryLookup);
                if (favoriteTrade != null) {
                    loadedFavorites.add(favoriteTrade);
                }
            } else if (value.isJsonArray()) {
                for (JsonElement favoriteElement : value.getAsJsonArray()) {
                    if (!favoriteElement.isJsonObject()) {
                        continue;
                    }

                    FavoriteTrade favoriteTrade = FavoriteTrade.fromJson(
                            favoriteElement.getAsJsonObject(),
                            registryLookup
                    );
                    if (favoriteTrade != null) {
                        loadedFavorites.add(favoriteTrade);
                    }
                }
            }

            if (!loadedFavorites.isEmpty()) {
                FAVORITE_TRADES.put(entry.getKey(), loadedFavorites);
            }
        }
    }

    static void writePersistentState(JsonObject root, HolderLookup.Provider registryLookup) {
        JsonObject state = new JsonObject();
        JsonObject favoriteTrades = new JsonObject();
        for (Map.Entry<String, List<FavoriteTrade>> entry : FAVORITE_TRADES.entrySet()) {
            JsonArray merchantFavorites = new JsonArray();
            for (FavoriteTrade favoriteTrade : entry.getValue()) {
                merchantFavorites.add(favoriteTrade.toJson(registryLookup));
            }
            favoriteTrades.add(entry.getKey(), merchantFavorites);
        }
        state.add("favoriteTrades", favoriteTrades);
        root.add("quickTrade", state);
    }

    private static JsonObject getObject(JsonObject root, String key) {
        JsonElement element = root != null && root.has(key) ? root.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private enum TradeResult {
        COMPLETE(null), BLOCKED("quickcraft.message.trade.blocked"), LOCKED_PAYMENT_AMBIGUOUS(
                "quickcraft.message.trade.locked_payment_ambiguous");

        private final String messageKey;

        TradeResult(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    private record TradeOrderState(
            MerchantScreen screen,
            MerchantOffers offers,
            MerchantOffer[] originalOffers,
            int[] displayToServerIndex,
            int favoriteCount
    ) {
    }

    private record TradeDisplayOrder(int[] displayToServerIndex, int favoriteCount) {
    }

    private record FavoriteTrade(ItemStack firstBuyItem, ItemStack secondBuyItem, ItemStack sellItem) {
        private static FavoriteTrade from(MerchantOffer offer) {
            return new FavoriteTrade(
                    normalize(offer.getCostA()),
                    normalize(offer.getCostB()),
                    normalize(offer.assemble())
            );
        }

        private boolean matches(MerchantOffer offer) {
            return sameIgnoringCount(firstBuyItem, offer.getCostA()) && sameIgnoringCount(
                    secondBuyItem,
                    offer.getCostB()
            ) && sameIgnoringCount(sellItem, offer.assemble());
        }

        private JsonObject toJson(HolderLookup.Provider registryLookup) {
            JsonObject json = new JsonObject();
            json.addProperty("firstBuyItem", encodeStack(firstBuyItem, registryLookup));
            json.addProperty("secondBuyItem", encodeStack(secondBuyItem, registryLookup));
            json.addProperty("sellItem", encodeStack(sellItem, registryLookup));
            return json;
        }

        private static FavoriteTrade fromJson(JsonObject json, HolderLookup.Provider registryLookup) {
            if (json == null) {
                return null;
            }

            ItemStack firstBuy = decodeStack(json.get("firstBuyItem"), registryLookup);
            ItemStack secondBuy = decodeStack(json.get("secondBuyItem"), registryLookup);
            ItemStack sell = decodeStack(json.get("sellItem"), registryLookup);
            if (sell.isEmpty()) {
                return null;
            }

            return new FavoriteTrade(normalize(firstBuy), normalize(secondBuy), normalize(sell));
        }

        private static ItemStack normalize(ItemStack stack) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack copy = stack.copy();
            copy.setCount(1);
            return copy;
        }

        private static boolean sameIgnoringCount(ItemStack expected, ItemStack actual) {
            if (expected.isEmpty() && actual.isEmpty()) {
                return true;
            }
            if (expected.isEmpty() || actual.isEmpty()) {
                return false;
            }

            ItemStack normalizedActual = actual.copy();
            normalizedActual.setCount(1);
            return ItemStack.isSameItemSameComponents(expected, normalizedActual);
        }

        private static String encodeStack(ItemStack stack, HolderLookup.Provider registryLookup) {
            return ItemStack.OPTIONAL_CODEC.encodeStart(
                    registryLookup.createSerializationContext(NbtOps.INSTANCE),
                    normalize(stack)
            ).result().map(Tag::toString).orElse("{}");
        }

        private static ItemStack decodeStack(JsonElement element, HolderLookup.Provider registryLookup) {
            if (element == null || !element.isJsonPrimitive()) {
                return ItemStack.EMPTY;
            }

            try {
                return ItemStack.OPTIONAL_CODEC.parse(
                        registryLookup.createSerializationContext(NbtOps.INSTANCE),
                        TagParser.parseCompoundFully(element.getAsString())
                ).result().orElse(ItemStack.EMPTY);
            } catch (CommandSyntaxException ignored) {
                return ItemStack.EMPTY;
            }
        }
    }
}
