package com.yiyihehe.quickcraft.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import java.util.List;

public abstract class QuickCraftAnvilButtonAccess extends ButtonWidget.Text {
    protected QuickCraftAnvilButtonAccess(int x, int y, PressAction onPress) {
        super(x, y, 176, 20, net.minecraft.text.Text.empty(), onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
        super.drawIcon(context, mouseX, mouseY, delta);
        renderPlanTooltip(context, mouseX, mouseY);
    }

    protected abstract void renderPlanTooltip(DrawContext context, int mouseX, int mouseY);

    protected static void drawPlanTooltip(DrawContext context, TextRenderer font, List<OrderedText> lines,
                                          TooltipPositioner positioner, int x, int y) {
        context.drawTooltip(font, lines, positioner, x, y, true);
    }
}
