package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;

import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 测试用的小型状态夹具：只读写 QuickTrade 的私有状态，不替代生产判断。 */
final class QuickTradeTestSupport {
    private QuickTradeTestSupport() {}

    static void resetState() {
        QuickTrade.clearPersistentState();
    }

    static void setPending(String merchantKey, boolean continuous) throws Exception {
        setStatic("pendingAutoTrade", true);
        setStatic("pendingContinuousTrade", continuous);
        setStatic("pendingMerchantKey", merchantKey);
        setStatic("pendingAutoTradeTicks", 0);
    }

    static void setCurrentMerchantKey(String merchantKey) throws Exception {
        setStatic("currentScreenMerchantKey", merchantKey);
    }

    static void loadFavorite(String merchantKey, ItemStack firstBuy, ItemStack sell) {
        RegistryWrapper.WrapperLookup lookup = DynamicRegistryManager.of(Registries.REGISTRIES);
        JsonObject favorite = new JsonObject();
        favorite.addProperty("firstBuyItem", QuickTradeAccess.encodeStack(firstBuy, lookup));
        favorite.addProperty("secondBuyItem", "{}");
        favorite.addProperty("sellItem", QuickTradeAccess.encodeStack(sell, lookup));

        JsonArray favorites = new JsonArray();
        favorites.add(favorite);
        JsonObject byMerchant = new JsonObject();
        byMerchant.add(merchantKey, favorites);
        JsonObject trade = new JsonObject();
        trade.add("favoriteTrades", byMerchant);
        JsonObject root = new JsonObject();
        root.add("quickTrade", trade);
        QuickTrade.loadPersistentState(root, lookup);
    }

    static void setClientOptions(MinecraftClient client) throws Exception {
        GameOptions options = mock(GameOptions.class);
        KeyBinding useKey = mock(KeyBinding.class);
        when(useKey.isUnbound()).thenReturn(true);
        setField(options, "useKey", useKey);
        setField(client, "options", options);
    }

    static Object getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    static Object getStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = QuickTrade.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
