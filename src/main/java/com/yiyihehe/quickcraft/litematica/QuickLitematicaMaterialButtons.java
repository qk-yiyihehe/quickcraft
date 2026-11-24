package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import com.yiyihehe.quickcraft.malilib.QuickCraftMovableButton;
import com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor;
import com.yiyihehe.quickcraft.mixin.GuiBaseAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialButtonAccessor;
import com.yiyihehe.quickcraft.mixin.MalilibSearchBarAccessor;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialNavigation.View;

public final class QuickLitematicaMaterialButtons {
    record Action(String key, Runnable click, boolean enabled, Object... arguments) { }
    private QuickLitematicaMaterialButtons() { }

    public static void add(GuiMaterialList gui) {
        var list = gui.getMaterialList();
        View current = list instanceof QuickLitematicaContainerMaterials.ContainerMaterialList ? View.CONTAINERS
                : list instanceof QuickLitematicaEntityMaterials.EntityMaterialList ? View.ENTITIES : View.BLOCKS;
        String layoutKey = QuickLitematicaMaterialNavigation.layoutKey(list, current);
        var navigation = QuickLitematicaMaterialNavigation.bind(gui);
        if (navigation == null) {
            QuickLitematicaListPagination.add(gui, layoutKey);
            return;
        }
        var buttons = ((QuickCraftGuiButtonAccess) gui).quickcraft$getButtons();
        if (current == View.ENTITIES) {
            String refreshLabel = translate("litematica.gui.button.material_list.refresh_list");
            buttons.removeIf(button -> ((LitematicaMaterialButtonAccessor) button).quickcraft$getLabel().equals(refreshLabel));
        }
        var operations = new ArrayList<>(buttons);
        var editing = new ArrayList<ButtonBase>();
        if (QuickLitematicaContainerReplacements.enabled() && current != View.ENTITIES) {
            var container = list instanceof QuickLitematicaContainerMaterials.ContainerMaterialList c ? c : null;
            editing.add(addAction(gui, layoutKey, action("quickcraft.litematica.button.save_schematic_copy", () -> QuickLitematicaContainerMaterials.saveCopy(list))));
            editing.add(addAction(gui, layoutKey, action("quickcraft.litematica.button.clear_container_material_rules", () -> {
                if (container != null) { container.quickcraft$clearAllContainerRulesForContext(); }
                else { QuickLitematicaContainerReplacements.removeReplacementRulesForContext(
                        QuickLitematicaContainerReplacements.Scope.PROJECTION, QuickLitematicaMaterialLists.getReplacementContextKey(list)); }
                list.reCreateMaterialList();
                gui.initGui();
            })));
            ItemStack first = container == null ? QuickLitematicaProjectionMaterials.firstSelected(list)
                    : container.selectedMaterials.values().stream().findFirst().orElse(ItemStack.EMPTY);
            editing.add(addAction(gui, layoutKey, new Action("quickcraft.litematica.button.batch_replace_materials", () ->
                    QuickLitematicaMaterialReplacementScreen.open(gui, first, container == null
                            ? QuickLitematicaProjectionMaterials.binding(list, first) : container.quickcraft$createSummaryReplacementBinding(first)), !first.isEmpty())));
            editing.add(addAction(gui, layoutKey, new Action("quickcraft.litematica.button.clear_selection", () -> {
                if (container == null) { QuickLitematicaProjectionMaterials.clearSelection(list); }
                else { container.selectedMaterials.clear(); }
                gui.initGui();
            }, !first.isEmpty())));
        }
        int reserved = gui.getStringWidth(translate("litematica.gui.label.material_list.multiplier")) + 64;
        layout(gui, navigation, current, layoutKey, operations, editing, reserved);
    }

