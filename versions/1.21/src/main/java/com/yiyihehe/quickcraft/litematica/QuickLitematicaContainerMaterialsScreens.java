package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerGroup;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerMaterialList;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerMaterialsData;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ItemCount;
import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.Collection;
import java.util.List;

final class QuickLitematicaContainerMaterialsScreens {
    private QuickLitematicaContainerMaterialsScreens() {
    }

    static void openMaterialListScreen(ContainerMaterialList materialList) {
        ContainerMaterialListScreen screen = new ContainerMaterialListScreen(materialList);
        screen.setParent(materialList.parent);
        GuiBase.openGui(screen);
    }

    static void openDetailScreen(ContainerMaterialList materialList) {
        ContainerMaterialsScreen screen = new ContainerMaterialsScreen(materialList);
        screen.setParent(materialList.parent);
        GuiBase.openGui(screen);
    }

    private static final class ContainerMaterialListScreen extends GuiMaterialList {
        private final ContainerMaterialList materialList;

        private ContainerMaterialListScreen(ContainerMaterialList materialList) {
            super(materialList);
            this.materialList = materialList;
        }

        @Override
        public void initGui() {
            super.initGui();

            int gap = 1;
            String detailsLabel = StringUtils.translate("quickcraft.litematica.button.container_material_details");
            String materialLabel = StringUtils.translate(QuickLitematicaContainerMaterials.BUTTON_KEY);
            List<ButtonBase> buttons = ((QuickCraftGuiButtonAccess) (Object) this).quickcraft$getButtons();
            int bottomRow = this.height - 22;
            boolean nativeButtonsWrapped = buttons.stream().anyMatch(button -> button.getY() == bottomRow);
            int topRowLimit = this.width - 60;
            int topX = buttons.stream()
                    .filter(button -> button.getY() == 24)
                    .mapToInt(button -> button.getX() + button.getWidth() + gap)
                    .max()
                    .orElse(12);
            int detailsWidth = this.getStringWidth(detailsLabel) + 10;
            int materialWidth = this.getStringWidth(materialLabel) + 10;
            boolean fitsTopRow = !nativeButtonsWrapped
                    && topX + detailsWidth + gap + materialWidth <= topRowLimit;
            int y = fitsTopRow ? 24 : bottomRow;
            int x = buttons.stream()
                    .filter(button -> button.getY() == y)
                    .mapToInt(button -> button.getX() + button.getWidth() + gap)
                    .max()
                    .orElse(12);

            ButtonGeneric detailsButton = this.createNavButton(x, y, detailsLabel);
            this.addButton(detailsButton, (button, mouseButton) -> openDetailScreen(this.materialList));
            x += detailsButton.getWidth() + gap;

            ButtonGeneric materialButton = this.createNavButton(x, y, materialLabel);
            materialButton.setEnabled(false);
            this.addButton(materialButton, (button, mouseButton) -> {
            });
        }

        private ButtonGeneric createNavButton(int x, int y, String label) {
            return new ButtonGeneric(x, y, this.getStringWidth(label) + 10, 20, label);
        }
    }

    private static final class ContainerMaterialsScreen
            extends GuiListBase<ContainerGroup, ContainerGroupEntryWidget, ContainerGroupListWidget> {
        private final ContainerMaterialList materialList;
        private final ContainerMaterialsData data;

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
            return this.height - 82;
        }

