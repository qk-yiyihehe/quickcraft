package com.yiyihehe.quickcraft.litematica;


import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialGui.Context;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 原版风格的材料替换选择器；只修改材料来源规则，不修改投影原始数据。 */
public final class QuickLitematicaMaterialReplacementScreen extends QuickLitematicaMaterialGui.BaseScreen {
    private static final int PREFERRED_PANEL_WIDTH = 460;
    private static final int PREFERRED_PANEL_HEIGHT = 320;
    private static final int CELL_SIZE = 28;
    private static final int GRID_COLUMNS = 13;
    private static final int GRID_ROWS = 5;
    private static final int GRID_TOP = 78;
    private static final List<RegistryKey<ItemGroup>> CREATIVE_GROUP_ORDER = List.of(
            ItemGroups.BUILDING_BLOCKS,
            ItemGroups.COLORED_BLOCKS,
            ItemGroups.NATURAL,
            ItemGroups.FUNCTIONAL,
            ItemGroups.REDSTONE,
            ItemGroups.TOOLS,
            ItemGroups.COMBAT,
            ItemGroups.FOOD_AND_DRINK,
            ItemGroups.INGREDIENTS,
            ItemGroups.SPAWN_EGGS,
            ItemGroups.OPERATOR
    );

    private final Screen parent;
    private final ItemStack source;
    private final ReplacementBinding binding;
    private final List<Item> allItems = new ArrayList<>();
    private final List<Item> filteredItems = new ArrayList<>();
    private GuiTextFieldGeneric searchField;
    private GuiTextFieldGeneric nameField;
    private GuiTextFieldGeneric countField;
    private Item selectedItem;
    private ItemStack selectedTemplate = ItemStack.EMPTY;
    private String countText = "";
    private String validationError = "";
    private String searchText = "";
    private String targetName = "";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int rowIndex;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public QuickLitematicaMaterialReplacementScreen(
            Screen parent,
            ItemStack source,
            QuickLitematicaContainerReplacements.Scope scope
    ) {
        this(parent, source, createDefaultBinding(scope, ""));
    }

    public QuickLitematicaMaterialReplacementScreen(
            Screen parent,
            ItemStack source,
            QuickLitematicaContainerReplacements.Scope scope,
            String replacementContextKey
    ) {
        this(parent, source, createDefaultBinding(scope, replacementContextKey));
    }

    public QuickLitematicaMaterialReplacementScreen(
            Screen parent,
            ItemStack source,
            ReplacementBinding binding
    ) {
        this.parent = parent;
        this.setParent(parent);
        this.source = source.copy();
        this.binding = binding == null
                ? createDefaultBinding(QuickLitematicaContainerReplacements.Scope.CONTAINER, "")
                : binding;
        if (!this.binding.supportsCount() && !this.source.isEmpty()) {
            this.source.setCount(1);
        }
        this.title = "";
        this.useTitleHierarchy = false;

        for (Item item : Registries.ITEM) {
            if (item != Items.AIR && this.binding.isTargetAllowed(this.source, new ItemStack(item))) {
                this.allItems.add(item);
            }
        }
        this.sortCreativeOrder();

        Optional<ItemStack> current = this.binding.getCurrentTarget(this.source);
        ItemStack initial = current.orElse(this.binding.selectionOnly() ? ItemStack.EMPTY : this.source);
        if (!initial.isEmpty() && this.binding.isTargetAllowed(this.source, initial)) {
            this.selectedTemplate = initial.copy();
            this.selectedItem = initial.getItem();
            Text customName = initial.get(DataComponentTypes.CUSTOM_NAME);
            this.targetName = customName == null ? "" : customName.getString();
        }
        if (current.isPresent() && initial.isEmpty() && this.binding.supportsCount()) {
            this.selectedItem = Items.AIR;
            this.selectedTemplate = ItemStack.EMPTY;
        }
        this.countText = this.binding.preserveCountByDefault() ? "" : Integer.toString(Math.max(1, initial.getCount()));
        this.refreshFilter();
    }

