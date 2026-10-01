package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

public abstract class QuickLitematicaEntityPlacementScreenBase extends Screen {
    protected QuickLitematicaEntityPlacementScreenBase(Text title) {
        super(title);
    }

    protected void renderScreenBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    protected void drawChestTexture(DrawContext context, Identifier texture, int x, int y, int w, int h) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, 256, 256);
    }

    protected void drawHopperTexture(DrawContext context, Identifier texture, int x, int y, int u, int v, int w, int h) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, 256, 256);
    }

    protected void drawStackOverlay(DrawContext context, TextRenderer textRenderer, ItemStack stack, int x, int y) {
        context.drawStackOverlay(textRenderer, stack, x, y);
    }

    protected int titleTextColor() {
        return 0xFFFFFFFF;
    }

    protected int errorTextColor() {
        return 0xFFFF5555;
    }

    protected int emptyTextColor() {
        return 0xFF404040;
    }

    protected List<ItemStack> getPlayerMainStacks(PlayerEntity player) {
        return player.getInventory().getMainStacks();
    }

    protected abstract boolean handleCellClick(double mouseX, double mouseY, int button);

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && handleCellClick(click.x(), click.y(), click.button())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }
}
