package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.BitSet;
import java.util.List;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerMaterialsData;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerGroup;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.hasStackChange;

/** 原版箱子材质上的投影库存预览，点击只进入材料规则编辑。 */
final class QuickLitematicaTargetContainerScreen extends QuickLitematicaMaterialGui.BaseScreen implements
        QuickLitematicaMaterialReplacementScreen.RefreshableParent {
    // 高清材质包沿用原版纹理的逻辑坐标。
    private static final int TEXTURE_SIZE = 256;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_COLUMNS = 9;
    private static final int SLOT = 18;
    private static final int GRID_TOP = 17;
    private static final int BOTTOM = 6;
    private static final TextureLayout CHEST = new TextureLayout("generic_54", 6, 222);
    private static final TextureLayout SHULKER = new TextureLayout("shulker_box", 3, 167);

    private record TextureLayout(Identifier texture, int rows, int height) {
        TextureLayout(String name, int rows, int height) {
            this(Identifier.withDefaultNamespace("textures/gui/container/" + name + ".png"), rows, height);
        }
    }

    private final LitematicaSchematic schematic;
    private final String context;
    private final ContainerGroup group;
    private final BitSet selected = new BitSet();
    private final TextureLayout layout;
    private final int columns;
    private final int panelWidth;
    private final int rows;
    private List<ItemStack> targets;
    private int left;
    private int top;
    private int rowsVisible;
    private int firstRow;
    private ButtonGeneric batch;
    private ButtonGeneric clear;

    QuickLitematicaTargetContainerScreen(LitematicaSchematic schematic, String context, ContainerGroup group) {
        this.schematic = schematic;
        this.context = context;
        this.group = group;

        int capacity = group.slotStacks().size();
        this.layout = capacity == 27 && group.containerStack().getItem() instanceof BlockItem item && item
                .getBlock() instanceof ShulkerBoxBlock ? SHULKER : CHEST;
        this.columns = capacity == 9 ? 3 : Math.min(PANEL_COLUMNS, capacity);
        this.panelWidth = PANEL_WIDTH - (PANEL_COLUMNS - this.columns) * SLOT;
        this.rows = Math.ceilDiv(capacity, this.columns);
        this.useTitleHierarchy = false;
        this.title = "";
        this.refreshTargets();
    }

    @Override
    public void initGui() {
        super.initGui();
        this.rowsVisible = Math.min(Math.min(this.rows, this.layout.rows()), Math.max(1, (this.height - 76) / SLOT));
        this.firstRow = Math.min(this.firstRow, this.rows - this.rowsVisible);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = Math.max(4, (this.height - this.rowsVisible * SLOT - 74) / 2);

        int buttonY = this.buttonY();
        String batchLabel = StringUtils.translate(
                "quickcraft.litematica.button.batch_replace",
                this.group.slotStacks().size()
        );
        String clearLabel = StringUtils.translate("quickcraft.litematica.button.clear_selection");
        String saveLabel = StringUtils.translate("quickcraft.litematica.button.save_schematic_copy");
        int batchWidth = this.getStringWidth(batchLabel) + 12;
        int clearWidth = this.getStringWidth(clearLabel) + 12;
        int saveWidth = this.getStringWidth(saveLabel) + 12;
        int x = (this.width - batchWidth - clearWidth - saveWidth - 8) / 2;
        this.batch = new ButtonGeneric(x, buttonY, batchWidth, 20, batchLabel);
        this.addButton(this.batch, (button, mouseButton) -> this.openBatchEditor());
        this.clear = new ButtonGeneric(x + batchWidth + 4, buttonY, clearWidth, 20, clearLabel);
        this.addButton(this.clear, (button, mouseButton) -> {
            this.selected.clear();
            this.refreshSelection();
        });
        var save = new ButtonGeneric(x + batchWidth + clearWidth + 8, buttonY, saveWidth, 20, saveLabel);
        this.addButton(
                save,
                (button, mouseButton) -> QuickLitematicaContainerMaterials.saveCopy(this.schematic, this.context)
        );
        this.refreshSelection();
    }

    private void refreshSelection() {
        this.batch.setDisplayString(
                StringUtils.translate("quickcraft.litematica.button.batch_replace", this.selected.cardinality())
        );
        this.batch.setEnabled(!this.selected.isEmpty());
        this.clear.setEnabled(!this.selected.isEmpty());
    }

    private void refreshTargets() {
        this.targets = QuickLitematicaGlobalReplacements.containerSlots(
                this.group.slotStacks(),
                this.context,
                this.group.signature()
        );
    }

    @Override
    public void quickcraft$refreshAfterReplacement() {
        this.refreshTargets();

        if (DataManager.getMaterialList() instanceof QuickLitematicaContainerMaterials.ContainerMaterialList list
                && this.context.equals(list.data.replacementContextKey)) {
            list.onTaskCompleted();
        }
    }

    private int gridX() {
        return this.left + (this.panelWidth - this.columns * SLOT) / 2;
    }

    private int gridY() {
        return this.top + GRID_TOP;
    }

    private int buttonY() {
        return this.gridY() + this.rowsVisible * SLOT + 1 + BOTTOM + 3;
    }

    private int firstSlot() {
        return this.firstRow * this.columns;
    }

    private int endSlot() {
        return Math.min(this.group.slotStacks().size(), (this.firstRow + this.rowsVisible) * this.columns);
    }

    private int slotAt(int x, int y) {
        int dx = x - this.gridX();
        int dy = y - this.gridY();
        if (dx < 0 || dy < 0 || dx >= this.columns * SLOT || dy >= this.rowsVisible * SLOT) {
            return -1;
        }
        int slot = this.firstSlot() + dy / SLOT * this.columns + dx / SLOT;
        return slot < this.endSlot() ? slot : -1;
    }

    @Override
    public boolean materialMouseClicked(int x, int y, int button) {
        int slot = this.slotAt(x, y);
        if (button != 0 || slot < 0) {
            return super.materialMouseClicked(x, y, button);
        }

        if (QuickLitematicaMaterialGui.controlDown()) {
            boolean value = !this.selected.get(slot);
            this.selected.set(slot, value);
            QuickLitematicaSelectionDrag.start(this, x, y, value, (dragX, dragY, select) -> {
                int crossed = this.slotAt(dragX, dragY);
                if (crossed < 0 || this.selected.get(crossed) == select) {
                    return false;
                }
                this.selected.set(crossed, select);
                return true;
            }, this::refreshSelection);
            this.refreshSelection();
        } else {
            QuickLitematicaMaterialReplacementScreen.open(
                    this,
                    this.group.slotStacks().get(slot),
                    ContainerMaterialsData.createSlotReplacementBinding(this.context, this.group, slot)
            );
        }
        return true;
    }

    @Override
    public boolean materialMouseScrolled(int x, int y, double horizontal, double vertical) {
        if (this.rows > this.rowsVisible && vertical != 0) {
            this.firstRow = Math.clamp(this.firstRow + (vertical > 0 ? -1 : 1), 0, this.rows - this.rowsVisible);
            return true;
        }
        return super.materialMouseScrolled(x, y, horizontal, vertical);
    }

    private void openBatchEditor() {
        var slots = this.selected.stream().mapToObj(
                slot -> new ContainerMaterialsData.SummarySlot(this.group, slot, this.group.slotStacks().get(slot))
        ).toList();
        if (!slots.isEmpty()) {
            QuickLitematicaMaterialReplacementScreen.open(
                    this,
                    slots.getFirst().source(),
                    ContainerMaterialsData.createBatchSlotBinding(this.context, slots)
            );
        }
    }

    @Override
    protected void drawMaterialButtons(int mouseX, int mouseY, float ticks, QuickLitematicaMaterialGui.Context draw) {
        // malilib 先画按钮再画内容，遮罩必须在按钮前提交。
        draw.rect(0, 0, this.width, this.height, 0x66000000);
        super.drawMaterialButtons(mouseX, mouseY, ticks, draw);
    }

    @Override
    protected void drawMaterialContents(QuickLitematicaMaterialGui.Context draw, int mouseX, int mouseY, float ticks) {
        this.drawPanel(draw);
        String name = QuickLitematicaContainerMaterials.fitText(this.group.containerName(), this.panelWidth - 16);
        draw.drawText(this.font, name, this.left + 8, this.top + 6, 0xFF404040, false);

        int hovered = this.slotAt(mouseX, mouseY);
        for (int slot = this.firstSlot(); slot < this.endSlot(); slot++) {
            int x = this.gridX() + ((slot - this.firstSlot()) % this.columns) * SLOT;
            int y = this.gridY() + ((slot - this.firstSlot()) / this.columns) * SLOT;
            if (this.selected.get(slot)) {
                draw.rect(x + 1, y + 1, 16, 16, 0x995B8CFF);
            } else if (slot == hovered) {
                draw.rect(x + 1, y + 1, 16, 16, 0x55FFFFFF);
            }
        }

        for (int slot = this.firstSlot(); slot < this.endSlot(); slot++) {
            int x = this.gridX() + ((slot - this.firstSlot()) % this.columns) * SLOT;
            int y = this.gridY() + ((slot - this.firstSlot()) / this.columns) * SLOT;
            ItemStack source = this.group.slotStacks().get(slot);
            ItemStack target = this.targets.get(slot);
            if (hasStackChange(source, target)) {
                draw.outline(x + 1, y + 1, 16, 16, 0xFFB28B45);
            }
            if (!target.isEmpty()) {
                draw.drawItem(target, x + 1, y + 1);
                draw.drawItemInSlot(this.font, target, x + 1, y + 1);
            }
        }

        String hint = StringUtils.translate("quickcraft.litematica.label.target_container_selection");
        draw.drawText(
                this.font,
                hint,
                (this.width - this.getStringWidth(hint)) / 2,
                this.buttonY() + 24,
                0xFFFFFFFF,
                true
        );

        if (hovered >= 0) {
            ItemStack source = this.group.slotStacks().get(hovered);
            ItemStack target = this.targets.get(hovered);
            if (hasStackChange(source, target)) {
                draw.drawTooltip(
                        this.font,
                        List.of(
                                Component.translatable(
                                        "quickcraft.litematica.label.target_container_change",
                                        stackLabel(source),
                                        stackLabel(target)
                                )
                        ),
                        mouseX,
                        mouseY
                );
            } else if (!target.isEmpty()) {
                draw.drawItemTooltip(this.font, target, mouseX, mouseY);
            }
        }
    }

    private static String stackLabel(ItemStack stack) {
        return stack.isEmpty() ? StringUtils.translate("quickcraft.litematica.label.material_replace_empty_slot")
                : stack.getHoverName().getString() + " ×" + stack.getCount();
    }

    private void drawPanel(QuickLitematicaMaterialGui.Context draw) {
        int gridHeight = this.rowsVisible * SLOT;
        int gridEnd = GRID_TOP + gridHeight;
        if (this.rowsVisible == this.layout.rows()) {
            this.drawPanelStrip(draw, 0, 0, gridEnd + 1);
        } else {
            // 缩短时从槽区中间去掉行，保留材质包绘制的首尾圆角。
            int tail = this.rowsVisible == 1 ? 3 : SLOT;
            int cutoff = gridEnd - tail;
            int sourceEnd = GRID_TOP + this.layout.rows() * SLOT;
            this.drawPanelStrip(draw, 0, 0, cutoff);
            this.drawPanelStrip(draw, cutoff, sourceEnd - tail, tail + 1);
        }
        this.drawPanelStrip(draw, gridEnd + 1, this.layout.height() - BOTTOM, BOTTOM);
    }

    private void drawPanelStrip(QuickLitematicaMaterialGui.Context drawContext, int panelY, int textureV, int height) {
        int removedWidth = PANEL_WIDTH - this.panelWidth;
        if (removedWidth == 0) {
            this.drawTextureRegion(drawContext, this.left, this.top + panelY, 0, textureV, PANEL_WIDTH, height);
            return;
        }

        // 缩窄时从中间去掉多余列，保留两侧原生边框。
        int splitX = this.columns == 1 ? this.panelWidth / 2 : 7 + this.columns / 2 * SLOT;
        this.drawTextureRegion(drawContext, this.left, this.top + panelY, 0, textureV, splitX, height);
        this.drawTextureRegion(
                drawContext,
                this.left + splitX,
                this.top + panelY,
                splitX + removedWidth,
                textureV,
                this.panelWidth - splitX,
                height
        );
    }

    private void drawTextureRegion(
            QuickLitematicaMaterialGui.Context drawContext,
            int screenX,
            int screenY,
            int textureU,
            int textureV,
            int width,
            int height
    ) {
        drawContext.nativeContext.blit(
                RenderPipelines.GUI_TEXTURED,
                this.layout.texture(),
                screenX,
                screenY,
                textureU,
                textureV,
                width,
                height,
                TEXTURE_SIZE,
                TEXTURE_SIZE
        );
    }
}
