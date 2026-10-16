package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayList;
import java.util.List;

/** 使用 GUI 逻辑宽度排布，首行给原生倍数输入框留位。 */
final class QuickLitematicaMaterialLayout {
    private QuickLitematicaMaterialLayout() { }
    record Slot(int x, int y, int width) { }
    record Rows(List<Slot> slots, int height) { }

    record Footer(int buttonsY, int totalY, int listBottom) { }

    static Footer footer(int height, int available, int[] widths) {
        int buttonsY = height - rows(available, 0, widths).height();
        return new Footer(buttonsY, buttonsY - 16, buttonsY - 20);
    }

    static Rows rows(int available, int reserved, int[] widths) {
        return rows(available, reserved, widths, 0);
    }

    static Rows rows(int available, int reserved, int[] widths, int trailingGroupSize) {
        available = Math.max(1, available);
        int x = 0;
        int y = 0;
        var slots = new ArrayList<Slot>();
        int groupStart = widths.length - trailingGroupSize;
        for (int index = 0; index < widths.length; index++) {
            int width = Math.min(available, widths[index]);
            int limit = y == 0 ? available - reserved : available;
            int needed = width;
            if (trailingGroupSize > 1 && index == groupStart) {
                int groupWidth = java.util.Arrays.stream(widths, groupStart, widths.length).sum() + (trailingGroupSize - 1) * 2;
                // 一组本身能放进整行时才保持同行，极窄窗口仍允许逐个换行。
                if (groupWidth <= available) { needed = groupWidth; }
            }
            if (x + needed > limit) { x = 0; y += 24; }
            slots.add(new Slot(x, y, width));
            x += width + 2;
        }
        return new Rows(List.copyOf(slots), slots.isEmpty() ? 0 : y + 24);
    }
}