    public static void open(Screen parent, ItemStack source) {
        open(parent, source, QuickLitematicaContainerReplacements.Scope.CONTAINER);
    }

    public static void open(
            Screen parent,
            ItemStack source,
            QuickLitematicaContainerReplacements.Scope scope
    ) {
        open(parent, source, scope, "");
    }

    public static void open(
            Screen parent,
            ItemStack source,
            QuickLitematicaContainerReplacements.Scope scope,
            String replacementContextKey
    ) {
        open(parent, source, createDefaultBinding(scope, replacementContextKey));
    }

    public static void open(
            Screen parent,
            ItemStack source,
            ReplacementBinding binding
    ) {
        if (parent == null || source == null || (source.isEmpty() && (binding == null || (!binding.supportsCount() && !binding.selectionOnly())))) {
            return;
        }
        GuiBase.openGui(new QuickLitematicaMaterialReplacementScreen(
                parent,
                source,
                binding
        ));
    }

    @Override
    public void initGui() {
        super.initGui();
        this.panelWidth = Math.min(PREFERRED_PANEL_WIDTH, Math.max(340, this.getScreenWidth() - 16));
        this.panelHeight = Math.min(PREFERRED_PANEL_HEIGHT, Math.max(260, this.getScreenHeight() - 16));
        this.panelX = Math.max(4, (this.getScreenWidth() - this.panelWidth) / 2);
        this.panelY = Math.max(4, (this.getScreenHeight() - this.panelHeight) / 2);

        this.searchField = new GuiTextFieldGeneric(this.panelX + 12, this.panelY + 42, this.panelWidth - 24, 20, this.textRenderer);
        this.searchField.setTextWrapper(this.searchText);
        this.searchField.setMaxLengthWrapper(80);
        this.addTextField(this.searchField, field -> {
            this.searchText = field.getTextWrapper();
            this.rowIndex = 0;
            this.refreshFilter();
            return true;
        });

        int buttonY = this.panelY + this.panelHeight - 30;
        ButtonGeneric apply = new ButtonGeneric(this.panelX + this.panelWidth - 62, buttonY, 50, 20,
                StringUtils.translate("quickcraft.litematica.button.material_replace_apply"));
        this.addButton(apply, (button, mouseButton) -> this.apply());

        if (!this.binding.selectionOnly()) {
            ButtonGeneric clear = new ButtonGeneric(this.panelX + 90, buttonY, 64, 20,
                    StringUtils.translate(this.binding.canKeepOriginal()
                            ? "quickcraft.litematica.button.material_replace_follow_summary"
                            : "quickcraft.litematica.button.material_replace_clear"));
            this.addButton(clear, (button, mouseButton) -> this.clear());
        }

        ButtonGeneric mainHand = new ButtonGeneric(this.panelX + 12, buttonY, 74, 20,
                StringUtils.translate("quickcraft.litematica.button.material_replace_main_hand"));
        this.addButton(mainHand, (button, mouseButton) -> this.readMainHand());

        int nameFieldX = this.getNameFieldX();
        this.nameField = new GuiTextFieldGeneric(
                nameFieldX,
                this.getBottomBoxY() + 5,
                this.getNameFieldWidth(),
                18,
                this.textRenderer
        );
        this.nameField.setDrawsBackground(true);
        this.nameField.setTextWrapper(this.targetName);
        this.nameField.setMaxLengthWrapper(64);
        this.addTextField(this.nameField, field -> {
            this.targetName = field.getTextWrapper();
            this.validationError = "";
            return true;
        });

        if (this.binding.supportsCount()) {
            int editY = this.getBottomBoxY() + 5;
            this.countField = new GuiTextFieldGeneric(this.getCountFieldX(), editY, 32, 18, this.textRenderer);
            this.countField.setTextWrapper(this.countText);
            this.countField.setMaxLengthWrapper(4);
            this.addTextField(this.countField, field -> {
                this.countText = field.getTextWrapper();
                this.validationError = "";
                return true;
            });
            ButtonGeneric empty = new ButtonGeneric(this.panelX + this.panelWidth - 66, editY, 48, 18,
                    StringUtils.translate("quickcraft.litematica.button.material_replace_empty"));
            this.addButton(empty, (button, mouseButton) -> {
                this.selectedItem = Items.AIR;
                this.selectedTemplate = ItemStack.EMPTY;
                this.targetName = "";
                this.nameField.setTextWrapper("");
                this.validationError = "";
            });
            if (this.binding.canKeepOriginal()) {
                ButtonGeneric keep = new ButtonGeneric(this.panelX + 158, buttonY, 54, 20,
                        StringUtils.translate("quickcraft.litematica.button.material_replace_keep_original"));
                this.addButton(keep, (button, mouseButton) -> {
                    this.binding.keepOriginal(this.source);
                    this.refreshParent();
                    this.closeToParent();
                });
            }
        }

        ButtonGeneric cancel = new ButtonGeneric(this.panelX + this.panelWidth - 116, buttonY, 50, 20,
                StringUtils.translate("quickcraft.litematica.button.material_replace_cancel"));
        this.addButton(cancel, (button, mouseButton) -> this.closeToParent());
    }

