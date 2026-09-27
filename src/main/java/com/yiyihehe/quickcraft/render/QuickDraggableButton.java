package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
//#if MC>=12110
//$$ import net.minecraft.client.gui.Click;
//#endif
//#if MC>=12111
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** Button that preserves normal clicks while reserving Shift + mouse gestures for layout editing. */
public class QuickDraggableButton extends ButtonWidget {
    public enum PositionKey {
        CONTAINER_LOCK("containerLock"),
        INVENTORY_LOCK("inventoryLock"),
        QUICK_STASH("quickStash"),
        QUICK_RETRIEVE("quickRetrieve"),
        WORKBENCH_CRAFT("workbenchCraft"),
        WORKBENCH_MODE("workbenchMode"),
        WORKBENCH_OUTPUT("workbenchOutput"),
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

    //#if MC>=12111
    //$$ public QuickDraggableButton(int x, int y, int width, int height, net.minecraft.text.Text message,
    //#else
    public QuickDraggableButton(int x, int y, int width, int height, Text message,
    //#endif
                                PressAction onPress, PositionKey positionKey) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuickCraftConfigs.isActionButtonDraggingEnabled()
                || !QuickCraftKeyBindings.isShiftDown()
                || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return false;
        }
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        for (Element child : screen.children()) {
            if (child instanceof QuickDraggableButton button && button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    //#if MC>=12110
    //$$ public boolean mouseClicked(Click click, boolean doubled) {
    //$$     double mouseX = click.x();
    //$$     double mouseY = click.y();
    //$$     int button = click.button();
    //#else
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //#endif
        if (!QuickCraftConfigs.isActionButtonDraggingEnabled()
                || !QuickCraftKeyBindings.isShiftDown()
                || !isPointerOver(mouseX, mouseY)) {
            //#if MC>=12110
            //$$ return super.mouseClicked(click, doubled);
            //#else
            return super.mouseClicked(mouseX, mouseY, button);
            //#endif
        }
        if (button == 0) {
            this.dragging = true;
            this.grabOffsetX = mouseX - this.getX();
            this.grabOffsetY = mouseY - this.getY();
            return true;
        }
        if (button == 1) {
            QuickCraftConfigs.resetActionButtonOffset(this.positionKey.configKey);
            this.setClampedPosition(this.defaultX, this.defaultY);
            QuickCraftConfigs.saveToFile();
            this.consumeRightRelease = true;
            return true;
        }
        return false;
    }

    private boolean isPointerOver(double mouseX, double mouseY) {
        //#if MC<12103
        return this.clicked(mouseX, mouseY);
        //#else
        //$$ return this.isMouseOver(mouseX, mouseY);
        //#endif
    }

    @Override
    //#if MC>=12110
    //$$ public boolean mouseDragged(Click click, double deltaX, double deltaY) {
    //$$     double mouseX = click.x();
    //$$     double mouseY = click.y();
    //$$     int button = click.button();
    //#else
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
    //#endif
        if (this.dragging && button == 0) {
            this.setClampedPosition((int)Math.round(mouseX - this.grabOffsetX), (int)Math.round(mouseY - this.grabOffsetY));
            return true;
        }
        //#if MC>=12110
        //$$ return super.mouseDragged(click, deltaX, deltaY);
        //#else
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        //#endif
    }

    @Override
    //#if MC>=12110
    //$$ public boolean mouseReleased(Click click) {
    //$$     int button = click.button();
    //#else
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //#endif
        if (this.dragging && button == 0) {
            this.dragging = false;
            QuickCraftConfigs.setActionButtonOffset(
                    this.positionKey.configKey,
                    this.getX() - this.defaultX,
                    this.getY() - this.defaultY
            );
            QuickCraftConfigs.saveToFile();
            return true;
        }
        //#if MC>=12110
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    //#if MC>=12111
    //$$ @Override
    //$$ protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    //$$     this.drawButton(context);
    //$$     this.drawLabel(context.getHoverListener(this, DrawContext.HoverType.NONE));
    //$$ }
    //#endif

    public boolean isPositionDragging() {
        return this.dragging;
    }

    public boolean consumeRightRelease() {
        boolean consume = this.consumeRightRelease;
        this.consumeRightRelease = false;
        return consume;
    }

    private void setClampedPosition(int x, int y) {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (screen == null) {
            this.setX(x);
            this.setY(y);
            return;
        }
        this.setX(MathHelper.clamp(x, 0, Math.max(0, screen.width - this.getWidth())));
        this.setY(MathHelper.clamp(y, 0, Math.max(0, screen.height - this.getHeight())));
    }
}
