package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor;
import com.yiyihehe.quickcraft.mixin.MalilibWidgetContainerInvoker;
import com.yiyihehe.quickcraft.mixin.MalilibWidgetListAccessor;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class QuickCraftMovableButton extends ButtonGeneric {
    private final String positionKey;
    private int defaultX;
    private int defaultY;
    private int minX;
    private int minY;
    private int right;
    private int bottom;
    private Runnable positionChangeListener;

    public QuickCraftMovableButton(
            String positionKey,
            int x,
            int y,
            int width,
            int height,
            String label,
            String... hover
    ) {
        super(x, y, width, height, label, hover);
        this.positionKey = positionKey;
    }

    public QuickCraftMovableButton(String positionKey, int x, int y, int width, boolean rightAligned, String label) {
        super(x, y, width, rightAligned, label);
        this.positionKey = positionKey;
    }

    public void place(GuiBase gui, int x, int y) {
        this.placeWithin(x, y, 2, 2, gui.getScreenWidth() - 2, gui.getScreenHeight() - 2);
    }

    public void placeInRow(int x, int y, int width, int height) {
        this.placeWithin(this.getX(), this.getY(), x, y, x + width, y + height);
    }

    public void setPositionChangeListener(Runnable listener) {
        this.positionChangeListener = listener;
    }

    private void notifyPositionChanged() {
        if (this.positionChangeListener != null) {
            this.positionChangeListener.run();
        }
    }

    private void placeWithin(int x, int y, int left, int top, int right, int bottom) {
        this.defaultX = x;
        this.defaultY = y;
        this.minX = left;
        this.minY = top;
        this.right = right;
        this.bottom = bottom;
        this.restorePosition();
    }

    private void restorePosition() {
        var offset = QuickCraftConfigs.getActionButtonOffset(this.positionKey);
        this.moveTo((long) this.defaultX + offset.x(), (long) this.defaultY + offset.y());
    }

    private void moveTo(long x, long y) {
        this.setPosition(
                (int) Math.clamp(x, this.minX, Math.max(this.minX, this.right - this.getWidth())),
                (int) Math.clamp(y, this.minY, Math.max(this.minY, this.bottom - this.getHeight()))
        );
    }

    private boolean isEditableAt(double x, double y) {
        return this.visible && x >= this.getX() && x < this.getX() + this.getWidth() && y >= this.getY() && y < this
                .getY() + this.getHeight() && x < this.right && y < this.bottom;
    }

    private boolean isMoved() {
        return this.getX() != this.defaultX || this.getY() != this.defaultY;
    }

    static final class Editor {
        private final GuiBase gui;
        private QuickCraftMovableButton dragging;
        private double grabX;
        private double grabY;
        private boolean consumeRightRelease;

        Editor(GuiBase gui) {
            this.gui = gui;
        }

        boolean click(
                double x,
                double y,
                int mouseButton,
                boolean controlDown,
                Predicate<QuickCraftMovableButton> press
        ) {
            if (mouseButton != 0 && mouseButton != 1) {
                return false;
            }
            boolean editing = QuickCraftConfigs.isActionButtonDraggingEnabled() && QuickCraftKeyBindings.isShiftDown();
            if (!editing && (mouseButton != 0 || controlDown)) {
                return false;
            }
            var buttons = this.buttons();
            // 行容器会遍历所有命中的按钮。玩家移出默认位置后，单击只派发给最上层的可用按钮。
            if (!editing && buttons.stream().noneMatch(button -> button.isMoved() && button.isEditableAt(x, y))) {
                return false;
            }
            for (int index = buttons.size() - 1; index >= 0; index--) {
                var button = buttons.get(index);
                if (!button.isEditableAt(x, y)) {
                    continue;
                }
                if (!editing) {
                    if (press.test(button)) {
                        return true;
                    }
                    continue;
                }
                this.cancel();
                if (mouseButton == 0) {
                    this.dragging = button;
                    this.grabX = x - button.getX();
                    this.grabY = y - button.getY();
                } else {
                    QuickCraftConfigs.resetActionButtonOffset(button.positionKey);
                    this.restoreButtons(button.positionKey, buttons);
                    QuickCraftConfigs.saveToFile();
                    this.consumeRightRelease = true;
                    button.notifyPositionChanged();
                }
                return true;
            }
            return false;
        }

        boolean isDragging() {
            return this.dragging != null;
        }

        void drag(double x, double y, boolean mouseDown) {
            if (this.dragging == null) {
                return;
            }
            if (mouseDown && QuickCraftConfigs.isActionButtonDraggingEnabled()) {
                this.dragging.moveTo(Math.round(x - this.grabX), Math.round(y - this.grabY));
            } else {
                this.cancel();
            }
        }

        boolean release(double x, double y, int mouseButton) {
            if (mouseButton == 1 && this.consumeRightRelease) {
                this.consumeRightRelease = false;
                return true;
            }
            var button = this.dragging;
            if (mouseButton != 0 || button == null) {
                return false;
            }
            this.dragging = null;
            var buttons = this.buttons();
            if (QuickCraftConfigs.isActionButtonDraggingEnabled() && buttons.contains(button)) {
                button.moveTo(Math.round(x - this.grabX), Math.round(y - this.grabY));
                QuickCraftConfigs.setActionButtonOffset(
                        button.positionKey,
                        button.getX() - button.defaultX,
                        button.getY() - button.defaultY
                );
                this.restoreButtons(button.positionKey, buttons);
                QuickCraftConfigs.saveToFile();
                button.notifyPositionChanged();
            } else {
                button.restorePosition();
            }
            return true;
        }

        void cancel() {
            if (this.dragging != null) {
                this.dragging.restorePosition();
                this.dragging = null;
            }
        }

        private List<QuickCraftMovableButton> buttons() {
            var result = new ArrayList<QuickCraftMovableButton>();
            if (this.gui instanceof MalilibGuiListAccessor listGui) {
                var list = listGui.quickcraft$getListWidget();
                if (list instanceof MalilibWidgetListAccessor entries) {
                    for (var row : entries.quickcraft$getVisibleEntries()) {
                        this.addButtons(result, ((MalilibWidgetContainerInvoker) row).quickcraft$getSubWidgets());
                    }
                }
            }
            this.addButtons(result, ((QuickCraftGuiButtonAccess) this.gui).quickcraft$getButtons());
            return result;
        }

        private void addButtons(List<QuickCraftMovableButton> result, List<? extends WidgetBase> source) {
            for (var widget : source) {
                if (widget instanceof QuickCraftMovableButton button) {
                    result.add(button);
                }
            }
        }

        private void restoreButtons(String key, List<QuickCraftMovableButton> buttons) {
            for (var button : buttons) {
                if (button.positionKey.equals(key)) {
                    button.restorePosition();
                }
            }
        }
    }
}
