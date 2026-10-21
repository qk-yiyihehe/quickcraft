package com.yiyihehe.quickcraft.gui;

import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilAssistant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.List;

public final class QuickCraftAnvilButton extends Button.Plain implements QuickCraftAnvilAssistant.ButtonView {
    private Component planTooltip = Component.empty();
    private List<FormattedCharSequence> lines = List.of();
    private int wrapWidth;
    private int offset;
    private int maxOffset;
    private final ClientTooltipPositioner positioner = (width, height, x, y, tooltipWidth, tooltipHeight) ->
            new Vector2i(Math.max(4, Math.min((isHovered() ? x : getX()) + 12, width - tooltipWidth - 4)),
                    (isHovered() ? y : getY() + getHeight()) + 8);

    public QuickCraftAnvilButton(int x, int y, OnPress onPress) {
        super(x, y, 176, 20, Component.empty(), onPress, DEFAULT_NARRATION);
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public void setPlanTooltip(Component text) {
        if (planTooltip.equals(text)) return;
        planTooltip = text;
        wrapWidth = 0;
        offset = 0;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractContents(graphics, mouseX, mouseY, delta);
        var client = Minecraft.getInstance();
        if (!isHovered() && !(isFocused() && client.getLastInputType().isKeyboard())) return;
        int width = Math.max(1, Math.min(170, client.getWindow().getGuiScaledWidth() - 16));
        if (width != wrapWidth) {
            lines = client.font.split(planTooltip, width);
            wrapWidth = width;
        }
        // 原版每行占 10 像素，另为首行间距与背景边框留出空间。
        int anchorY = isHovered() ? mouseY : getY() + getHeight();
        int rows = Math.max(2, (client.getWindow().getGuiScaledHeight() - anchorY - 16) / 10);
        int visibleRows = lines.size() > rows ? rows - 1 : rows;
        maxOffset = Math.max(0, lines.size() - visibleRows);
        offset = Math.min(offset, maxOffset);
        var visible = new ArrayList<>(lines.subList(offset, Math.min(lines.size(), offset + visibleRows)));
        if (maxOffset > 0) {
            visible.add(Component.translatable("quickcraft.anvil.scroll_plan", offset + 1,
                    Math.min(lines.size(), offset + visibleRows), lines.size()).getVisualOrderText());
        }
        graphics.setTooltipForNextFrame(client.font, visible, positioner,
                isHovered() ? mouseX : getX(), anchorY, true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxOffset == 0 || verticalAmount == 0) return false;
        offset = Math.max(0, Math.min(maxOffset, offset + (verticalAmount > 0 ? -3 : 3)));
        return true;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        super.updateWidgetNarration(output);
        output.add(NarratedElementType.HINT, planTooltip);
    }
}
