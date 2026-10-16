package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.util.StringUtils;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialGui.Context;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.*;

final class QuickLitematicaContainerMaterialsScreens {
    private QuickLitematicaContainerMaterialsScreens() {}
    static net.minecraft.client.gui.screens.Screen createDetailScreen(ContainerMaterialList list) {
        var screen = new ContainerMaterialsScreen(list);
        screen.setParent(list.parent);
        return screen;
    }
    private static final class ContainerMaterialsScreen
            extends QuickLitematicaMaterialGui.ListScreen<ContainerRow, ContainerGroupEntryWidget, ContainerGroupListWidget>
            implements QuickLitematicaMaterialReplacementScreen.RefreshableParent {
        private final ContainerMaterialList materialList;
        private final ContainerMaterialsData data;
        private int sortColumn = 1;
        private boolean sortReverse;
        private int savedScroll;
        private int bottomMargin = 40;
        private final Map<String, ContainerMaterialsData.SummarySlot> selectedSlots = new LinkedHashMap<>();

        private ContainerMaterialsScreen(ContainerMaterialList materialList) {
            super(10, 44);
            this.materialList = materialList;
            this.data = materialList.data;
            this.title = this.data.detailTitle();
            this.useTitleHierarchy = false;
        }

        @Override
        protected int getBrowserWidth() {
            return this.width - 20;
        }

        @Override
        protected int getBrowserHeight() {
            return Math.max(0, this.height - this.getListY() - this.bottomMargin);
        }

        @Override
        public void initGui() {
            super.initGui();

            ButtonListenerChangeMenu.ButtonType type = ButtonListenerChangeMenu.ButtonType.MAIN_MENU;
            String label = StringUtils.translate(type.getLabelKey());
            int buttonWidth = this.getStringWidth(label) + 20;
            ButtonGeneric button = new ButtonGeneric(this.width - buttonWidth - 10, this.height - 36, buttonWidth, 20, label);
            this.addButton(button, new ButtonListenerChangeMenu(type, this.getParent()));
            var actions = new ArrayList<QuickLitematicaMaterialButtons.Action>();
            if (QuickLitematicaContainerReplacements.enabled()) {
                actions.add(QuickLitematicaMaterialButtons.action("quickcraft.litematica.button.clear_container_material_rules", this::clearAllRules));
                actions.add(QuickLitematicaMaterialButtons.action("quickcraft.litematica.button.save_schematic_copy", () -> saveCopy(this.materialList)));
                actions.add(new QuickLitematicaMaterialButtons.Action(StringUtils.translate("quickcraft.litematica.button.batch_replace", this.selectedSlots.size()), () -> {
                    var slots = List.copyOf(this.selectedSlots.values());
                    this.savedScroll = this.getListWidget().getScrollbar().getValue();
                    QuickLitematicaMaterialReplacementScreen.open(this, slots.getFirst().source(), this.data.createBatchSlotBinding(slots));
                }, !this.selectedSlots.isEmpty()));
                actions.add(QuickLitematicaMaterialButtons.action("quickcraft.litematica.button.clear_selection", () -> {
                    this.selectedSlots.clear();
                    this.savedScroll = this.getListWidget().getScrollbar().getValue();
                    this.initGui();
                }));
            }
            this.bottomMargin = QuickLitematicaMaterialButtons.addDetails(this, this.materialList, actions);
            this.getListWidget().getScrollbar().setValue(this.savedScroll);
            this.getListWidget().refreshEntries();
        }

        @Override
        public void drawMaterialContents(Context drawContext, int mouseX, int mouseY, float partialTicks) {
            super.drawMaterialContents(drawContext, mouseX, mouseY, partialTicks);

            if (this.data.visibleGroups().isEmpty()) {
                String text = StringUtils.translate("quickcraft.litematica.label.no_container_contents");
                drawContext.drawText(Minecraft.getInstance().font, text, this.width / 2 - this.getStringWidth(text) / 2, this.height / 2, 0xFFFFFFFF, false);
            }
        }

        @Override
        protected ContainerGroupListWidget createListWidget(int listX, int listY) {
            return new ContainerGroupListWidget(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
        }

        private List<ContainerRow> rows(int rowWidth) {
            List<ContainerGroup> groups = new ArrayList<>(this.data.visibleGroups());
            Comparator<ContainerGroup> comparator = this.sortColumn == 0
                    ? Comparator.comparing(ContainerGroup::containerName, String.CASE_INSENSITIVE_ORDER)
                    : Comparator.comparingInt(ContainerGroup::containerCount).reversed();
            comparator = comparator.thenComparing(ContainerGroup::signature);
            if (this.sortReverse) { comparator = comparator.reversed(); }
            groups.sort(comparator);
            int contentWidth = rowWidth - CONTAINER_COLUMN_WIDTH - COUNT_COLUMN_WIDTH - ACTION_COLUMN_WIDTH - 18;
            int columns = Math.max(1, Math.min(9, (contentWidth - 12) / (2 * ITEM_CELL_WIDTH)));
            // 每个列表条目只放一排槽位，避免下一条高行把本页剩余空间全部空出来。
            int slotsPerRow = columns;
            List<ContainerRow> rows = new ArrayList<>();
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                ContainerGroup group = groups.get(groupIndex);
                for (int start = 0; start < group.slotStacks().size(); start += slotsPerRow) {
                    rows.add(new ContainerRow(group, start, Math.min(group.slotStacks().size(), start + slotsPerRow), groupIndex));
                }
            }
            return rows;
        }

        private void sortBy(int column) {
            this.sortReverse = this.sortColumn == column && !this.sortReverse;
            this.sortColumn = column;
            this.savedScroll = 0;
            this.getListWidget().getScrollbar().setValue(0);
            this.getListWidget().refreshEntries();
        }

        @Override
        public void removed() {
            this.savedScroll = this.getListWidget().getScrollbar().getValue();
            super.removed();
        }

        private void ignoreGroup(ContainerGroup group) {
            this.data.ignoreGroup(group);
            this.materialList.reCreateMaterialList();
            this.reCreateListWidget();
            this.initGui();
        }

        private void clearAllRules() {
            this.materialList.quickcraft$clearAllContainerRulesForContext();
            this.materialList.reCreateMaterialList();
            this.reCreateListWidget();
            this.initGui();
        }

        private void toggleSlotSelection(ContainerGroup group, int slotIndex) {
            if (!QuickLitematicaContainerReplacements.enabled()) { return; }
            String key = group.signature() + ":" + slotIndex;
            if (this.selectedSlots.remove(key) == null) {
                this.selectedSlots.put(key, new ContainerMaterialsData.SummarySlot(group, slotIndex, group.slotStacks().get(slotIndex).copy()));
            }
            this.savedScroll = this.getListWidget().getScrollbar().getValue();
            this.initGui();
        }

        private void openSlotEditor(ContainerGroup group, int slotIndex) {
            if (!QuickLitematicaContainerReplacements.enabled()) { return; }
            if (group == null || slotIndex < 0 || slotIndex >= group.slotStacks().size()) {
                return;
            }

            this.savedScroll = this.getListWidget().getScrollbar().getValue();
            ItemStack stack = group.slotStacks().get(slotIndex);
            QuickLitematicaMaterialReplacementScreen.open(
                    this,
                    stack,
                    this.materialList.data.createSlotReplacementBinding(group, slotIndex)
            );
        }

        private Optional<ItemStack> getSlotTarget(ContainerGroup group, int slotIndex) {
            if (group == null || slotIndex < 0 || slotIndex >= group.slotStacks().size()) {
                return Optional.empty();
            }

            ItemStack stack = group.slotStacks().get(slotIndex);
            return QuickLitematicaContainerReplacements.getReplacementTarget(
                    stack,
                    QuickLitematicaContainerReplacements.Scope.CONTAINER,
                    this.data.replacementContextKey,
                    group.signature(),
                    slotIndex
            );
        }

        @Override
        public void quickcraft$refreshAfterReplacement() {
            this.materialList.reCreateMaterialList();
            this.reCreateListWidget();
            this.initGui();
        }
    }