    static int addDetails(GuiBase gui, QuickLitematicaContainerMaterials.ContainerMaterialList list,
                           List<Action> actions) {
        String layoutKey = QuickLitematicaMaterialNavigation.layoutKey(list, View.DETAILS);
        var operations = new ArrayList<>(((QuickCraftGuiButtonAccess) gui).quickcraft$getButtons());
        var editing = new ArrayList<ButtonBase>();
        for (var action : actions) { editing.add(addAction(gui, layoutKey, action)); }
        return layout(gui, QuickLitematicaMaterialNavigation.forContainers(list), View.DETAILS, layoutKey, operations, editing, 0);
    }

    static Action action(String key, Runnable click) { return new Action(key, click, true); }
    private static String translate(String key) { return StringUtils.translate(key); }

    private static ButtonBase addAction(GuiBase gui, String layoutKey, Action action) {
        String positionKey = action.key().equals("quickcraft.litematica.button.batch_replace")
                ? "quickcraft.litematica.button.batch_replace_materials" : action.key();
        var button = new QuickCraftMovableButton(layoutKey + "." + positionKey, 0, 0, -1, 20,
                StringUtils.translate(action.key(), action.arguments()));
        button.setEnabled(action.enabled());
        gui.addButton(button, (clicked, mouseButton) -> action.click().run());
        return button;
    }

    private static int layout(GuiBase gui, QuickLitematicaMaterialNavigation navigation, View current, String layoutKey,
                               List<ButtonBase> operations, List<ButtonBase> editing, int reserved) {
        var tabs = new ArrayList<ButtonBase>();
        String menuLabel = translate(ButtonListenerChangeMenu.ButtonType.MAIN_MENU.getLabelKey());
        // 沿用原生投影菜单回调，移到材料导航最前面。
        for (var button : List.copyOf(operations)) {
            if (((LitematicaMaterialButtonAccessor) button).quickcraft$getLabel().equals(menuLabel)) {
                operations.remove(button);
                tabs.add(button);
                break;
            }
        }
        for (var view : navigation.views(current)) {
            var button = addAction(gui, layoutKey,
                    new Action(QuickLitematicaMaterialNavigation.labelKey(view), () -> navigation.show(view), view != current));
            tabs.add(button);
        }
        int available = Math.max(1, gui.getScreenWidth() - 24);
        // 原生一排能放下时保留原位置、宽度及间距；窄窗口的原生底部溢出行移到顶部，避开导航。
        boolean nativeRowFits = current != View.ENTITIES && operations.stream().allMatch(button -> button.getY() == 24
                && button.getX() + button.getWidth() <= gui.getScreenWidth() - 12 - reserved);
        int editingY = operations.isEmpty() ? 24 : nativeRowFits ? 48
                : position(gui, operations, 24, available, reserved, false);
        int defaultListY = position(gui, editing, editingY, available, 0, true);
        var footer = QuickLitematicaMaterialLayout.footer(gui.getScreenHeight(), available,
                tabs.stream().mapToInt(ButtonBase::getWidth).toArray());
        position(gui, tabs, footer.buttonsY(), available, 0, false);
        // 原生总计标签在 initGui 中重新生成；多行导航时随预留区域一起上移。
        for (var widget : ((GuiBaseAccessor) gui).quickcraft$getWidgets()) {
            if (widget instanceof WidgetLabel && widget.getX() == 12 && widget.getY() == gui.getScreenHeight() - 36) {
                widget.setY(footer.totalY());
            }
        }
        int bottomMargin = gui.getScreenHeight() - footer.listBottom();
        if (gui instanceof QuickLitematicaMaterialNavigation.NavigationScreen screen) {
            screen.quickcraft$setBottomMargin(bottomMargin);
        }
        int listY = listTop(editing, editingY, defaultListY);
        resizeList(gui, listY, footer.listBottom(), null);
        var pagination = QuickLitematicaListPagination.add(gui, layoutKey, gui.getScreenWidth() - 12, gui.getScreenHeight() - 24);
        Runnable reflow = () -> {
            int updatedY = listTop(editing, editingY, defaultListY);
            if (((MalilibGuiListAccessor) gui).quickcraft$getListY() != updatedY) {
                resizeList(gui, updatedY, footer.listBottom(), pagination);
            }
        };
        for (var button : editing) {
            if (button instanceof QuickCraftMovableButton movable) {
                movable.setPositionChangeListener(reflow);
            }
        }
        return bottomMargin;
    }

