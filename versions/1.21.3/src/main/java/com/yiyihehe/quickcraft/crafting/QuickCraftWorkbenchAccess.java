package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.mixin.RecipeBookScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;

final class QuickCraftWorkbenchAccess {
    private QuickCraftWorkbenchAccess() {
    }

    static void clearRecipeGhostSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen screen) {
            ((RecipeBookScreenAccessor) (Object) screen)
                    .quickcraft$getRecipeBook()
                    .onMouseClick(screen.getScreenHandler().getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT));
        }
    }
}
