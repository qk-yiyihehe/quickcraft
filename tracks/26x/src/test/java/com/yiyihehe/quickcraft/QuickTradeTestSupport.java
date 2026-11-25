package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 26.x 测试用的小型状态夹具：只读写 QuickTrade 的私有状态，不替代生产判断。 */
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

    static void loadFavorite(String merchantKey, ItemStack firstBuy, ItemStack sell) throws Exception {
        HolderLookup.Provider lookup = registryLookup();
        JsonObject favorite = new JsonObject();
        favorite.addProperty("firstBuyItem", encode(firstBuy, lookup));
        favorite.addProperty("secondBuyItem", encode(ItemStack.EMPTY, lookup));
        favorite.addProperty("sellItem", encode(sell, lookup));

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

    static void setClientOptions(Minecraft client) throws Exception {
        Options options = mock(Options.class);
        KeyMapping useKey = mock(KeyMapping.class);
        when(useKey.isUnbound()).thenReturn(true);
        setField(options, "keyUse", useKey);
        setField(client, "options", options);
    }

    static void setScreen(Minecraft client, Screen screen) throws Exception {
        Gui gui = mock(Gui.class, invocation -> "screen".equals(invocation.getMethod().getName()) ? screen : null);
        setField(client, "gui", gui);
        QuickClientScreenAccess.setScreen(client, screen);
        try {
            setField(client, "screen", screen);
        } catch (NoSuchFieldException ignored) {
            // 26.2+ 的当前屏幕只保存在 gui 中。
        }
    }

    private static HolderLookup.Provider registryLookup() throws Exception {
        try {
            Method method = VanillaRegistries.class.getMethod("createLookup");
            return (HolderLookup.Provider) method.invoke(null);
        } catch (NoSuchMethodException ignored) {
            Method method = VanillaRegistries.class.getMethod("createWorldLookup");
            return (HolderLookup.Provider) method.invoke(null);
        }
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
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

    private static String encode(ItemStack stack, net.minecraft.core.HolderLookup.Provider lookup) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), stack).result()
                .map(Object::toString).orElse("{}");
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
