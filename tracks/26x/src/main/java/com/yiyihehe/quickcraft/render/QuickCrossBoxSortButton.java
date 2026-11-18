package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class QuickCrossBoxSortButton extends QuickCompactIconButton {
    private final Identifier icon;

    public QuickCrossBoxSortButton(int x, int y, boolean onePerBox) {
        this(x, y, onePerBox, button -> QuickCrossBoxSort.start(onePerBox),
                onePerBox ? PositionKey.BACKPACK_CROSS_BOX_SORT_PURE : PositionKey.BACKPACK_CROSS_BOX_SORT_COMPACT);
    }

    public QuickCrossBoxSortButton(int x, int y, boolean onePerBox, OnPress action, PositionKey positionKey) {
        super(x, y, onePerBox
                        ? Component.translatable("quickcraft.button.cross_box_sort.pure")
                        : Component.translatable("quickcraft.button.cross_box_sort.compact"),
                action, positionKey);
        this.icon = Identifier.fromNamespaceAndPath("quickcraft", onePerBox
                ? "textures/gui/cross_box_sort_pure.png" : "textures/gui/cross_box_sort_compact.png");
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F, 10, 10, 10, 10);
    }
}
