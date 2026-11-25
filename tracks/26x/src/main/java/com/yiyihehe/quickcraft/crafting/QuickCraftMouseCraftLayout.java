package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 描述玩家的两种合成界面中原版配方书使用的槽位布局。
 * 此布局不负责移动物品或执行配方。
 */
public final class QuickCraftMouseCraftLayout {
    public static final int OUTPUT_SLOT = 0;
    public static final int PLAYER_INVENTORY_SIZE = 36;

    /** CraftingMenu 的合成格为 1–9，主物品栏从 10 开始，快捷栏从 37 开始。 */
    public static final Layout WORKBENCH = new Layout("workbench", 1, 9, 3, 3, 10, 37);

    /** InventoryMenu 的合成格为 1–4，主物品栏从 9 开始，快捷栏从 36 开始。 */
    public static final Layout BACKPACK = new Layout("backpack", 1, 4, 2, 2, 9, 36);

    private QuickCraftMouseCraftLayout() {}

    public static Layout fromScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return null;
        }

        if (screen instanceof CraftingScreen && handledScreen.getMenu() instanceof CraftingMenu) {
            return WORKBENCH;
        }
        if (screen instanceof InventoryScreen && handledScreen.getMenu() instanceof InventoryMenu) {
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

        /** 将 Inventory 的索引映射到菜单槽位。快捷栏为 0–8，主物品栏为 9–35。 */
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
            if (handlerSlot >= playerMainInventoryStart && handlerSlot < playerMainInventoryStart + 27) {
                return handlerSlot - playerMainInventoryStart + 9;
            }
            return -1;
        }
    }
}