        @Override
        public void initGui() {
            super.initGui();

            int x = 12;
            int y = 24;
            x += this.createButton(x, y, ButtonType.REFRESH) + 2;
            x += this.createButton(x, y, ButtonType.MATERIAL_LIST) + 2;
            ButtonGeneric detailsButton = new ButtonGeneric(
                    x,
                    y,
                    -1,
                    20,
                    StringUtils.translate("quickcraft.litematica.button.container_material_details")
            );
            detailsButton.setEnabled(false);
            this.addButton(detailsButton, (button, mouseButton) -> {
            });

            String summary = StringUtils.translate(
                    "quickcraft.litematica.label.summary",
                    this.data.visibleGroups().size(),
                    this.data.totalVisibleContainerCount()
            );
            this.addLabel(12, this.height - 36, -1, 12, 0xFFFFFFFF, summary);

            ButtonListenerChangeMenu.ButtonType type = ButtonListenerChangeMenu.ButtonType.MAIN_MENU;
            String label = StringUtils.translate(type.getLabelKey());
            int buttonWidth = this.getStringWidth(label) + 20;
            ButtonGeneric button = new ButtonGeneric(this.width - buttonWidth - 10, this.height - 36, buttonWidth, 20, label);
            this.addButton(button, new ButtonListenerChangeMenu(type, this.getParent()));
        }

        @Override
        public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
            super.drawContents(drawContext, mouseX, mouseY, partialTicks);

