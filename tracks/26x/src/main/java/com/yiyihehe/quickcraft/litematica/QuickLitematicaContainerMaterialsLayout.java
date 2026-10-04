package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ButtonPlacement;
import fi.dy.masa.malilib.gui.button.ButtonBase;

import java.util.List;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.*;

final class QuickLitematicaContainerMaterialsLayout {
    private QuickLitematicaContainerMaterialsLayout() {
    }

    static ButtonPlacement navigationPlacement(List<ButtonBase> buttons, int width, int height, int detailsWidth, int materialWidth) {
        int gap = 1;
        int bottomRow = height - 22;
        boolean nativeButtonsWrapped = buttons.stream().anyMatch(button -> button.getY() == bottomRow);
        int topRowLimit = width - 60;
        int topX = buttons.stream()
                .filter(button -> button.getY() == 24)
                .mapToInt(button -> button.getX() + button.getWidth() + gap)
                .max()
                .orElse(12);
        boolean fitsTopRow = !nativeButtonsWrapped
                && topX + detailsWidth + gap + materialWidth <= topRowLimit;
        int y = fitsTopRow ? 24 : bottomRow;
        int x = buttons.stream()
                .filter(button -> button.getY() == y)
                .mapToInt(button -> button.getX() + button.getWidth() + gap)
                .max()
                .orElse(12);
        return new ButtonPlacement(x, y);
    }

    static int contentColumns(int rowWidth) {
        int contentWidth = Math.max(ITEM_CELL_WIDTH, rowWidth - CONTAINER_COLUMN_WIDTH - COUNT_COLUMN_WIDTH - ACTION_COLUMN_WIDTH - 18);
        return Math.max(1, contentWidth / ITEM_CELL_WIDTH);
    }

    static int itemX(int contentsX, int columns, int index) {
        return contentsX + (index % columns) * ITEM_CELL_WIDTH;
    }

    static int itemY(int rowY, int columns, int index) {
        return rowY + 6 + (index / columns) * ITEM_CELL_HEIGHT;
    }

    static int hoveredItemIndex(int rowX, int rowY, int rowWidth, int itemCount, int mouseX, int mouseY) {
        int contentsX = rowX + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
        int columns = contentColumns(rowWidth);
        for (int i = 0; i < itemCount; i++) {
            int itemX = itemX(contentsX, columns, i);
            int itemY = itemY(rowY, columns, i);
            if (mouseX >= itemX && mouseX < itemX + 16 && mouseY >= itemY && mouseY < itemY + 16) {
                return i;
            }
        }
        return -1;
    }
}
