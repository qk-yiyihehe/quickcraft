package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.InputConstants;
import com.yiyihehe.quickcraft.mixin.MalilibGuiListAccessor;
import com.yiyihehe.quickcraft.mixin.MalilibWidgetContainerInvoker;
import com.yiyihehe.quickcraft.mixin.MalilibWidgetListAccessor;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialGui.Context;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialReplacementScreen;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerReplacements;

/** 为 Litematica 材料行增加替换入口，按钮沿用材料表原有的 malilib 控件。 */
public final class QuickLitematicaMaterialRow {
    private final MalilibWidgetContainerInvoker owner;
    private MaterialListEntry entry;
    private int quickcraft$rowX;
    private int quickcraft$rowY;
    private int quickcraft$rowWidth;
    private int quickcraft$rowHeight;
    private WidgetListMaterialList quickcraft$listWidget;
    private QuickLitematicaContainerReplacements.Scope quickcraft$scope;
    private String quickcraft$contextKey;
    private boolean quickcraft$replacementSupported;
    private MaterialListBase quickcraft$materialList;

    public QuickLitematicaMaterialRow(
            int x,
            int y,
            int width,
            int height,
            boolean isOdd,
            MaterialListBase materialList,
            MaterialListEntry entry,
            int listIndex,
            WidgetListMaterialList listWidget,
            MalilibWidgetContainerInvoker owner
    ) {
        this.owner = owner;
        this.entry = entry;
        this.quickcraft$materialList = materialList;
        this.quickcraft$rowX = x;
        this.quickcraft$rowY = y;
        this.quickcraft$rowWidth = width;
        this.quickcraft$rowHeight = height;
        this.quickcraft$listWidget = listWidget;
        boolean isContainerList = materialList instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource;
        boolean isProjectionList = materialList instanceof fi.dy.masa.litematica.materials.MaterialListPlacement
                || materialList instanceof fi.dy.masa.litematica.materials.MaterialListSchematic;
        this.quickcraft$scope = isContainerList
                ? QuickLitematicaContainerReplacements.Scope.CONTAINER
                : isProjectionList ? QuickLitematicaContainerReplacements.Scope.PROJECTION : null;
        this.quickcraft$contextKey = QuickLitematicaMaterialLists.getReplacementContextKey(materialList);
        this.quickcraft$replacementSupported = isContainerList
                || (isProjectionList && !this.quickcraft$contextKey.isBlank()
                && (entry == null || entry.getStack().getItem() instanceof BlockItem));

        if (!QuickLitematicaContainerReplacements.enabled() || entry == null || !this.quickcraft$replacementSupported) {
            return;
        }

        String label = StringUtils.translate("quickcraft.litematica.button.material_replace");
        int ignoreWidth = StringUtils.getStringWidth(
                StringUtils.translate("litematica.gui.button.material_list.ignore")
        ) + 10;
        int replaceRight = x + width - ignoreWidth - 4;
        ButtonGeneric button = new ButtonGeneric(replaceRight, y + 1, -1, true, label);
        button.setHoverStrings(StringUtils.translate("quickcraft.litematica.tooltip.material_replace"),
                StringUtils.translate("quickcraft.litematica.tooltip.batch_select"));
        this.owner.quickcraft$addButton(button, (clicked, mouseButton) -> {
            if (com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(Minecraft.getInstance()) != null) {
                QuickLitematicaMaterialReplacementScreen.open(
                        com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(Minecraft.getInstance()),
                        this.entry.getStack(),
                        this.quickcraft$createReplacementBinding(materialList)
                );
            }
        });

        if (this.quickcraft$hasReplacement(materialList)) {
            String clearLabel = StringUtils.translate("quickcraft.litematica.button.material_replace_clear_list");
            ButtonGeneric clearButton = new ButtonGeneric(
                    button.getX() - 4,
                    y + 1,
                    -1,
                    true,
                    clearLabel
            );
            clearButton.setHoverStrings(StringUtils.translate("quickcraft.litematica.tooltip.material_replace_clear_list"));
            this.owner.quickcraft$addButton(clearButton, (clicked, mouseButton) -> {
                this.quickcraft$clearReplacement(materialList);
            });
        }
    }

