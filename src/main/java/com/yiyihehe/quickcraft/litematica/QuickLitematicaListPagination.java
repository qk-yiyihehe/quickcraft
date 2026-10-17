package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.GuiBaseAccessor;
import com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只改变列表视口，保留原生完整数据、排序、过滤和条目索引。 */
public final class QuickLitematicaListPagination {
    public interface PagedList {
        void quickcraft$setPagination(QuickLitematicaListPagination pagination);
    }

    private final WidgetListBase<?, ?> list;
    private final GuiBase gui;
    private final int right;
    private final int y;
    private final ButtonGeneric previous;
    private final ButtonGeneric next;
    private final PageLabel label;
    private List<Integer> starts = List.of(0);
    private int page;

    private QuickLitematicaListPagination(GuiBase gui, WidgetListBase<?, ?> list, int right, int y) {
        this.list = list;
        this.gui = gui;
        this.right = right;
        this.y = y;
        this.previous = new ButtonGeneric(0, 0, 38, 20, "<", "quickcraft.litematica.pagination.previous");
        this.next = new ButtonGeneric(0, 0, 38, 20, ">", "quickcraft.litematica.pagination.next");
        this.label = new PageLabel();
        gui.addButton(this.previous, (button, mouse) -> this.move(-1));
        gui.addButton(this.next, (button, mouse) -> this.move(1));
        ((GuiBaseAccessor) gui).quickcraft$getWidgets().add(this.label);
    }

    public static boolean enabled() {
        return QuickCraftConfigs.ProjectionTools.ENABLE_LITEMATICA_LIST_PAGINATION.getBooleanValue();
    }

    public static void add(GuiBase gui) {
        int right = gui.getScreenWidth() - 12;
        int y = gui.getScreenHeight() - 36;
        // 原生验证页右侧保留投影菜单，翻页紧挨其左侧。
        for (var button : ((GuiBaseAccessor) gui).quickcraft$getButtons()) {
            if (button.getY() == y && button.getX() + button.getWidth() >= right) {
                right = Math.min(right, button.getX() - 4);
            }
        }
        add(gui, right, y);
    }

    public static void add(GuiBase gui, int right, int y) {
        if (!enabled()) { return; }
        var list = ((MalilibGuiListAccessor) gui).quickcraft$getListWidget();
        ((PagedList) list).quickcraft$setPagination(new QuickLitematicaListPagination(gui, list, right, y));
        list.refreshEntries();
    }

    public int prepare(int requestedStart, int availableHeight, int[] heights) {
        this.starts = pageStarts(availableHeight, heights);
        this.page = pageFor(this.starts, requestedStart);
        this.previous.setEnabled(this.page > 0);
        this.next.setEnabled(this.page + 1 < this.starts.size());
        this.label.update(this.page + 1, this.starts.size());
        int x = this.right - 92 - this.gui.getStringWidth(this.label.text());
        this.previous.setPosition(x, this.y);
        this.next.setPosition(x + 42, this.y);
        this.label.setPosition(x + 92, this.y);
        return this.starts.get(this.page);
    }

    public int lastStart() { return this.starts.getLast(); }

    public void move(int direction) {
        int target = Math.clamp(this.page + direction, 0, this.starts.size() - 1);
        this.list.getScrollbar().setValue(this.starts.get(target));
        this.list.refreshEntries();
    }

    static int pageFor(List<Integer> starts, int requestedStart) {
        int index = Collections.binarySearch(starts, Math.max(0, requestedStart));
        return index >= 0 ? index : -index - 2;
    }

    static List<Integer> pageStarts(int availableHeight, int[] heights) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        int used = 0;
        for (int index = 0; index < heights.length; index++) {
            // 超高条目独占一页，极小窗口也必须让分页边界前进。
            if (index > starts.getLast() && used + heights[index] > availableHeight) {
                starts.add(index);
                used = 0;
            }
            used += heights[index];
        }
        return starts;
    }

    private static final class PageLabel extends WidgetLabel {
        private PageLabel() { super(0, 0, 100, 20, 0xFFFFFFFF); }

        private String text() { return this.labels.getFirst(); }

        private void update(int page, int total) {
            this.labels.clear();
            this.addLine(StringUtils.translate("quickcraft.litematica.pagination.page", page, total));
        }
    }
}
