package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.RecipeBookScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;

/**
 * 原版工作台配方书合成入口；潜影盒流水线由路由器选择另一执行器。
 */
public class QuickCraftWorkbench implements ClientModInitializer {
    private static QuickCraftWorkbench instance;

    private final QuickCraftRecipeBookCrafting crafting = new QuickCraftRecipeBookCrafting(
            QuickCraftRecipeBookLayout.WORKBENCH,
            QuickCraftConfigs::isWorkbenchQuickCraftEnabled,
            QuickCraftWorkbench::clearRecipeGhostSlots
    );

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(crafting::tick);
    }

    public static boolean handleWorkbenchCraftButton(boolean rapidCraft) {
        return instance != null && instance.crafting.handleCraftButton(rapidCraft);
    }

    public static boolean shouldSuppressRecipeGhostSlots() {
        return instance != null && instance.crafting.isRapidCraftingActive();
    }

    private static void clearRecipeGhostSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen screen) {
            ((RecipeBookScreenAccessor) (Object) screen)
                    .quickcraft$getRecipeBook()
                    .onMouseClick(screen.getScreenHandler().getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT));
        }
    }
}