    record ContainerRow(ContainerGroup group, int startSlot, int endSlot, int groupIndex) { }

    private static final class ContainerGroupListWidget extends WidgetListBase<ContainerRow, ContainerGroupEntryWidget> {
        private final ContainerMaterialsScreen gui;

        private ContainerGroupListWidget(int x, int y, int width, int height, ContainerMaterialsScreen gui) {
            super(x, y, width, height, null);
            this.gui = gui;
            this.browserEntryHeight = HEADER_HEIGHT;
        }

        @Override
        protected Collection<ContainerRow> getAllEntries() {
            return this.gui.rows(this.browserEntryWidth);
        }

        @Override
        protected int getBrowserEntryHeightFor(ContainerRow group) {
            return group == null ? HEADER_HEIGHT : getGroupHeight(group, this.browserEntryWidth);
        }

        @Override
        protected ContainerGroupEntryWidget createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight) {
            if (usedHeight + HEADER_HEIGHT > usableHeight) {
                return null;
            }

            return new ContainerGroupEntryWidget(x, y, this.browserEntryWidth, HEADER_HEIGHT, true, true, null, this.gui);
        }

        @Override
        protected ContainerGroupEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ContainerRow entry) {
            return new ContainerGroupEntryWidget(
                    x,
                    y,
                    this.browserEntryWidth,
                    getGroupHeight(entry, this.browserEntryWidth),
                    (entry.groupIndex() & 1) != 0,
                    entry.startSlot() == 0 || listIndex == this.scrollBar.getValue(),
                    entry,
                    this.gui
            );
        }
    }

    private static final class ContainerGroupEntryWidget extends QuickLitematicaMaterialGui.Entry<ContainerRow> {
        private final ContainerMaterialsScreen gui;
        private final boolean isOdd;
        private final boolean showGroupInfo;

        private ContainerGroupEntryWidget(
                int x,
                int y,
                int width,
                int height,
                boolean isOdd,
                boolean showGroupInfo,
                ContainerRow entry,
                ContainerMaterialsScreen gui
        ) {
            super(x, y, width, height, entry, entry == null ? -1 : 0);
            this.gui = gui;
            this.isOdd = isOdd;
            this.showGroupInfo = showGroupInfo;

            if (entry != null && showGroupInfo) {
                ButtonGeneric button = new ButtonGeneric(
                        x + width - 4,
                        y + 1,
                        -1,
                        true,
                        "quickcraft.litematica.button.ignore_container"
                );
                this.addButton(button, (clickedButton, mouseButton) -> this.gui.ignoreGroup(entry.group()));
            }
        }

        @Override
        protected int getColumnPosX(int column) {
            return this.x + 6 + switch (column) {
                case 1 -> CONTAINER_COLUMN_WIDTH - 2;
                case 2 -> CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 2;
                default -> 0;
            };
        }

        @Override
        protected int getCurrentSortColumn() { return this.gui.sortColumn; }

        @Override
        protected boolean getSortInReverse() { return this.gui.sortReverse; }

        @Override
        public boolean selectMaterialAt(int mouseX, int mouseY, int mouseButton) {
            return false;
        }

        @Override
        protected boolean clickMaterial(int mouseX, int mouseY, int mouseButton) {
            if (this.entry == null) {
                int column = this.getMouseOverColumn(mouseX, mouseY);
                if (column >= 0 && column < 2) {
                    this.gui.sortBy(column);
                    return true;
                }
                return false;
            }

            int slotIndex = this.getHoveredSlotIndex(mouseX, mouseY);
            if (slotIndex >= 0) {
                if (mouseButton != 0) { return false; }
                if (QuickLitematicaMaterialGui.controlDown()) {
                    this.gui.toggleSlotSelection(this.entry.group(), slotIndex);
                } else {
                    this.gui.openSlotEditor(this.entry.group(), slotIndex);
                }
                return true;
            }

            return false;
        }

        @Override
        public void renderMaterial(int mouseX, int mouseY, boolean selected, Context drawContext) {
            if (this.entry == null) {
                this.renderHeader(drawContext, mouseX, mouseY);
                return;
            }

            if (this.isOdd) {
                drawContext.rect(this.x, this.y, this.width, this.height, 0xA0101010);
            } else {
                drawContext.rect(this.x, this.y, this.width, this.height, 0xA0303030);
            }

            int containerX = this.x + 6;
            int countX = this.x + CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int yText = this.y + 6;

            if (this.entry.startSlot() == 0) {
                drawContext.rect(this.x, this.y, this.width, 1, 0xFF737B85);
            }
            drawContext.rect(this.x, this.y, 1, this.height, 0xFF737B85);
            drawContext.rect(this.x + this.width - 1, this.y, 1, this.height, 0xFF737B85);
            if (this.entry.endSlot() == this.entry.group().slotStacks().size()) {
                drawContext.rect(this.x, this.y + this.height - 1, this.width, 1, 0xFF737B85);
            }
            if (this.showGroupInfo) {
                drawContext.rect(containerX, this.y + 6, 16, 16, 0x20FFFFFF);
                drawContext.drawItem(this.entry.group().containerStack(), containerX, this.y + 6);
                this.drawMaterialString(containerX + 20, yText, 0xFFFFFFFF, fitText(this.entry.group().containerName(), CONTAINER_COLUMN_WIDTH - 28), drawContext);
                if (this.entry.group().sourceLabel() != null) {
                    this.drawMaterialString(containerX + 20, yText + 11, 0xFFAAAAAA, fitText(this.entry.group().sourceLabel(), CONTAINER_COLUMN_WIDTH - 28), drawContext);
                }
                this.drawMaterialString(countX, this.y + 10, 0xFFFFFFFF, "x" + this.entry.group().containerCount(), drawContext);
            }
            this.renderContents(drawContext, contentsX, mouseX, mouseY);
            super.renderMaterial(mouseX, mouseY, selected, drawContext);
        }

        @Override
        public void hoverMaterial(int mouseX, int mouseY, boolean selected, Context drawContext) {
            if (this.entry == null) {
                return;
            }

            int containerX = this.x + 6;
            int containerY = this.y + 6;

            if (this.showGroupInfo && mouseX >= containerX && mouseX < containerX + 16 && mouseY >= containerY && mouseY < containerY + 16) {
                drawContext.drawItemTooltip(Minecraft.getInstance().font, this.entry.group().containerStack(), mouseX, mouseY);
                return;
            }

            int countX = this.x + CONTAINER_COLUMN_WIDTH + 4;
            if (this.showGroupInfo && mouseX >= countX && mouseX < countX + COUNT_COLUMN_WIDTH && this.isMouseOver(mouseX, mouseY)) {
                drawContext.drawTooltip(Minecraft.getInstance().font, List.of(Component.literal(StringUtils.translate(
                        "quickcraft.litematica.label.material_replace_group_scope", this.entry.group().containerCount()))), mouseX, mouseY);
                return;
            }
            int hoveredSlot = this.getHoveredSlotIndex(mouseX, mouseY);
            if (hoveredSlot >= 0) {
                int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
                int targetX = contentsX + this.getContentColumns(contentsX) * ITEM_CELL_WIDTH + 12;
                ItemStack original = this.entry.group().slotStacks().get(hoveredSlot);
                ItemStack hovered = mouseX >= targetX
                        ? this.gui.getSlotTarget(this.entry.group(), hoveredSlot).orElse(original) : original;
                if (!hovered.isEmpty()) {
                    drawContext.drawItemTooltip(Minecraft.getInstance().font, hovered, mouseX, mouseY);
                } else {
                    drawContext.drawTooltip(Minecraft.getInstance().font, List.of(Component.literal(StringUtils.translate(
                            "quickcraft.litematica.label.material_replace_empty_slot"))), mouseX, mouseY);
                }
                return;
            }

            super.hoverMaterial(mouseX, mouseY, selected, drawContext);
        }

        private void renderHeader(Context drawContext, int mouseX, int mouseY) {
            drawContext.rect(this.x, this.y, this.width, this.height, 0xA0101010);

            int containerX = this.x + 6;
            int countX = this.x + CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int actionX = this.x + this.width - ACTION_COLUMN_WIDTH + 4;
            int endX = this.x + this.width - 2;
            int y = this.y + 7;

            this.renderMaterialHeader(mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP, drawContext);
            this.drawHeaderCell(drawContext, contentsX, actionX);
            this.drawHeaderCell(drawContext, actionX, endX);
            this.drawMaterialString(containerX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.container") + GuiBase.TXT_RST, drawContext);
            this.drawMaterialString(countX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.count") + GuiBase.TXT_RST, drawContext);
            this.drawMaterialString(contentsX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.contents") + GuiBase.TXT_RST, drawContext);
            this.drawMaterialString(actionX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.action") + GuiBase.TXT_RST, drawContext);
        }

        private void drawHeaderCell(Context drawContext, int xStart, int xEnd) {
            drawContext.outline(xStart - 3, this.y + 1, xEnd - xStart - 2, this.height - 2, 0xC0707070);
        }

        private void renderContents(Context drawContext, int contentsX, int mouseX, int mouseY) {
            if (this.entry.group().slotStacks().isEmpty()) {
                this.drawMaterialString(contentsX, this.y + 10, 0xFFAAAAAA, StringUtils.translate("quickcraft.litematica.label.empty_contents"), drawContext);
                return;
            }

            int columns = getContentColumns(contentsX);
            int targetX = contentsX + columns * ITEM_CELL_WIDTH + 12;
            for (int i = this.entry.startSlot(); i < this.entry.endSlot(); i++) {
                int itemX = contentsX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int replacedX = targetX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int itemY = this.y + 4 + ((i - this.entry.startSlot()) / columns) * ITEM_CELL_HEIGHT;
                drawContext.rect(itemX, itemY, 16, 16, 0x20FFFFFF);
                ItemStack original = this.entry.group().slotStacks().get(i);
                ItemStack result = this.gui.getSlotTarget(this.entry.group(), i).orElse(original);
                boolean changed = hasStackChange(original, result);
                drawContext.box(replacedX, itemY, 16, 16,
                        changed ? 0xD02A65AF : 0x20FFFFFF, changed ? 0xFF76C7FF : 0x40707070);
                if (this.gui.selectedSlots.containsKey(this.entry.group().signature() + ":" + i)) {
                    drawContext.outline(itemX - 1, itemY - 1, 18, 18, 0xFFFFD54F);
                    drawContext.outline(replacedX - 1, itemY - 1, 18, 18, 0xFFFFD54F);
                }
                if (mouseX >= itemX && mouseX < itemX + 16 && mouseY >= itemY && mouseY < itemY + 16) {
                    drawContext.outline(itemX, itemY, 16, 16, 0xFFFFFFFF);
                }
            }
            this.drawMaterialString(targetX - 10, this.y + 8, 0xFF8FD7FF, ">", drawContext);

            for (int i = this.entry.startSlot(); i < this.entry.endSlot(); i++) {
                ItemStack displayStack = this.entry.group().slotStacks().get(i);
                int itemX = contentsX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int replacedX = targetX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int itemY = this.y + 4 + ((i - this.entry.startSlot()) / columns) * ITEM_CELL_HEIGHT;
                Optional<ItemStack> target = this.gui.getSlotTarget(this.entry.group(), i);

                if (!displayStack.isEmpty()) {
                    drawContext.drawItem(displayStack, itemX, itemY);
                    drawContext.drawItemInSlot(Minecraft.getInstance().font, displayStack, itemX, itemY);
                }
                ItemStack finalStack = target.orElse(displayStack);
                drawContext.drawItem(finalStack, replacedX, itemY);
                drawContext.drawItemInSlot(Minecraft.getInstance().font, finalStack, replacedX, itemY);
                this.drawMaterialString(itemX + 2, itemY + 17, 0xFF9298A1, Integer.toString(i + 1), drawContext);
                this.drawMaterialString(replacedX + 2, itemY + 17, 0xFF9298A1, Integer.toString(i + 1), drawContext);
            }
        }

        private int getHoveredSlotIndex(int mouseX, int mouseY) {
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int columns = getContentColumns(contentsX);
            int targetX = contentsX + columns * ITEM_CELL_WIDTH + 12;

            for (int i = this.entry.startSlot(); i < this.entry.endSlot(); i++) {
                int itemX = contentsX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int replacedX = targetX + ((i - this.entry.startSlot()) % columns) * ITEM_CELL_WIDTH;
                int itemY = this.y + 4 + ((i - this.entry.startSlot()) / columns) * ITEM_CELL_HEIGHT;

                if (((mouseX >= itemX && mouseX < itemX + 16)
                        || (mouseX >= replacedX && mouseX < replacedX + 16))
                        && mouseY >= itemY && mouseY < itemY + 16) {
                    return i;
                }
            }

            return -1;
        }

        private int getContentColumns(int contentsX) {
            int contentWidth = Math.max(ITEM_CELL_WIDTH, this.x + this.width - contentsX - ACTION_COLUMN_WIDTH - 10);
            return Math.max(1, Math.min(9, (contentWidth - 12) / (2 * ITEM_CELL_WIDTH)));
        }
    }

}
