package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;

final class QuickCraftWorkbenchAccess {
    private QuickCraftWorkbenchAccess() {
    }

    static void clearRecipeGhostSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen screen) {
            screen.getRecipeBookWidget().slotClicked(
                    screen.getScreenHandler().getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT));
        }
    }
}
