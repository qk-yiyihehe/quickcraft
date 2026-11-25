package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.Identifier;

public final class QuickCrossBoxSortButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.of("quickcraft", "textures/gui/cross_box_sort_compact.png");

    public QuickCrossBoxSortButton(
            int x,
            int y,
            HandledScreen<?> screen,
            boolean playerInventory,
            PositionKey positionKey
    ) {
        super(
                x,
                y,
                net.minecraft.text.Text.translatable("quickcraft.button.cross_box_sort"),
                button -> QuickCrossBoxSort.start(screen, playerInventory, QuickCraftKeyBindings.isAltDown()),
                positionKey
        );
    }

    @Override
    protected void drawIcon(DrawContext graphics, int x, int y, boolean hovered) {
        QuickButtonAccess.drawButtonIcon(graphics, ICON, x, y, 10, 10);
    }
}