    private static int listTop(List<ButtonBase> editing, int nativeEnd, int defaultEnd) {
        int listY = nativeEnd;
        for (var button : editing) {
            // 拖进列表区的浮动按钮不占工具栏高度。
            if (button.getY() < defaultEnd) {
                listY = Math.max(listY, button.getY() + button.getHeight() + 4);
            }
        }
        return listY;
    }

    private static int position(GuiBase gui, List<ButtonBase> buttons, int y, int available, int reserved, boolean keepSelectionTogether) {
        int[] widths = buttons.stream().mapToInt(ButtonBase::getWidth).toArray();
        boolean selectionPair = keepSelectionTogether && buttons.size() >= 2
                && ((LitematicaMaterialButtonAccessor) buttons.getLast()).quickcraft$getLabel()
                .equals(translate("quickcraft.litematica.button.clear_selection"));
        var layout = QuickLitematicaMaterialLayout.rows(available, reserved, widths, selectionPair ? 2 : 0);
        for (int i = 0; i < buttons.size(); i++) {
            var button = buttons.get(i);
            var slot = layout.slots().get(i);
            // 超长译文只缩短文字；所有操作仍保留独立按钮和原鼠标回调。
            if (button.getWidth() > slot.width()) {
                var label = ((LitematicaMaterialButtonAccessor) button).quickcraft$getLabel();
                if (gui.getStringWidth(label) > slot.width() - 6) { button.setDisplayString(fit(gui, label, slot.width())); }
                button.setWidth(slot.width());
            }
            if (button instanceof QuickCraftMovableButton movable) {
                movable.place(gui, 12 + slot.x(), y + slot.y());
            } else {
                button.setPosition(12 + slot.x(), y + slot.y());
            }
        }
        return y + layout.height();
    }

    private static void resizeList(GuiBase gui, int listY, int listBottom, QuickLitematicaListPagination pagination) {
        var access = (MalilibGuiListAccessor) gui;
        var widget = access.quickcraft$getListWidget();
        int scroll = widget.getScrollbar().getValue();
        if (access.quickcraft$getListY() != listY) {
            var search = widget.getSearchBarWidget();
            boolean open = search != null && search.isSearchOpen();
            String text = search == null ? "" : ((MalilibSearchBarAccessor) search).quickcraft$getSearchBox().getText();
            access.quickcraft$setListPosition(10, listY);
            access.quickcraft$reCreateListWidget();
            widget = access.quickcraft$getListWidget();
            if (search != null && widget.getSearchBarWidget() != null) {
                search = widget.getSearchBarWidget();
                ((MalilibSearchBarAccessor) search).quickcraft$getSearchBox().setText(text);
                search.setSearchOpen(open);
            }
            if (pagination != null) {
                pagination.attach(widget);
            }
        }
        // setSize 不会重建已有行；必须在最终位置、尺寸确定后刷新控件。
        widget.setSize(access.quickcraft$getBrowserWidth(), Math.max(0, listBottom - listY));
        widget.initGui();
        widget.getScrollbar().setValue(scroll);
        widget.refreshEntries();
    }

    static String fit(GuiBase gui, String label, int width) {
        int end = label.length();
        while (end > 0 && gui.getStringWidth(label.substring(0, end) + (end == label.length() ? "" : "…")) > width - 10) {
            end = label.offsetByCodePoints(end, -1);
        }
        return label.substring(0, end) + (end < label.length() ? "…" : "");
    }
}
