package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 背包 2x2 配方书合成入口；具体合成状态由共享核心独立持有。
 */
public class QuickCraftBackpack implements ClientModInitializer {
    private static QuickCraftBackpack instance;

    private final QuickCraftRecipeBookCrafting crafting = new QuickCraftRecipeBookCrafting(
            QuickCraftRecipeBookLayout.BACKPACK,
            QuickCraftConfigs::isBackpackQuickCraftEnabled,
            null
    );

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(crafting::tick);
    }

    public static boolean handleBackpackCraftButton(boolean rapidCraft) {
        return instance != null && instance.crafting.handleCraftButton(rapidCraft);
    }
}