    public boolean select(int mouseX, int mouseY, int mouseButton) {
        if (this.entry == null || mouseButton != InputConstants.MOUSE_BUTTON_LEFT || !QuickLitematicaMaterialGui.controlDown()
                || !QuickLitematicaContainerReplacements.enabled()) {
            return false;
        }
        MaterialListBase list = this.quickcraft$materialList;
        if (!(list instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource)
                && !(this.quickcraft$replacementSupported && this.quickcraft$scope == QuickLitematicaContainerReplacements.Scope.PROJECTION)) {
            return false;
        }

        ItemStack stack = this.entry.getStack();
        boolean selected = !isSelected(list, stack);
        toggleSelection(list, stack);
        if (com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof GuiMaterialList gui) {
            QuickLitematicaSelectionDrag.start(gui, mouseX, mouseY, selected,
                    (x, y, select) -> setSelectionAt(gui, x, y, select), () -> refreshSelection(gui));
            refreshSelection(gui);
        } else {
            this.quickcraft$listWidget.refreshEntries();
        }
        return true;
    }

    private static boolean setSelectionAt(GuiMaterialList gui, int mouseX, int mouseY, boolean selected) {
        var list = gui.getMaterialList();
        var widget = ((MalilibGuiListAccessor) gui).quickcraft$getListWidget();
        for (var row : ((MalilibWidgetListAccessor) widget).quickcraft$getVisibleEntries()) {
            if (row.isMouseOver(mouseX, mouseY) && row.getEntry() instanceof MaterialListEntry entry) {
                ItemStack stack = entry.getStack();
                if (!(list instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource)
                        && !(stack.getItem() instanceof BlockItem)) {
                    return false;
                }
                if (isSelected(list, stack) == selected) { return false; }
                toggleSelection(list, stack);
                return true;
            }
        }
        return false;
    }

    private static boolean isSelected(MaterialListBase list, ItemStack stack) {
        if (list instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            return source.quickcraft$isMaterialSelected(stack);
        }
        return QuickLitematicaProjectionMaterials.selected(list, stack);
    }

    private static void toggleSelection(MaterialListBase list, ItemStack stack) {
        if (list instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            source.quickcraft$toggleSelectedMaterial(stack);
        } else {
            QuickLitematicaProjectionMaterials.toggle(list, stack);
        }
    }

    private static void refreshSelection(GuiMaterialList gui) {
        var access = (MalilibGuiListAccessor) gui;
        int scroll = access.quickcraft$getListWidget().getScrollbar().getValue();
        gui.initGui();
        var widget = access.quickcraft$getListWidget();
        if (widget.getScrollbar().getValue() != scroll) {
            widget.getScrollbar().setValue(scroll);
            widget.refreshEntries();
        }
    }

