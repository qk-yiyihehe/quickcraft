package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.mixin.RecipeBookScreenAccessor;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;

final class QuickCraftWorkbenchAccess {
    private QuickCraftWorkbenchAccess() {
    }

    static void clearRecipeGhostSlots() {
        Minecraft client = Minecraft.getInstance();
        if (QuickClientScreenAccess.currentScreen(client) instanceof CraftingScreen screen) {
            ((RecipeBookScreenAccessor) (Object) screen)
                    .quickcraft$getRecipeBook()
                    .slotClicked(screen.getMenu().getSlot(QuickCraftMouseCraftLayout.OUTPUT_SLOT));
        }
    }
}
