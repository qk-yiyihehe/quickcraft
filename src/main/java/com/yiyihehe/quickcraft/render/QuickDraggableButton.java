package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Button that preserves normal clicks while reserving Shift + mouse gestures for layout editing. */
public class QuickDraggableButton extends Button {
    public enum PositionKey {
        CONTAINER_LOCK("containerLock"),
        INVENTORY_LOCK("inventoryLock"),
        QUICK_STASH("quickStash"),
        QUICK_RETRIEVE("quickRetrieve"),
        WORKBENCH_CRAFT("workbenchCraft"),
        BACKPACK_CRAFT("backpackCraft"),
        STONECUTTER_CRAFT("stonecutterCraft");

        private final String configKey;

        PositionKey(String configKey) {
            this.configKey = configKey;
        }
    }

    private final PositionKey positionKey;
    private int defaultX;
    private int defaultY;
    private double grabOffsetX;
    private double grabOffsetY;
    private boolean dragging;
    private boolean consumeRightRelease;

    public QuickDraggableButton(int x, int y, int width, int height, Component message,
                                OnPress onPress, PositionKey positionKey) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.positionKey = positionKey;
        this.setDefaultPosition(x, y);
    }

    public void setDefaultPosition(int x, int y) {
        this.defaultX = x;
        this.defaultY = y;
        if (!this.dragging) {
            QuickCraftConfigs.ButtonOffset offset = QuickCraftConfigs.getActionButtonOffset(this.positionKey.configKey);
            this.setClampedPosition(x + offset.x(), y + offset.y());
        }
    }

    public static boolean isEditGestureOverCurrentButton() {
        Minecraft client = Minecraft.getInstance();
        if (!QuickCraftConfigs.isActionButtonDraggingEnabled()
                || !client.hasShiftDown()
                || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        double mouseX = client.mouseHandler.getScaledXPos(client.getWindow());
        double mouseY = client.mouseHandler.getScaledYPos(client.getWindow());
        for (GuiEventListener child : screen.children()) {
            if (child instanceof QuickDraggableButton button && button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!QuickCraftConfigs.isActionButtonDraggingEnabled()
                || !event.hasShiftDown()
                || !this.isMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick);
        }
        if (event.button() == 0) {
            this.dragging = true;
            this.grabOffsetX = event.x() - this.getX();
            this.grabOffsetY = event.y() - this.getY();
            return true;
        }
        if (event.button() == 1) {
            QuickCraftConfigs.resetActionButtonOffset(this.positionKey.configKey);
            this.setClampedPosition(this.defaultX, this.defaultY);
            QuickCraftConfigs.saveToFile();
            this.consumeRightRelease = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.dragging && event.button() == 0) {
            this.setClampedPosition((int)Math.round(event.x() - this.grabOffsetX),
                    (int)Math.round(event.y() - this.grabOffsetY));
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragging && event.button() == 0) {
            this.dragging = false;
            QuickCraftConfigs.setActionButtonOffset(
                    this.positionKey.configKey,
                    this.getX() - this.defaultX,
                    this.getY() - this.defaultY
            );
            QuickCraftConfigs.saveToFile();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.extractDefaultSprite(graphics);
        this.extractDefaultLabel(graphics.textRendererForWidget(
                this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }

    public boolean isPositionDragging() {
        return this.dragging;
    }

    public boolean consumeRightRelease() {
        boolean consume = this.consumeRightRelease;
        this.consumeRightRelease = false;
        return consume;
    }

    private void setClampedPosition(int x, int y) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            this.setX(x);
            this.setY(y);
            return;
        }
        this.setX(Mth.clamp(x, 0, Math.max(0, screen.width - this.getWidth())));
        this.setY(Mth.clamp(y, 0, Math.max(0, screen.height - this.getHeight())));
    }
}
