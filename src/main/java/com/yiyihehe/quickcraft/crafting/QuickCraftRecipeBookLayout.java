package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Describes the vanilla recipe-book slot layout for the two player crafting screens.
 * It intentionally contains no item movement or recipe execution logic.
 */
public final class QuickCraftRecipeBookLayout {
    public static final int OUTPUT_SLOT = 0;
    public static final int PLAYER_INVENTORY_SIZE = 36;

    /** CraftingMenu: grid 1..9, main inventory starts at 10, hotbar at 37. */
    public static final Layout WORKBENCH = new Layout("workbench", 1, 9, 3, 3, 10, 37);

    /** InventoryMenu: grid 1..4, main inventory starts at 9, hotbar at 36. */
    public static final Layout BACKPACK = new Layout("backpack", 1, 4, 2, 2, 9, 36);

    private QuickCraftRecipeBookLayout() {
    }

    public static Layout fromScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return null;
        }

        if (screen instanceof CraftingScreen
                && handledScreen.getMenu() instanceof CraftingMenu) {
            return WORKBENCH;
        }
        if (screen instanceof InventoryScreen
                && handledScreen.getMenu() instanceof InventoryMenu) {
            return BACKPACK;
        }

        return null;
    }

    public static Layout fromHandler(AbstractContainerMenu handler) {
        if (handler instanceof CraftingMenu) {
            return WORKBENCH;
        }
        if (handler instanceof InventoryMenu) {
            return BACKPACK;
        }

        return null;
    }

    public static boolean isSupportedScreen(Screen screen) {
        return fromScreen(screen) != null;
    }

    public record Layout(
            String name,
            int gridStart,
            int gridSize,
            int gridWidth,
            int gridHeight,
            int playerMainInventoryStart,
            int playerHotbarStart
    ) {
        public int gridSlotId(int logicalIndex) {
            if (logicalIndex < 0 || logicalIndex >= gridSize) {
                throw new IndexOutOfBoundsException("logicalIndex=" + logicalIndex);
            }
            return gridStart + logicalIndex;
        }

        public boolean isGridSlot(int handlerSlot) {
            return handlerSlot >= gridStart && handlerSlot < gridStart + gridSize;
        }

        /** Maps a Inventory index (hotbar 0..8, main inventory 9..35) to a handler slot. */
        public int handlerSlotForInventoryIndex(int inventoryIndex) {
            if (inventoryIndex >= 0 && inventoryIndex < 9) {
                return playerHotbarStart + inventoryIndex;
            }
            if (inventoryIndex >= 9 && inventoryIndex < PLAYER_INVENTORY_SIZE) {
                return playerMainInventoryStart + inventoryIndex - 9;
            }
            return -1;
        }

        public int inventoryIndexForHandlerSlot(int handlerSlot) {
            if (handlerSlot >= playerHotbarStart && handlerSlot < playerHotbarStart + 9) {
                return handlerSlot - playerHotbarStart;
            }
            if (handlerSlot >= playerMainInventoryStart
                    && handlerSlot < playerMainInventoryStart + 27) {
                return handlerSlot - playerMainInventoryStart + 9;
            }
            return -1;
        }
    }
}
