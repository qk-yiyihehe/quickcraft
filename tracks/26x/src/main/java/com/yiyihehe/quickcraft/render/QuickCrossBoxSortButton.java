package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class QuickCrossBoxSortButton extends QuickCompactIconButton {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath(
            "quickcraft",
            "textures/gui/cross_box_sort_compact.png"
    );

    public QuickCrossBoxSortButton(
            int x,
            int y,
            AbstractContainerScreen<?> screen,
            boolean playerInventory,
            PositionKey positionKey
    ) {
        super(
                x,
                y,
                Component.translatable("quickcraft.button.cross_box_sort"),
                button -> QuickCrossBoxSort.start(screen, playerInventory, QuickCraftKeyBindings.isAltDown()),
                positionKey
        );
    }

    @Override
    protected void extractIcon(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x, y, 0.0F, 0.0F, 10, 10, 10, 10);
    }
}