    @Override
    protected void drawMaterialFields(int mouseX, int mouseY, Context drawContext) {
        // GuiBase draws fields before drawContents; this screen renders them once after its panel.
    }

    @Override
    protected void drawMaterialButtons(int mouseX, int mouseY, float partialTicks, Context drawContext) {
        // GuiBase draws buttons before drawContents; this screen renders them once after its panel.
    }

    @Override
    public boolean materialMouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (super.materialMouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }

        int gridX = this.getGridX();
        int gridY = this.panelY + GRID_TOP;
        int cellSize = this.getGridCellSize();
        int gridHeight = this.getGridRows() * cellSize;
        int scrollbarX = this.panelX + this.panelWidth - 18;
        if (mouseX >= scrollbarX - 3 && mouseX < scrollbarX + 9
                && mouseY >= gridY && mouseY < gridY + gridHeight) {
            int maxRow = this.getMaxRowIndex();
            if (maxRow > 0) {
                int thumbY = this.getScrollbarThumbY(gridY);
                this.draggingScrollbar = true;
                this.scrollbarDragOffset = Math.max(0, mouseY - thumbY);
                this.setRowFromScrollbarMouse(mouseY);
            }
            return true;
        }
        if (mouseX < gridX || mouseX >= gridX + GRID_COLUMNS * cellSize
                || mouseY < gridY || mouseY >= gridY + gridHeight) {
            return true;
        }

