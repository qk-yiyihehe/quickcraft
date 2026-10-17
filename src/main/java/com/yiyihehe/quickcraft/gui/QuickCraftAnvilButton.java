package com.yiyihehe.quickcraft.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.text.OrderedText;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.List;

public final class QuickCraftAnvilButton extends QuickCraftAnvilButtonAccess {
    private net.minecraft.text.Text planTooltip = net.minecraft.text.Text.empty();
    private List<OrderedText> lines = List.of();
    private int wrapWidth;
    private int offset;
    private int maxOffset;
    private final TooltipPositioner positioner = (width, height, x, y, tooltipWidth, tooltipHeight) ->
            new Vector2i(Math.max(4, Math.min((isHovered() ? x : getX()) + 12, width - tooltipWidth - 4)),
                    (isHovered() ? y : getY() + getHeight()) + 8);

    public QuickCraftAnvilButton(int x, int y, PressAction onPress) {
        super(x, y, onPress);
    }

    public void setPlanTooltip(net.minecraft.text.Text text) {
        if (planTooltip.equals(text)) return;
        planTooltip = text;
        wrapWidth = 0;
        offset = 0;
    }

    @Override
    protected void renderPlanTooltip(DrawContext context, int mouseX, int mouseY) {
        var client = MinecraftClient.getInstance();
        if (!isHovered() && !(isFocused() && client.getNavigationType().isKeyboard())) return;
        int width = Math.max(1, Math.min(170, client.getWindow().getScaledWidth() - 16));
        if (width != wrapWidth) {
            lines = client.textRenderer.wrapLines(planTooltip, width);
            wrapWidth = width;
        }
        // 原版每行占 10 像素，另为首行间距与背景边框留出空间。
        int anchorY = isHovered() ? mouseY : getY() + getHeight();
        int rows = Math.max(2, (client.getWindow().getScaledHeight() - anchorY - 16) / 10);
        int visibleRows = lines.size() > rows ? rows - 1 : rows;
        maxOffset = Math.max(0, lines.size() - visibleRows);
        offset = Math.min(offset, maxOffset);
        var visible = new ArrayList<>(lines.subList(offset, Math.min(lines.size(), offset + visibleRows)));
        if (maxOffset > 0) {
            visible.add(net.minecraft.text.Text.translatable("quickcraft.anvil.scroll_plan", offset + 1,
                    Math.min(lines.size(), offset + visibleRows), lines.size()).asOrderedText());
        }
        drawPlanTooltip(context, client.textRenderer, visible, positioner,
                isHovered() ? mouseX : getX(), anchorY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxOffset == 0 || verticalAmount == 0) return false;
        offset = Math.max(0, Math.min(maxOffset, offset + (verticalAmount > 0 ? -3 : 3)));
        return true;
    }

    @Override
    public void appendClickableNarrations(NarrationMessageBuilder builder) {
        super.appendClickableNarrations(builder);
        builder.put(NarrationPart.HINT, planTooltip);
    }
}