            if (this.data.visibleGroups().isEmpty()) {
                String text = StringUtils.translate("quickcraft.litematica.label.no_container_contents");
                drawContext.drawText(this.textRenderer, text, this.width / 2 - this.getStringWidth(text) / 2, this.height / 2, 0xFFFFFFFF, false);
            }
        }

        @Override
        protected ContainerGroupListWidget createListWidget(int listX, int listY) {
            return new ContainerGroupListWidget(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
        }

        private int createButton(int x, int y, ButtonType type) {
            String label = StringUtils.translate(type.translationKey);
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
            this.addButton(button, new ContainerMaterialsButtonListener(this, type));
            return button.getWidth();
        }

        private List<ContainerGroup> groups() {
            return this.data.visibleGroups();
        }

        private void refreshData() {
            this.data.refresh(this.materialList.getMaterialListType());
            this.materialList.invalidateMaterialEntries();
            this.reCreateListWidget();
            this.initGui();
        }

        private void ignoreGroup(ContainerGroup group) {
            this.data.ignoreGroup(group);
            this.materialList.invalidateMaterialEntries();
            this.reCreateListWidget();
            this.initGui();
        }

        private void openMaterialList() {
            this.materialList.ensureMaterialEntriesInitialized();
            openMaterialListScreen(this.materialList);
        }
    }

    private static final class ContainerGroupListWidget extends WidgetListBase<ContainerGroup, ContainerGroupEntryWidget> {
        private final ContainerMaterialsScreen gui;

        private ContainerGroupListWidget(int x, int y, int width, int height, ContainerMaterialsScreen gui) {
            super(x, y, width, height, null);
            this.gui = gui;
            this.browserEntryHeight = QuickLitematicaContainerMaterials.HEADER_HEIGHT;
        }

        @Override
        protected Collection<ContainerGroup> getAllEntries() {
            return this.gui.groups();
        }

        @Override
        protected int getBrowserEntryHeightFor(ContainerGroup group) {
            return group == null ? QuickLitematicaContainerMaterials.HEADER_HEIGHT : QuickLitematicaContainerMaterials.getGroupHeight(group, this.browserEntryWidth);
        }

        @Override
        protected ContainerGroupEntryWidget createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight) {
            if (usedHeight + QuickLitematicaContainerMaterials.HEADER_HEIGHT > usableHeight) {
                return null;
            }

            return new ContainerGroupEntryWidget(x, y, this.browserEntryWidth, QuickLitematicaContainerMaterials.HEADER_HEIGHT, true, null, this.gui);
        }

        @Override
        protected ContainerGroupEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ContainerGroup entry) {
            return new ContainerGroupEntryWidget(
                    x,
                    y,
                    this.browserEntryWidth,
                    QuickLitematicaContainerMaterials.getGroupHeight(entry, this.browserEntryWidth),
                    isOdd,
                    entry,
                    this.gui
            );
        }
    }

    private static final class ContainerGroupEntryWidget extends WidgetListEntryBase<ContainerGroup> {
        private final ContainerMaterialsScreen gui;
        private final boolean isOdd;

        private ContainerGroupEntryWidget(
                int x,
                int y,
                int width,
                int height,
                boolean isOdd,
                ContainerGroup entry,
                ContainerMaterialsScreen gui
        ) {
            super(x, y, width, height, entry, 0);
            this.gui = gui;
            this.isOdd = isOdd;

            if (entry != null) {
                int buttonX = x + width - QuickLitematicaContainerMaterials.ACTION_COLUMN_WIDTH + 4;
                int buttonY = y + (height - 20) / 2;
                ButtonGeneric button = new ButtonGeneric(
                        buttonX,
                        buttonY,
                        -1,
                        true,
                        "quickcraft.litematica.button.ignore_container"
                );
                this.addButton(button, (clickedButton, mouseButton) -> this.gui.ignoreGroup(entry));
            }
        }

        @Override
        public boolean canSelectAt(int mouseX, int mouseY, int mouseButton) {
            return false;
        }

        @Override
        public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
            if (this.entry == null) {
                this.renderHeader(drawContext);
                return;
            }

            if (this.isMouseOver(mouseX, mouseY)) {
                drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
            } else if (this.isOdd) {
                drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
            } else {
                drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
            }

            int containerX = this.x + 6;
            int countX = this.x + QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH + QuickLitematicaContainerMaterials.COUNT_COLUMN_WIDTH + 8;
            int yText = this.y + 6;

            drawRect(drawContext, containerX, this.y + 6, 16, 16, 0x20FFFFFF);
            drawContext.drawItem(this.entry.containerStack(), containerX, this.y + 6);
            this.drawText(drawContext, containerX + 20, yText, 0xFFFFFFFF, QuickLitematicaContainerMaterials.fitText(this.entry.containerName(), QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH - 28));

            if (this.entry.sourceLabel() != null) {
                this.drawText(drawContext, containerX + 20, yText + 11, 0xFFAAAAAA, QuickLitematicaContainerMaterials.fitText(this.entry.sourceLabel(), QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH - 28));
            }

            this.drawText(drawContext, countX, this.y + 10, 0xFFFFFFFF, "x" + this.entry.containerCount());
            this.renderContents(drawContext, contentsX, mouseX, mouseY);
            super.render(mouseX, mouseY, selected, drawContext);
        }

        @Override
        public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
            if (this.entry == null) {
                return;
            }

            int containerX = this.x + 6;
            int containerY = this.y + 6;

            if (mouseX >= containerX && mouseX < containerX + 16 && mouseY >= containerY && mouseY < containerY + 16) {
                drawContext.drawItemTooltip(this.textRenderer, this.entry.containerStack(), mouseX, mouseY);
                return;
            }

            ItemCount hovered = this.getHoveredItem(mouseX, mouseY);

            if (hovered != null) {
                drawContext.drawItemTooltip(this.textRenderer, hovered.stack(), mouseX, mouseY);
                return;
            }

            super.postRenderHovered(mouseX, mouseY, selected, drawContext);
        }

        private void renderHeader(DrawContext drawContext) {
            drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);

            int containerX = this.x + 6;
            int countX = this.x + QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH + QuickLitematicaContainerMaterials.COUNT_COLUMN_WIDTH + 8;
            int actionX = this.x + this.width - QuickLitematicaContainerMaterials.ACTION_COLUMN_WIDTH + 4;
            int endX = this.x + this.width - 2;
            int y = this.y + 7;

            this.drawHeaderCell(drawContext, containerX, countX);
            this.drawHeaderCell(drawContext, countX, contentsX);
            this.drawHeaderCell(drawContext, contentsX, actionX);
            this.drawHeaderCell(drawContext, actionX, endX);
            this.drawText(drawContext, containerX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.container") + GuiBase.TXT_RST);
            this.drawText(drawContext, countX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.count") + GuiBase.TXT_RST);
            this.drawText(drawContext, contentsX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.contents") + GuiBase.TXT_RST);
            this.drawText(drawContext, actionX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.action") + GuiBase.TXT_RST);
        }

        private void drawHeaderCell(DrawContext drawContext, int xStart, int xEnd) {
            drawOutline(drawContext, xStart - 3, this.y + 1, xEnd - xStart - 2, this.height - 2, 0xC0707070);
        }

        private void renderContents(DrawContext drawContext, int contentsX, int mouseX, int mouseY) {
            if (this.entry.contents().isEmpty()) {
                this.drawText(drawContext, contentsX, this.y + 10, 0xFFAAAAAA, StringUtils.translate("quickcraft.litematica.label.empty_contents"));
                return;
            }

            int columns = getContentColumns(contentsX);

            for (int i = 0; i < this.entry.contents().size(); i++) {
                ItemCount item = this.entry.contents().get(i);
                int itemX = contentsX + (i % columns) * QuickLitematicaContainerMaterials.ITEM_CELL_WIDTH;
                int itemY = this.y + 6 + (i / columns) * QuickLitematicaContainerMaterials.ITEM_CELL_HEIGHT;
                ItemStack displayStack = item.stack();

                drawRect(drawContext, itemX, itemY, 16, 16, 0x20FFFFFF);
                drawContext.drawItem(displayStack, itemX, itemY);
                drawContext.drawItemInSlot(
                        this.textRenderer,
                        displayStack,
                        itemX,
                        itemY,
                        QuickLitematicaContainerMaterials.formatCount(item.totalCount(this.entry.containerCount()))
                );
            }
        }

        private void drawText(DrawContext drawContext, int x, int y, int color, String text) {
            this.drawString(x, y, color, text, drawContext);
        }

        private static void drawRect(DrawContext drawContext, int x, int y, int width, int height, int color) {
            RenderUtils.drawRect(x, y, width, height, color);
        }

        private static void drawOutline(DrawContext drawContext, int x, int y, int width, int height, int color) {
            RenderUtils.drawOutline(x, y, width, height, color);
        }

        private ItemCount getHoveredItem(int mouseX, int mouseY) {
            int contentsX = this.x + QuickLitematicaContainerMaterials.CONTAINER_COLUMN_WIDTH + QuickLitematicaContainerMaterials.COUNT_COLUMN_WIDTH + 8;
            int columns = getContentColumns(contentsX);

            for (int i = 0; i < this.entry.contents().size(); i++) {
                int itemX = contentsX + (i % columns) * QuickLitematicaContainerMaterials.ITEM_CELL_WIDTH;
                int itemY = this.y + 6 + (i / columns) * QuickLitematicaContainerMaterials.ITEM_CELL_HEIGHT;

                if (mouseX >= itemX && mouseX < itemX + 16 && mouseY >= itemY && mouseY < itemY + 16) {
                    return this.entry.contents().get(i);
                }
            }

            return null;
        }

        private int getContentColumns(int contentsX) {
            int contentWidth = Math.max(QuickLitematicaContainerMaterials.ITEM_CELL_WIDTH, this.x + this.width - contentsX - QuickLitematicaContainerMaterials.ACTION_COLUMN_WIDTH - 10);
            return Math.max(1, contentWidth / QuickLitematicaContainerMaterials.ITEM_CELL_WIDTH);
        }
    }

    private enum ButtonType {
        REFRESH("quickcraft.litematica.button.refresh_container_material_list"),
        MATERIAL_LIST(QuickLitematicaContainerMaterials.BUTTON_KEY);

        private final String translationKey;

        ButtonType(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private static final class ContainerMaterialsButtonListener implements IButtonActionListener {
        private final ContainerMaterialsScreen screen;
        private final ButtonType type;

        private ContainerMaterialsButtonListener(ContainerMaterialsScreen screen, ButtonType type) {
            this.screen = screen;
            this.type = type;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            switch (this.type) {
                case REFRESH -> this.screen.refreshData();
                case MATERIAL_LIST -> this.screen.openMaterialList();
            }
        }
    }
}