        int index = (this.rowIndex + (mouseY - gridY) / cellSize) * GRID_COLUMNS
                + (mouseX - gridX) / cellSize;
        if (index >= 0 && index < this.filteredItems.size()) {
            this.selectedItem = this.filteredItems.get(index);
            if (this.selectedTemplate.getItem() != this.selectedItem) {
                this.selectedTemplate = new ItemStack(this.selectedItem);
            }
            this.validationError = "";
        }
        return true;
    }

    @Override
    public boolean materialMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= this.panelX && mouseX < this.panelX + this.panelWidth
                && mouseY >= this.panelY + GRID_TOP
                && mouseY < this.panelY + GRID_TOP + this.getGridRows() * this.getGridCellSize()) {
            int maxRow = this.getMaxRowIndex();
            this.rowIndex = Math.max(0, Math.min(maxRow, this.rowIndex + (verticalAmount < 0 ? 1 : -1)));
            return true;
        }
        return true;
    }

    @Override
    public boolean materialMouseReleased(int mouseX, int mouseY, int mouseButton) {
        super.materialMouseReleased(mouseX, mouseY, mouseButton);
        this.draggingScrollbar = false;
        return true;
    }

    @Override
    public boolean materialMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            this.setRowFromScrollbarMouse((int) mouseY - this.scrollbarDragOffset + this.getScrollbarThumbHeight() / 2);
            return true;
        }
        return super.materialMouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean materialKeyTyped(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.closeToParent();
            return true;
        }
        return super.materialKeyTyped(keyCode, scanCode, modifiers);
    }

    @Override
    protected void drawMaterialContents(Context drawContext, int mouseX, int mouseY, float partialTicks) {
        if (this.parent != null) {
            drawContext.parent(this.parent, mouseX, mouseY, partialTicks);
        }
        drawContext.rect(0, 0, this.getScreenWidth(), this.getScreenHeight(), 0x66000000);
        drawContext.box(this.panelX, this.panelY, this.panelWidth, this.panelHeight, 0xFF1E2228, 0xFF8B929B);
        drawContext.rect(this.panelX + 1, this.panelY + 1, this.panelWidth - 2, 24, 0xFF30353C);

        String title = this.binding.selectionOnly() ? this.binding.selectionTitle()
                : StringUtils.translate("quickcraft.litematica.title.material_replace", this.source.getName().getString());
        if (!this.binding.selectionOnly()) { drawContext.drawItem(this.source, this.panelX + 12, this.panelY + 4); }
        drawContext.drawText(this.textRenderer, title, this.panelX + (this.binding.selectionOnly() ? 12 : 34), this.panelY + 8, 0xFFFFFFFF, false);

        int gridX = this.getGridX();
        int gridY = this.panelY + GRID_TOP;
        int cellSize = this.getGridCellSize();
        drawContext.rect(gridX - 2, gridY - 2, GRID_COLUMNS * cellSize + 4, this.getGridRows() * cellSize + 4, 0x6630353D);
        int first = this.rowIndex * GRID_COLUMNS;
        int end = Math.min(this.filteredItems.size(), first + this.getGridRows() * GRID_COLUMNS);

        // 先完成所有格子背景和边框，再统一渲染物品，避免物品渲染切换深度/混合状态后影响相邻格子。
        for (int index = first; index < end; index++) {
            int local = index - first;
            int x = gridX + (local % GRID_COLUMNS) * cellSize;
            int y = gridY + (local / GRID_COLUMNS) * cellSize;
            int itemBoxSize = cellSize - 4;
            boolean selected = this.selectedItem == this.filteredItems.get(index);
            drawContext.box(x + 2, y + 2, itemBoxSize, itemBoxSize,
                    selected ? 0x665B8CFF : 0x222A2F36,
                    selected ? 0xFFB7C7FF : 0x44656D78);
        }
        for (int index = first; index < end; index++) {
            int local = index - first;
            int x = gridX + (local % GRID_COLUMNS) * cellSize;
            int y = gridY + (local / GRID_COLUMNS) * cellSize;
            ItemStack stack = new ItemStack(this.filteredItems.get(index));
            int itemOffset = (cellSize - 16) / 2;
            drawContext.drawItem(stack, x + itemOffset, y + itemOffset);
        }
        this.drawScrollbar(drawContext, gridY);

        int bottomBoxY = this.getBottomBoxY();
        int sourceBoxX = this.panelX + 12;
        int sourceBoxWidth = this.getBottomColumnWidth();
        int targetBoxX = this.getTargetColumnX();
        drawContext.box(sourceBoxX, bottomBoxY, this.panelWidth - 24, 28, 0x332A2F36, 0xFF737B85);
        if (!this.binding.selectionOnly()) {
            drawContext.drawItem(this.source, sourceBoxX + 6, bottomBoxY + 6);
            drawContext.drawItemInSlot(this.textRenderer, this.source, sourceBoxX + 6, bottomBoxY + 6);
            String sourceBaseName = this.source.isEmpty() ? StringUtils.translate("quickcraft.litematica.label.material_replace_empty_slot")
                    : this.source.getItem().getName().getString();
            Text sourceCustomName = this.source.get(DataComponentTypes.CUSTOM_NAME);
            String sourceDisplayName = sourceBaseName;
            if (sourceCustomName != null && !sourceCustomName.getString().isBlank()) {
                sourceDisplayName += "  " + sourceCustomName.getString();
            }
            drawContext.drawText(
                    this.textRenderer,
                    this.fitText(sourceDisplayName, sourceBoxWidth - 34),
                    sourceBoxX + 28,
                    bottomBoxY + 9,
                    0xFFFFFFFF,
                    false
            );

            drawContext.drawText(this.textRenderer, ">", sourceBoxX + sourceBoxWidth + 3, bottomBoxY + 9,
                    0xFF8FD7FF, false);
        }
        if (this.selectedItem != null) {
            ItemStack selectedStack = this.editedTarget();
            drawContext.drawItem(selectedStack, targetBoxX + 6, bottomBoxY + 6);
            drawContext.drawItemInSlot(this.textRenderer, selectedStack, targetBoxX + 6, bottomBoxY + 6);
            drawContext.drawText(
                    this.textRenderer,
                    this.fitText(selectedStack.isEmpty()
                            ? StringUtils.translate("quickcraft.litematica.label.material_replace_empty_slot")
                            : selectedStack.getItem().getName().getString(), this.getNameFieldX() - targetBoxX - 32),
                    targetBoxX + 28,
                    bottomBoxY + 9,
                    0xFFFFFFFF,
                    false
            );
        } else {
            drawContext.drawText(this.textRenderer,
                    this.fitText(
                            StringUtils.translate("quickcraft.litematica.label.material_replace_target_hint"),
                            this.getNameFieldX() - targetBoxX - 12
                    ),
                    targetBoxX + 8,
                    bottomBoxY + 9,
                    0xFF9298A1,
                    false
            );
        }
        drawContext.textField(this.nameField, mouseX, mouseY, partialTicks);
        if (this.countField != null) {
            drawContext.drawText(this.textRenderer,
                    StringUtils.translate("quickcraft.litematica.label.material_replace_count_short"),
                    this.getCountLabelX(), bottomBoxY + 10, 0xFFAEB5BF, false);
            drawContext.textField(this.countField, mouseX, mouseY, partialTicks);
            if (this.countText.isBlank()) {
                drawContext.drawText(this.textRenderer,
                        StringUtils.translate("quickcraft.litematica.label.material_replace_count_keep"),
                        this.getCountFieldX() + 3, bottomBoxY + 10, 0xFF9298A1, false);
            }
        }
        String notice = this.validationError.isEmpty() ? this.binding.scopeDescription() : this.validationError;
        if (!notice.isEmpty()) {
            drawContext.drawText(this.textRenderer, this.fitText(notice, this.panelWidth - 24),
                    this.panelX + 12, bottomBoxY + 40, this.validationError.isEmpty() ? 0xFFAEB5BF : 0xFFFF9E9E, false);
        }
        if (this.targetName.isBlank()) {
            drawContext.drawText(this.textRenderer,
                    StringUtils.translate("quickcraft.litematica.label.material_replace_name_hint"),
                    this.getNameFieldX() + 5,
                    bottomBoxY + 10,
                    0xFF9298A1,
                    false
            );
        }
        drawContext.textField(this.searchField, mouseX, mouseY, partialTicks);
        if (this.searchText.isBlank()) {
            drawContext.drawText(
                    this.textRenderer,
                    StringUtils.translate("quickcraft.litematica.label.material_replace_search_hint"),
                    this.panelX + 20,
                    this.panelY + 48,
                    0xFF9298A1,
                    false
            );
        }
        super.drawMaterialButtons(mouseX, mouseY, partialTicks, drawContext);

        int hovered = this.getHoveredIndex(mouseX, mouseY, gridX, gridY);
        if (hovered >= 0) {
            String hoveredName = this.filteredItems.get(hovered).getName().getString();
            drawContext.drawText(
                    this.textRenderer,
                    this.fitText(hoveredName, this.panelWidth - 24),
                    this.panelX + 12,
                    this.panelY + GRID_TOP - 12,
                    0xFFAEB5BF,
                    false
            );
        }
    }

    private int getBottomBoxY() {
        return this.panelY + this.panelHeight - 94;
    }

    private int getGridRows() {
        return Math.min(GRID_ROWS, Math.max(1, (this.getBottomBoxY() - 8 - this.panelY - GRID_TOP) / this.getGridCellSize()));
    }

    private int getBottomColumnWidth() {
        return (this.getNameFieldX() - this.panelX - 30) / 2;
    }

    private int getTargetColumnX() {
        return this.binding.selectionOnly() ? this.panelX + 12 : this.panelX + 12 + this.getBottomColumnWidth() + 10;
    }

    private int getCountFieldX() {
        return this.panelX + this.panelWidth - 104;
    }

    private int getCountLabelX() {
        return this.getCountFieldX() - this.getStringWidth(StringUtils.translate(
                "quickcraft.litematica.label.material_replace_count_short")) - 4;
    }

    private int getNameFieldX() {
        int right = this.binding.supportsCount() ? this.getCountLabelX() - 6 : this.panelX + this.panelWidth - 18;
        return right - this.getNameFieldWidth();
    }

    private int getNameFieldWidth() {
        return Math.max(64, Math.min(96, this.panelWidth / 5));
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int end = text.length();
        while (end > 1 && this.textRenderer.getWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return end <= 1 ? ellipsis : text.substring(0, end) + ellipsis;
    }

    private int getHoveredIndex(int mouseX, int mouseY, int gridX, int gridY) {
        int cellSize = this.getGridCellSize();
        if (mouseX < gridX || mouseX >= gridX + GRID_COLUMNS * cellSize
                || mouseY < gridY || mouseY >= gridY + this.getGridRows() * cellSize) {
            return -1;
        }
        int index = (this.rowIndex + (mouseY - gridY) / cellSize) * GRID_COLUMNS
                + (mouseX - gridX) / cellSize;
        return index >= 0 && index < this.filteredItems.size() ? index : -1;
    }

    private int getGridX() {
        int gridWidth = GRID_COLUMNS * this.getGridCellSize();
        return this.panelX + Math.max(12, (this.panelWidth - 18 - gridWidth) / 2);
    }

    private int getGridCellSize() {
        int widthLimit = (this.panelWidth - 30) / GRID_COLUMNS;
        int heightLimit = (this.getBottomBoxY() - 8 - this.panelY - GRID_TOP) / GRID_ROWS;
        return Math.min(CELL_SIZE, Math.max(20, Math.min(widthLimit, heightLimit)));
    }

    private void refreshFilter() {
        String needle = this.searchText.trim().toLowerCase(Locale.ROOT);
        this.filteredItems.clear();
        for (Item item : this.allItems) {
            ItemStack stack = new ItemStack(item);
            String id = Registries.ITEM.getId(item).toString().toLowerCase(Locale.ROOT);
            String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || id.contains(needle) || path.contains(needle) || name.contains(needle)) {
                this.filteredItems.add(item);
            }
        }
        int maxRow = this.getMaxRowIndex();
        this.rowIndex = Math.max(0, Math.min(maxRow, this.rowIndex));
    }

    private int getMaxRowIndex() {
        return Math.max(0, (this.filteredItems.size() - 1) / GRID_COLUMNS - this.getGridRows() + 1);
    }

    private int getScrollbarThumbHeight() {
        int gridHeight = this.getGridRows() * this.getGridCellSize();
        int totalRows = Math.max(this.getGridRows(), (this.filteredItems.size() + GRID_COLUMNS - 1) / GRID_COLUMNS);
        return Math.max(18, gridHeight * this.getGridRows() / totalRows);
    }

    private void drawScrollbar(Context drawContext, int gridY) {
        int scrollbarX = this.panelX + this.panelWidth - 18;
        int gridHeight = this.getGridRows() * this.getGridCellSize();
        int maxRow = this.getMaxRowIndex();
        int thumbHeight = this.getScrollbarThumbHeight();
        int thumbY = this.getScrollbarThumbY(gridY);

        drawContext.box(scrollbarX, gridY, 6, gridHeight, 0x44171B20, 0xFF68717B);
        drawContext.rect(scrollbarX + 1, thumbY + 1, 4, thumbHeight - 2,
                maxRow == 0 ? 0xFF68717B : 0xFFB7BEC7);
    }

    private int getScrollbarThumbY(int gridY) {
        int gridHeight = this.getGridRows() * this.getGridCellSize();
        int maxRow = this.getMaxRowIndex();
        int travel = Math.max(0, gridHeight - this.getScrollbarThumbHeight());
        return gridY + (maxRow == 0 ? 0 : travel * this.rowIndex / maxRow);
    }

    private void setRowFromScrollbarMouse(int mouseY) {
        int gridY = this.panelY + GRID_TOP;
        int travel = Math.max(1, this.getGridRows() * this.getGridCellSize() - this.getScrollbarThumbHeight());
        double fraction = (double) (mouseY - gridY - this.getScrollbarThumbHeight() / 2) / travel;
        this.rowIndex = Math.max(0, Math.min(this.getMaxRowIndex(),
                (int) Math.round(fraction * this.getMaxRowIndex())));
    }

    private void sortCreativeOrder() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            ItemGroups.updateDisplayContext(
                    client.world.getEnabledFeatures(),
                    client.player.isCreativeLevelTwoOp(),
                    client.world.getRegistryManager()
            );
        }

        Map<Item, Integer> order = new java.util.IdentityHashMap<>();
        int index = 0;
        for (RegistryKey<ItemGroup> groupKey : CREATIVE_GROUP_ORDER) {
            ItemGroup group = Registries.ITEM_GROUP.get(groupKey.getValue());
            if (group == null) {
                continue;
            }
            for (ItemStack displayStack : group.getDisplayStacks()) {
                order.putIfAbsent(displayStack.getItem(), index++);
            }
        }

        this.allItems.sort(Comparator
                .comparingInt((Item item) -> order.getOrDefault(item, Integer.MAX_VALUE))
                .thenComparing(item -> Registries.ITEM.getId(item).toString()));
    }

    private void readMainHand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.getMainHandStack().isEmpty()) {
            return;
        }
        ItemStack hand = client.player.getMainHandStack();
        if (!this.binding.isTargetAllowed(this.source, hand)) {
            return;
        }
        this.selectedItem = hand.getItem();
        this.selectedTemplate = hand.copy();
        Text customName = hand.get(DataComponentTypes.CUSTOM_NAME);
        this.targetName = customName == null ? "" : customName.getString();
        this.nameField.setTextWrapper(this.targetName);
        if (this.countField != null) {
            this.countText = Integer.toString(hand.getCount());
            this.countField.setTextWrapper(this.countText);
        }
        this.validationError = "";
    }

    private ItemStack editedTarget() {
        ItemStack target = this.selectedTemplate.copy();
        if (this.selectedItem == Items.AIR) { return ItemStack.EMPTY; }
        Text name = target.get(DataComponentTypes.CUSTOM_NAME);
        if (this.targetName.isBlank()) {
            target.remove(DataComponentTypes.CUSTOM_NAME);
        } else if (name == null || !this.targetName.equals(name.getString())) {
            target.set(DataComponentTypes.CUSTOM_NAME, Text.literal(this.targetName));
        }
        int count = Math.max(1, this.source.getCount());
        if (this.binding.supportsCount() && !this.countText.isBlank()) {
            try { count = Integer.parseInt(this.countText.trim()); }
            catch (NumberFormatException ignored) { count = 0; }
        }
        target.setCount(count);
        return target;
    }

    private void apply() {
        if (this.selectedItem == null) {
            return;
        }
        ItemStack target = this.editedTarget();
        if (this.binding.supportsCount() && !target.isEmpty() && (target.getCount() < 1 || target.getCount() > target.getMaxCount())) {
            this.validationError = StringUtils.translate("quickcraft.litematica.label.material_replace_count_error", this.selectedTemplate.getMaxCount());
            return;
        }
        boolean preserveCount = this.binding.supportsCount() && this.countText.isBlank();
        this.validationError = this.binding.validateTarget(this.source, target, preserveCount);
        if (!this.validationError.isEmpty()) {
            return;
        }
        if (!this.binding.isTargetAllowed(this.source, target)) {
            return;
        }
        this.binding.apply(this.source, target, preserveCount);
        this.refreshParent();
        this.closeToParent();
    }

    private void clear() {
        this.binding.clear(this.source);
        this.refreshParent();
        this.closeToParent();
    }

    private void refreshParent() {
        if (this.parent instanceof fi.dy.masa.litematica.gui.GuiMaterialList gui) {
            gui.getMaterialList().reCreateMaterialList();
            gui.initGui();
        } else if (this.parent instanceof RefreshableParent refreshableParent) {
            refreshableParent.quickcraft$refreshAfterReplacement();
        }
    }

    private void closeToParent() {
        GuiBase.openGui(this.parent);
    }

    private static ReplacementBinding createDefaultBinding(
            QuickLitematicaContainerReplacements.Scope scope,
            String replacementContextKey
    ) {
        QuickLitematicaContainerReplacements.Scope effectiveScope =
                scope == null ? QuickLitematicaContainerReplacements.Scope.CONTAINER : scope;
        String effectiveContextKey = replacementContextKey == null ? "" : replacementContextKey;
        return new ReplacementBinding() {
            @Override
            public boolean isTargetAllowed(ItemStack source, ItemStack target) {
                return QuickLitematicaContainerReplacements.isReplacementAllowed(source, target, effectiveScope);
            }

            @Override
            public Optional<ItemStack> getCurrentTarget(ItemStack source) {
                return QuickLitematicaContainerReplacements.getReplacementTarget(
                        source,
                        effectiveScope,
                        effectiveContextKey
                );
            }

            @Override
            public void apply(ItemStack source, ItemStack target) {
                QuickLitematicaContainerReplacements.setReplacementRule(
                        source,
                        target,
                        effectiveScope,
                        effectiveContextKey
                );
            }

            @Override
            public void clear(ItemStack source) {
                QuickLitematicaContainerReplacements.removeReplacementRule(
                        source,
                        effectiveScope,
                        effectiveContextKey
                );
            }
        };
    }

    public interface ReplacementBinding {
        default boolean selectionOnly() { return false; }

        default String selectionTitle() { return ""; }
        default boolean supportsCount() { return false; }

        default boolean preserveCountByDefault() { return false; }

        default boolean canKeepOriginal() { return false; }

        default String scopeDescription() { return ""; }

        default void keepOriginal(ItemStack source) { }

        default String validateTarget(ItemStack source, ItemStack target, boolean preserveCount) { return ""; }

        default void apply(ItemStack source, ItemStack target, boolean preserveCount) { this.apply(source, target); }

        default boolean isTargetAllowed(ItemStack source, ItemStack target) {
            return target != null && (!target.isEmpty() || this.supportsCount());
        }

        Optional<ItemStack> getCurrentTarget(ItemStack source);

        void apply(ItemStack source, ItemStack target);

        void clear(ItemStack source);
    }

    public interface RefreshableParent {
        void quickcraft$refreshAfterReplacement();
    }
}
