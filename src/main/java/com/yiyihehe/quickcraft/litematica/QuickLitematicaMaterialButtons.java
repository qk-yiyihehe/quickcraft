package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.List;

/** 在原生按钮布局完成后追加入口，窄屏的额外行同步缩短列表，避免遮挡和越界。 */
public final class QuickLitematicaMaterialButtons {
    private record Action(String key, Runnable click, boolean enabled) { }
    private QuickLitematicaMaterialButtons() { }

    public static void add(GuiMaterialList gui) {
        var access = (com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor) gui;
        var list = gui.getMaterialList();
        var container = list instanceof QuickLitematicaContainerMaterials.ContainerMaterialList c ? c : null;
        boolean showContainers = QuickLitematicaContainerMaterials.shouldShowButton();
        boolean showReplacements = QuickLitematicaContainerReplacements.enabled();
        if ((!showContainers && !showReplacements)
                || container == null && !(list instanceof MaterialListPlacement || list instanceof MaterialListSchematic)) { return; }
        List<Action> actions = new ArrayList<>();
        if (showContainers) {
            actions.add(new Action(QuickLitematicaContainerMaterials.BUTTON_KEY, () -> open(gui, false), container == null));
            actions.add(new Action("quickcraft.litematica.button.container_material_details", () -> open(gui, true), true));
        }
        if (showReplacements) {
            actions.add(new Action("quickcraft.litematica.button.save_schematic_copy", () -> QuickLitematicaContainerMaterials.saveCopy(list), true));
            actions.add(new Action("quickcraft.litematica.button.clear_container_material_rules", () -> {
                if (container != null) { container.quickcraft$clearAllContainerRulesForContext(); }
                else { QuickLitematicaContainerReplacements.removeReplacementRulesForContext(
                        QuickLitematicaContainerReplacements.Scope.PROJECTION, QuickLitematicaMaterialLists.getReplacementContextKey(list)); }
                list.reCreateMaterialList(); gui.initGui();
            }, true));
            actions.add(new Action("quickcraft.litematica.button.batch_replace_materials", () -> {
                var first = container == null ? QuickLitematicaProjectionMaterials.firstSelected(list)
                        : container.selectedMaterials.values().stream().findFirst().orElse(net.minecraft.item.ItemStack.EMPTY);
                if (!first.isEmpty()) { QuickLitematicaMaterialReplacementScreen.open(gui, first,
                        container == null ? QuickLitematicaProjectionMaterials.binding(list, first)
                                : container.quickcraft$createSummaryReplacementBinding(first)); }
            }, true));
            actions.add(new Action("quickcraft.litematica.button.clear_selection", () -> {
                if (container == null) { QuickLitematicaProjectionMaterials.clearSelection(list); }
                else { container.selectedMaterials.clear(); }
                access.quickcraft$getListWidget().refreshEntries();
            }, true));
        }
        var buttons = ((QuickCraftGuiButtonAccess) gui).quickcraft$getButtons();
        var labels = actions.stream().map(a -> StringUtils.translate(a.key())).toList();
        var widths = labels.stream().mapToInt(label -> Math.min(gui.getScreenWidth() - 24, gui.getStringWidth(label) + 10)).toArray();
        int topX = buttons.stream().filter(b -> b.getY() == 24).mapToInt(b -> b.getX() + b.getWidth() + 1).max().orElse(12);
        int reserved = gui.getStringWidth(StringUtils.translate("litematica.gui.label.material_list.multiplier")) + 60;
        boolean wrapped = buttons.stream().anyMatch(b -> b.getY() == gui.getScreenHeight() - 22);
        boolean top = !wrapped && topX + java.util.Arrays.stream(widths).sum() + widths.length - 1 <= gui.getScreenWidth() - reserved;
        int rows = 1;
        int rowWidth = 0;
        if (!top) {
            for (int width : widths) {
                if (rowWidth > 0 && rowWidth + width > gui.getScreenWidth() - 24) { rows++; rowWidth = 0; }
                rowWidth += width + 1;
            }
        }
        int x = top ? topX : 12;
        int y = top ? 24 : Math.max(24, gui.getScreenHeight() - 58 - (rows - 1) * 22);
        if (!top && access.quickcraft$getListWidget() != null) {
            var widget = access.quickcraft$getListWidget();
            widget.setSize(access.quickcraft$getBrowserWidth(), Math.max(24, y - access.quickcraft$getListY() - 4));
        }
        for (int index = 0; index < actions.size(); index++) {
            int width = widths[index];
            if (!top && x > 12 && x + width > gui.getScreenWidth() - 12) { x = 12; y += 22; }
            var action = actions.get(index);
            var button = new ButtonGeneric(x, y, width, 20, labels.get(index));
            button.setEnabled(action.enabled());
            if (action.key().equals("quickcraft.litematica.button.batch_replace_materials")) {
                button.setHoverStrings(StringUtils.translate("quickcraft.litematica.tooltip.batch_select"));
            }
            gui.addButton(button, (clicked, mouseButton) -> action.click().run());
            x += width + 1;
        }
    }

    private static void open(GuiMaterialList gui, boolean details) {
        var list = gui.getMaterialList();
        if (list instanceof QuickLitematicaContainerMaterials.ContainerMaterialList container) {
            if (details) { QuickLitematicaContainerMaterialsScreens.openDetailScreen(container); }
            return;
        }
        if (list instanceof LitematicaMaterialListPlacementAccessor accessor) {
            if (details) { QuickLitematicaContainerMaterials.openDetailsForPlacement(accessor.quickcraft$getPlacement(), gui); }
            else { QuickLitematicaContainerMaterials.openForPlacement(accessor.quickcraft$getPlacement(), gui); }
        } else if (list instanceof LitematicaMaterialListSchematicAccessor accessor) {
            if (details) { QuickLitematicaContainerMaterials.openDetailsForSchematic(accessor.quickcraft$getSchematic(), accessor.quickcraft$getRegions(), gui); }
            else { QuickLitematicaContainerMaterials.openForSchematic(accessor.quickcraft$getSchematic(), accessor.quickcraft$getRegions(), gui); }
        }
    }
}