    public void draw(
            int mouseX,
            int mouseY,
            boolean selected,
            Context drawContext
    ) {
        if (!QuickLitematicaContainerReplacements.enabled() || this.entry == null || !this.quickcraft$replacementSupported) {
            return;
        }

        if (isSelected(this.quickcraft$materialList, this.entry.getStack())) {
            drawContext.outline(this.quickcraft$rowX, this.quickcraft$rowY,
                    this.quickcraft$rowWidth, this.quickcraft$rowHeight, 0xFFFFD54F);
        }

        java.util.Optional<ItemStack> target;
        String targetLabel;
        if (this.quickcraft$materialList instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            var summary = source.quickcraft$getSummaryReplacement(this.entry.getStack());
            target = summary.target();
            targetLabel = summary.label();
        } else {
            target = QuickLitematicaContainerReplacements.getReplacementTarget(
                    this.entry.getStack(), this.quickcraft$scope, this.quickcraft$contextKey);
            targetLabel = target.map(stack -> stack.isEmpty()
                    ? QuickLitematicaMaterialReplacementScreen.emptyTargetLabel(this.quickcraft$scope)
                    : stack.getHoverName().getString()).orElse("");
        }
        if (target.isEmpty()) {
            return;
        }

        int statusY = this.quickcraft$rowY + 2;
        int ignoreWidth = StringUtils.getStringWidth(
                StringUtils.translate("litematica.gui.button.material_list.ignore")
        ) + 10;
        int replaceWidth = StringUtils.getStringWidth(
                StringUtils.translate("quickcraft.litematica.button.material_replace")
        ) + 10;
        int clearWidth = StringUtils.getStringWidth(
                StringUtils.translate("quickcraft.litematica.button.material_replace_clear_list")
        ) + 10;
        int actionLeft = this.quickcraft$rowX + this.quickcraft$rowWidth - ignoreWidth - 4 - replaceWidth - 4;
        actionLeft -= clearWidth + 4;
        int statusRight = actionLeft - 6;
        int statusX = this.quickcraft$rowX + Math.max(96, statusRight - this.quickcraft$rowX - 254);
        int sourceTextX = statusX + 20;
        int arrowX = statusX + 126;
        int targetX = arrowX + 18;
        int targetTextX = targetX + 20;
        int sourceTextWidth = Math.max(24, arrowX - sourceTextX - 6);
        int targetTextWidth = Math.max(24, statusRight - targetTextX);
        String sourceName = fitText(this.entry.getStack().getHoverName().getString(), sourceTextWidth);
        String targetName = fitText(targetLabel, targetTextWidth);
        drawContext.drawItem(this.entry.getStack(), statusX, statusY);
        drawContext.drawText(
                Minecraft.getInstance().font,
                sourceName,
                sourceTextX,
                statusY + 5,
                0xFFE8F4FF,
                false
        );
        drawContext.drawText(
                Minecraft.getInstance().font,
                ">",
                arrowX,
                statusY + 5,
                0xFF8FD7FF,
                false
        );
        drawContext.drawItem(target.get(), targetX, statusY);
        drawContext.drawText(
                Minecraft.getInstance().font,
                targetName,
                targetTextX,
                statusY + 5,
                0xFFE8F4FF,
                false
        );
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        net.minecraft.client.gui.Font renderer = Minecraft.getInstance().font;
        if (renderer.width(text) <= maxWidth) {
            return text;
        }
        int end = text.length();
        while (end > 1 && renderer.width(text.substring(0, end) + "...") > maxWidth) {
            end--;
        }
        return end <= 1 ? "..." : text.substring(0, end) + "...";
    }

    private QuickLitematicaMaterialReplacementScreen.ReplacementBinding quickcraft$createReplacementBinding(MaterialListBase materialList) {
        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            return source.quickcraft$createSummaryReplacementBinding(this.entry.getStack());
        }

        return com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials.binding(materialList, this.entry.getStack());
    }

    private boolean quickcraft$hasReplacement(MaterialListBase materialList) {
        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            return source.quickcraft$hasSummaryReplacement(this.entry.getStack());
        }

        return QuickLitematicaContainerReplacements.getReplacementTarget(
                this.entry.getStack(),
                this.quickcraft$scope,
                this.quickcraft$contextKey
        ).isPresent();
    }

    private void quickcraft$clearReplacement(MaterialListBase materialList) {
        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            source.quickcraft$clearSummaryReplacement(this.entry.getStack());
        } else {
            QuickLitematicaContainerReplacements.removeReplacementRule(
                    this.entry.getStack(),
                    this.quickcraft$scope,
                    this.quickcraft$contextKey
            );
        }

        Minecraft client = Minecraft.getInstance();
        if (com.yiyihehe.quickcraft.QuickClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui) {
            gui.getMaterialList().reCreateMaterialList();
            gui.initGui();
        } else if (this.quickcraft$listWidget != null) {
            this.quickcraft$listWidget.refreshEntries();
        }
    }
}
