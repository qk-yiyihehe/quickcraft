package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class QuickCrossBoxSortButton extends QuickCompactIconButton {
    private final Identifier icon;

    public QuickCrossBoxSortButton(int x, int y, boolean onePerBox) {
        this(x, y, onePerBox, button -> QuickCrossBoxSort.start(onePerBox),
                onePerBox ? PositionKey.BACKPACK_CROSS_BOX_SORT_PURE : PositionKey.BACKPACK_CROSS_BOX_SORT_COMPACT);
    }

    public QuickCrossBoxSortButton(int x, int y, boolean onePerBox, PressAction action, PositionKey positionKey) {
        super(x, y, onePerBox
                        ? net.minecraft.text.Text.translatable("quickcraft.button.cross_box_sort.pure")
                        : net.minecraft.text.Text.translatable("quickcraft.button.cross_box_sort.compact"),
                action, positionKey);
        this.icon = Identifier.of("quickcraft", onePerBox
                ? "textures/gui/cross_box_sort_pure.png" : "textures/gui/cross_box_sort_compact.png");
    }

    @Override
    protected void drawIcon(DrawContext graphics, int x, int y, boolean hovered) {
        QuickButtonAccess.drawButtonIcon(graphics, icon, x, y, 10, 10);
    }
}
