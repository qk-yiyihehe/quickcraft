package com.yiyihehe.quickcraft;

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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * 村民交易增强：
 * - 中键收藏一条交易，再次中键同一条交易会取消收藏
 * - 收藏交易会在原版交易列表顺序中排到第一位
 * - 右键一条交易时，尽可能连续完成该交易
 * - 开启快速交易后，对着村民右键会自动完成收藏交易并关闭界面
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

    private static final Map<String, FavoriteTrade> FAVORITE_TRADES = new HashMap<>();
    private static boolean lastUseDown;
    private static boolean pendingAutoTrade;
    private static int pendingAutoTradeTicks;
    private static int pendingMerchantTicks;
    private static String pendingMerchantKey;
    private static String currentScreenMerchantKey;
    private static TradeOrderState currentOrderState;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        handleMerchantUseAttempt(client);
        processPendingMerchantOpen(client);
        clearCurrentMerchantKeyIfNeeded(client);

        if (!QuickCraftConfigs.isQuickTradeEnabled()) {
            clearPendingAutoTradeState();
        } else {
            processPendingAutoTrade(client);
        }

        if (currentOrderState != null && client.screen != currentOrderState.screen()) {
            restoreTradeOrder(currentOrderState.screen(), currentOrderState.originalOffers());
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

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && QuickCraftConfigs.isFavoriteTradeEnabled()) {
            toggleFavorite(screen, tradeIndex);
            return true;
        }

        // 交易界面里的右键连续成交是独立功能，不受“快速交易”开关影响。
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (tradeAllAvailable(screen, tradeIndex)) {
                sendTradeBlockedMessage(Minecraft.getInstance());
            }
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

        if (currentOrderState != null
                && currentOrderState.screen() == screen
                && currentOrderState.originalOffers().length == offers.size()) {
            return;
        }

        MerchantOffer[] originalOffers = offers.toArray(new MerchantOffer[0]);
        int[] displayToServerIndex = buildDisplayToServerIndex(originalOffers, getFavoriteTrade(screen));
        applyDisplayOrder(offers, originalOffers, displayToServerIndex);
        currentOrderState = new TradeOrderState(screen, originalOffers, displayToServerIndex);

        setIndexStartOffset(screen, 0);
    }

    public static void renderFavoriteStar(MerchantScreen screen, GuiGraphicsExtractor context) {
        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        FavoriteTrade favoriteTrade = getFavoriteTrade(screen);
        if (!QuickCraftConfigs.isFavoriteTradeEnabled() || favoriteTrade == null) {
            return;
        }

        MerchantOffers offers = screen.getMenu().getOffers();
        int favoriteIndex = findFavoriteOfferIndex(offers, favoriteTrade);
        if (favoriteIndex < 0) {
            return;
        }

        int startOffset = getIndexStartOffset(screen);
        int visibleRow = favoriteIndex - startOffset;
        if (visibleRow < 0 || visibleRow >= VISIBLE_ROW_COUNT) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.font == null) {
            return;
        }

        int rowLeft = getGuiLeft(screen) + ROW_X_OFFSET;
        int rowTop = getGuiTop(screen) + ROW_Y_OFFSET + visibleRow * ROW_HEIGHT;
        context.text(
                client.font,
                "★",
                rowLeft + STAR_X_OFFSET,
                rowTop + STAR_Y_OFFSET,
                0xFFFFE066
        );
    }

    private static void handleMerchantUseAttempt(Minecraft client) {
        if (client == null || client.player == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.keyUse);
        if (useDown && !lastUseDown && client.screen == null) {
            AbstractVillager merchant = getLookedAtMerchant(client);
            if (merchant != null) {
                pendingMerchantKey = buildMerchantKey(merchant);
                pendingMerchantTicks = 0;

                if (QuickCraftConfigs.isQuickTradeEnabled()) {
                    if (getFavoriteTrade(pendingMerchantKey) == null) {
                        sendStatusMessage(client, Component.translatable("quickcraft.message.trade.no_favorite_saved"));
                    } else {
                        pendingAutoTrade = true;
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
        if (!(client.screen instanceof MerchantScreen screen)) {
            if (pendingAutoTradeTicks > AUTO_TRADE_TIMEOUT_TICKS) {
                clearPendingAutoTradeState();
            }
            return;
        }

        if (client.player == null || client.gameMode == null) {
            clearPendingAutoTradeState();
            return;
        }

        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers.isEmpty()) {
            if (pendingAutoTradeTicks > AUTO_TRADE_TIMEOUT_TICKS) {
                clearPendingAutoTradeState();
            }
            return;
        }

        FavoriteTrade favoriteTrade = getFavoriteTrade(screen);
        int favoriteIndex = findFavoriteOfferIndex(offers, favoriteTrade);
        if (favoriteIndex < 0) {
            clearPendingAutoTradeState();
            sendStatusMessage(client, Component.translatable("quickcraft.message.trade.current_villager_no_favorite"));
            return;
        }

        MerchantOffer favoriteOffer = getOffer(offers, favoriteIndex);
        if (favoriteOffer != null && favoriteOffer.isOutOfStock()) {
            finishPendingAutoTrade(client, false);
            return;
        }

        MerchantMenu handler = screen.getMenu();
        selectTrade(screen, favoriteIndex);
        if (!handler.getSlot(OUTPUT_SLOT_ID).hasItem()) {
            if (pendingAutoTradeTicks > AUTO_TRADE_TIMEOUT_TICKS) {
                finishPendingAutoTrade(client, true);
            }
            return;
        }

        finishPendingAutoTrade(client, tradeAllAvailable(screen, favoriteIndex));
    }

    private static void processPendingMerchantOpen(Minecraft client) {
        if (pendingMerchantKey == null) {
            return;
        }

        pendingMerchantTicks++;
        if (client.screen instanceof MerchantScreen) {
            currentScreenMerchantKey = pendingMerchantKey;
            pendingMerchantKey = null;
            pendingMerchantTicks = 0;
            return;
        }

        if (pendingMerchantTicks > MERCHANT_OPEN_TIMEOUT_TICKS) {
            pendingMerchantKey = null;
            pendingMerchantTicks = 0;
        }
    }

    private static void clearCurrentMerchantKeyIfNeeded(Minecraft client) {
        if (!(client.screen instanceof MerchantScreen)) {
            currentScreenMerchantKey = null;
        }
    }

    private static boolean tradeAllAvailable(MerchantScreen screen, int tradeIndex) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return true;
        }

        MerchantOffer offer = getOffer(screen.getMenu().getOffers(), tradeIndex);
        if (offer == null) {
            return true;
        }
        if (offer.isOutOfStock()) {
            return false;
        }

        MerchantMenu handler = screen.getMenu();
        selectTrade(screen, tradeIndex);

        for (int attempt = 0; attempt < MAX_BATCH_TRADES; attempt++) {
            if (!handler.getSlot(OUTPUT_SLOT_ID).hasItem()) {
                selectTrade(screen, tradeIndex);
                if (!handler.getSlot(OUTPUT_SLOT_ID).hasItem()) {
                    MerchantOffer currentOffer = getOffer(screen.getMenu().getOffers(), tradeIndex);
                    return currentOffer == null || !currentOffer.isOutOfStock();
                }
            }

            ItemStack resultTemplate = handler.getSlot(OUTPUT_SLOT_ID).getItem().copy();
            int beforeCount = countMatchingItems(client.player.getInventory(), resultTemplate);
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    OUTPUT_SLOT_ID,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
            int afterCount = countMatchingItems(client.player.getInventory(), resultTemplate);
            if (afterCount <= beforeCount) {
                return true;
            }
        }

        return false;
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

    public static void syncRecipeIndex(MerchantScreen screen) {
        bindCurrentMerchant(screen);
        prepareTradeOrder(screen);

        MerchantMenu handler = screen.getMenu();
        int displayTradeIndex = getSelectedIndex(screen);
        if (displayTradeIndex < 0) {
            return;
        }

        handler.setSelectionHint(displayTradeIndex);
        handler.tryMoveItems(displayTradeIndex);
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
        FavoriteTrade favoriteTrade = getFavoriteTrade(merchantKey);
        if (favoriteTrade != null && favoriteTrade.matches(offer)) {
            FAVORITE_TRADES.remove(merchantKey);
            refreshTradeOrder(screen, true);
            QuickPersistentState.saveCurrentProfileState();
            sendStatusMessage(client, Component.translatable("quickcraft.message.trade.favorite_removed"));
            return;
        }

        FAVORITE_TRADES.put(merchantKey, FavoriteTrade.from(offer));
        refreshTradeOrder(screen, true);
        QuickPersistentState.saveCurrentProfileState();
        sendStatusMessage(client, Component.translatable("quickcraft.message.trade.favorite_added"));
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

    private static void sendTradeBlockedMessage(Minecraft client) {
        sendStatusMessage(client, Component.translatable("quickcraft.message.trade.blocked"));
    }

    private static void finishPendingAutoTrade(Minecraft client, boolean failed) {
        if (failed) {
            sendTradeBlockedMessage(client);
        }

        if (client.player != null && client.screen instanceof MerchantScreen) {
            client.player.closeContainer();
        }

        clearPendingAutoTradeState();
    }

    private static void clearPendingAutoTradeState() {
        pendingAutoTrade = false;
        pendingAutoTradeTicks = 0;
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

        int[] displayToServerIndex = buildDisplayToServerIndex(originalOffers, getFavoriteTrade(screen));
        applyDisplayOrder(offers, originalOffers, displayToServerIndex);
        currentOrderState = new TradeOrderState(screen, originalOffers, displayToServerIndex);

        if (resetScroll) {
            setIndexStartOffset(screen, 0);
        }
    }

    private static void restoreTradeOrder(MerchantScreen screen, MerchantOffer[] originalOffers) {
        MerchantOffers offers = screen.getMenu().getOffers();
        if (offers == null || originalOffers == null || offers.size() != originalOffers.length) {
            return;
        }

        for (int index = 0; index < originalOffers.length; index++) {
            offers.set(index, originalOffers[index]);
        }
    }

    private static int[] buildDisplayToServerIndex(MerchantOffer[] originalOffers, FavoriteTrade favoriteTrade) {
        int[] displayToServerIndex = new int[originalOffers.length];
        int favoriteServerIndex = findFavoriteOfferIndex(originalOffers, favoriteTrade);
        if (favoriteServerIndex < 0) {
            for (int index = 0; index < originalOffers.length; index++) {
                displayToServerIndex[index] = index;
            }
            return displayToServerIndex;
        }

        displayToServerIndex[0] = favoriteServerIndex;
        int displayIndex = 1;
        for (int serverIndex = 0; serverIndex < originalOffers.length; serverIndex++) {
            if (serverIndex == favoriteServerIndex) {
                continue;
            }
            displayToServerIndex[displayIndex++] = serverIndex;
        }
        return displayToServerIndex;
    }

    private static void applyDisplayOrder(MerchantOffers offers,
                                          MerchantOffer[] originalOffers,
                                          int[] displayToServerIndex) {
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
        if (client == null || client.screen != screen) {
            return;
        }

        if (currentScreenMerchantKey == null && pendingMerchantKey != null) {
            currentScreenMerchantKey = pendingMerchantKey;
            pendingMerchantKey = null;
            pendingMerchantTicks = 0;
        }
    }

    private static String getCurrentMerchantKey(MerchantScreen screen) {
        bindCurrentMerchant(screen);
        return currentScreenMerchantKey;
    }

    private static FavoriteTrade getFavoriteTrade(MerchantScreen screen) {
        String merchantKey = getCurrentMerchantKey(screen);
        return merchantKey == null ? null : FAVORITE_TRADES.get(merchantKey);
    }

    private static FavoriteTrade getFavoriteTrade(String merchantKey) {
        return merchantKey == null ? null : FAVORITE_TRADES.get(merchantKey);
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

    private static int findFavoriteOfferIndex(MerchantOffer[] offers, FavoriteTrade favoriteTrade) {
        if (!QuickCraftConfigs.isFavoriteTradeEnabled() || favoriteTrade == null || offers == null) {
            return -1;
        }

        for (int index = 0; index < offers.length; index++) {
            if (favoriteTrade.matches(offers[index])) {
                return index;
            }
        }

        return -1;
    }

    static void clearPersistentState() {
        FAVORITE_TRADES.clear();
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
            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            FavoriteTrade favoriteTrade = FavoriteTrade.fromJson(entry.getValue().getAsJsonObject(), registryLookup);
            if (favoriteTrade != null) {
                FAVORITE_TRADES.put(entry.getKey(), favoriteTrade);
            }
        }
    }

    static void writePersistentState(JsonObject root, HolderLookup.Provider registryLookup) {
        JsonObject state = new JsonObject();
        JsonObject favoriteTrades = new JsonObject();
        for (Map.Entry<String, FavoriteTrade> entry : FAVORITE_TRADES.entrySet()) {
            favoriteTrades.add(entry.getKey(), entry.getValue().toJson(registryLookup));
        }
        state.add("favoriteTrades", favoriteTrades);
        root.add("quickTrade", state);
    }

    private static JsonObject getObject(JsonObject root, String key) {
        JsonElement element = root != null && root.has(key) ? root.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private record TradeOrderState(MerchantScreen screen, MerchantOffer[] originalOffers, int[] displayToServerIndex) {
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
            return sameIgnoringCount(firstBuyItem, offer.getCostA())
                    && sameIgnoringCount(secondBuyItem, offer.getCostB())
                    && sameIgnoringCount(sellItem, offer.assemble());
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
            return ItemStack.OPTIONAL_CODEC
                    .encodeStart(registryLookup.createSerializationContext(NbtOps.INSTANCE), normalize(stack))
                    .result()
                    .map(Tag::toString)
                    .orElse("{}");
        }

        private static ItemStack decodeStack(JsonElement element, HolderLookup.Provider registryLookup) {
            if (element == null || !element.isJsonPrimitive()) {
                return ItemStack.EMPTY;
            }

            try {
                return ItemStack.OPTIONAL_CODEC
                        .parse(registryLookup.createSerializationContext(NbtOps.INSTANCE), TagParser.parseCompoundFully(element.getAsString()))
                        .result()
                        .orElse(ItemStack.EMPTY);
            } catch (CommandSyntaxException ignored) {
                return ItemStack.EMPTY;
            }
        }
    }
}
