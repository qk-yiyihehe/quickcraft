package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.gui.screen.Screen;

public final class QuickLitematicaSelectionDrag {
    private static QuickLitematicaSelectionDrag active;

    private final Screen screen;
    private final boolean selected;
    private final SelectionTarget target;
    private final Runnable refresh;
    private int lastX;
    private int lastY;

    private QuickLitematicaSelectionDrag(
            Screen screen,
            int mouseX,
            int mouseY,
            boolean selected,
            SelectionTarget target,
            Runnable refresh
    ) {
        this.screen = screen;
        this.lastX = mouseX;
        this.lastY = mouseY;
        this.selected = selected;
        this.target = target;
        this.refresh = refresh;
    }

    static void start(
            Screen screen,
            int mouseX,
            int mouseY,
            boolean selected,
            SelectionTarget target,
            Runnable refresh
    ) {
        active = new QuickLitematicaSelectionDrag(screen, mouseX, mouseY, selected, target, refresh);
    }

    public static void stop() {
        active = null;
    }

    public static void move(Screen screen, double mouseX, double mouseY) {
        var drag = active;
        if (drag == null) {
            return;
        }
        if (drag.screen != screen || !QuickLitematicaMaterialGui.controlDown() || !QuickLitematicaContainerReplacements
                .enabled()) {
            stop();
            return;
        }

        drag.selectAlongPath((int) mouseX, (int) mouseY);
    }

    private void selectAlongPath(int mouseX, int mouseY) {
        int dx = mouseX - this.lastX;
        int dy = mouseY - this.lastY;
        // 快速滑动时，一次移动事件也可能越过多个槽位。
        int steps = Math.ceilDiv(Math.max(Math.abs(dx), Math.abs(dy)), 4);
        boolean changed = false;
        for (int step = 1; step <= steps; step++) {
            int x = this.lastX + dx * step / steps;
            int y = this.lastY + dy * step / steps;
            changed |= this.target.setSelected(x, y, this.selected);
        }
        this.lastX = mouseX;
        this.lastY = mouseY;
        if (changed) {
            this.refresh.run();
        }
    }

    @FunctionalInterface
    interface SelectionTarget {
        boolean setSelected(int mouseX, int mouseY, boolean selected);
    }
}
