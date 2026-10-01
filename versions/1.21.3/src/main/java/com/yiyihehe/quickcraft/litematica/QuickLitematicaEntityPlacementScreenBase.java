package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderLayer;
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
        this.renderBackground(context, mouseX, mouseY, delta);
    }

    protected void drawChestTexture(DrawContext context, Identifier texture, int x, int y, int w, int h) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 0, 0, w, h, 256, 256);
    }

    protected void drawHopperTexture(DrawContext context, Identifier texture, int x, int y, int u, int v, int w, int h) {
        context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, u, v, w, h, 256, 256);
    }

    protected void drawStackOverlay(DrawContext context, TextRenderer textRenderer, ItemStack stack, int x, int y) {
        context.drawStackOverlay(textRenderer, stack, x, y);
    }

    protected int titleTextColor() {
        return 0xFFFFFF;
    }

    protected int errorTextColor() {
        return 0xFF5555;
    }

    protected int emptyTextColor() {
        return 0x404040;
    }

    protected List<ItemStack> getPlayerMainStacks(PlayerEntity player) {
        return player.getInventory().main;
    }

    protected abstract boolean handleCellClick(double mouseX, double mouseY, int button);

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handleCellClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
