package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;

/**
 * Describes the vanilla recipe-book slot layout for the two player crafting screens.
 * It intentionally contains no item movement or recipe execution logic.
 */
public final class QuickCraftMouseCraftLayout {
    public static final int OUTPUT_SLOT = 0;
    public static final int PLAYER_INVENTORY_SIZE = 36;

    /** CraftingScreenHandler: grid 1..9, main inventory starts at 10, hotbar at 37. */
    public static final Layout WORKBENCH = new Layout("workbench", 1, 9, 3, 3, 10, 37);

    /** PlayerScreenHandler: grid 1..4, main inventory starts at 9, hotbar at 36. */
    public static final Layout BACKPACK = new Layout("backpack", 1, 4, 2, 2, 9, 36);

    private QuickCraftMouseCraftLayout() {
    }

    public static Layout fromScreen(Screen screen) {
        if (!(screen instanceof HandledScreen<?> handledScreen)) {
            return null;
        }

        if (screen instanceof CraftingScreen
                && handledScreen.getScreenHandler() instanceof CraftingScreenHandler) {
            return WORKBENCH;
        }
        if (screen instanceof InventoryScreen
                && handledScreen.getScreenHandler() instanceof PlayerScreenHandler) {
            return BACKPACK;
        }

        return null;
    }

    public static Layout fromHandler(ScreenHandler handler) {
        if (handler instanceof CraftingScreenHandler) {
            return WORKBENCH;
        }
        if (handler instanceof PlayerScreenHandler) {
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

        /** Maps a PlayerInventory index (hotbar 0..8, main inventory 9..35) to a handler slot. */
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
