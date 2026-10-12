package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialGui.Context;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerReplacements.Scope;

public final class QuickLitematicaGlobalReplacementScreen extends QuickLitematicaMaterialGui.BaseScreen
        implements QuickLitematicaMaterialReplacementScreen.RefreshableParent {
    private final Scope scope;
    private int first;
    private final List<Draft> rows = new ArrayList<>();
    private boolean loaded;

    public QuickLitematicaGlobalReplacementScreen(Screen parent, Scope scope) {
        this.setParent(parent);
        this.scope = scope;
        this.title = StringUtils.translate(scope == Scope.CONTAINER
                ? "quickcraft.litematica.global.container_title" : "quickcraft.litematica.global.block_title");
    }

    private int visibleRows() { return Math.max(1, (this.height - 124) / 30); }

    static int pageStart(int requestedStart, int rowCount, int pageSize) {
        int lastStart = Math.max(0, rowCount - 1) / pageSize * pageSize;
        return Math.min(Math.max(0, requestedStart) / pageSize * pageSize, lastStart);
    }
    private int panelWidth() { return Math.min(600, this.width - 24); }
    private int panelX() { return (this.width - this.panelWidth()) / 2; }
    private int columnWidth() { return (this.panelWidth() - 78) / 2; }
    private int targetX() { return this.panelX() + this.columnWidth() + 24; }

    @Override
    public void initGui() {
        super.initGui();
        if (!this.loaded) {
            for (var rule : QuickLitematicaGlobalReplacements.rules(this.scope)) {
                this.rows.add(new Draft(QuickLitematicaGlobalReplacements.source(rule), rule.target()));
            }
            this.loaded = true;
        }
        this.first = pageStart(this.first, this.rows.size(), this.visibleRows());
        var add = new ButtonGeneric(this.panelX(), 30, -1, 20, StringUtils.translate("quickcraft.litematica.global.add"));
        this.addButton(add, (button, mouse) -> {
            this.rows.add(new Draft(ItemStack.EMPTY, ItemStack.EMPTY));
            this.first = pageStart(this.rows.size() - 1, this.rows.size(), this.visibleRows());
            this.initGui();
        });
        for (int index = this.first; index < Math.min(this.rows.size(), this.first + this.visibleRows()); index++) {
            var row = this.rows.get(index);
            int y = 68 + (index - this.first) * 30;
            this.addChoiceButton(row, true, this.panelX(), y);
            this.addChoiceButton(row, false, this.targetX(), y);
            var remove = new ButtonGeneric(this.panelX() + this.panelWidth() - 50, y, 50, 22,
                    StringUtils.translate("quickcraft.litematica.global.delete"));
            this.addButton(remove, (button, mouse) -> {
                if (!row.savedSource.isEmpty()) { QuickLitematicaGlobalReplacements.set(row.savedSource, null, this.scope); }
                this.rows.remove(row);
                this.initGui();
            });
        }
        var previous = new ButtonGeneric(this.panelX(), this.height - 30, 38, 20, "<");
        previous.setEnabled(this.first > 0);
        this.addButton(previous, (button, mouse) -> { this.first -= this.visibleRows(); this.initGui(); });
        var next = new ButtonGeneric(this.panelX() + 42, this.height - 30, 38, 20, ">");
        next.setEnabled(this.first + this.visibleRows() < this.rows.size());
        this.addButton(next, (button, mouse) -> { this.first += this.visibleRows(); this.initGui(); });
        var back = new ButtonGeneric(this.panelX() + this.panelWidth() - 70, this.height - 30, 70, 20,
                StringUtils.translate("quickcraft.litematica.global.back"));
        this.addButton(back, (button, mouse) -> GuiBase.openGui(this.getParent()));
    }

    private void addChoiceButton(Draft row, boolean source, int x, int y) {
        var stack = source ? row.source : row.target;
        String label = stack.isEmpty() ? StringUtils.translate(source
                ? "quickcraft.litematica.global.choose_source" : "quickcraft.litematica.global.choose_target")
                : "      " + this.textRenderer.trimToWidth(stack.getName().getString(), this.columnWidth() - 38);
        var choice = new ButtonGeneric(x, y, this.columnWidth(), 22, label);
        this.addButton(choice, (button, mouse) -> this.pick(row, source));
    }

    @Override
    protected void drawMaterialButtons(int mouseX, int mouseY, float partialTicks, Context context) { }

    @Override
    protected void drawMaterialContents(Context context, int mouseX, int mouseY, float partialTicks) {
        int x = this.panelX();
        context.rect(x - 6, 54, this.panelWidth() + 12, Math.min(this.rows.size(), this.visibleRows()) * 30 + 18, 0xA0181C22);
        context.drawText(this.textRenderer, StringUtils.translate("quickcraft.litematica.global.source_heading"), x + 4, 56, 0xFFAEB5BF, false);
        context.drawText(this.textRenderer, StringUtils.translate("quickcraft.litematica.global.target_heading"), this.targetX() + 4, 56, 0xFFAEB5BF, false);
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(StringUtils.translate("quickcraft.litematica.global.runtime_only"),
                this.panelWidth()), x, this.height - 44, 0xFFFFD54F, false);
        String page = (this.first / this.visibleRows() + 1) + " / "
                + Math.max(1, (this.rows.size() + this.visibleRows() - 1) / this.visibleRows());
        context.drawText(this.textRenderer, page, x + 92, this.height - 24, 0xFFFFFFFF, false);
        super.drawMaterialButtons(mouseX, mouseY, partialTicks, context);
        for (int index = this.first; index < Math.min(this.rows.size(), this.first + this.visibleRows()); index++) {
            var row = this.rows.get(index);
            int y = 68 + (index - this.first) * 30;
            context.drawText(this.textRenderer, ">", x + this.columnWidth() + 9, y + 7, 0xFFFFD54F, false);
            if (!row.source.isEmpty()) { context.drawItem(row.source, x + 4, y + 3); }
            if (!row.target.isEmpty()) { context.drawItem(row.target, this.targetX() + 4, y + 3); }
        }
    }

    private void pick(Draft row, boolean sourceSide) {
        ItemStack current = sourceSide ? row.source : row.target;
        QuickLitematicaMaterialReplacementScreen.open(this, ItemStack.EMPTY,
                new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
            @Override public boolean selectionOnly() { return true; }
            @Override public String selectionTitle() { return StringUtils.translate(sourceSide
                    ? "quickcraft.litematica.global.choose_source" : "quickcraft.litematica.global.choose_target"); }
            @Override public boolean isTargetAllowed(ItemStack ignored, ItemStack selected) {
                if (selected.isEmpty()) { return false; }
                if (scope == Scope.CONTAINER) { return true; }
                return sourceSide || row.source.isEmpty() ? selected.getItem() instanceof net.minecraft.item.BlockItem
                        : QuickLitematicaContainerReplacements.isReplacementAllowed(row.source, selected, scope);
            }
            @Override public String scopeDescription() {
                return StringUtils.translate("quickcraft.litematica.global.choose_both");
            }
            @Override public Optional<ItemStack> getCurrentTarget(ItemStack ignored) {
                return current.isEmpty() ? Optional.empty() : Optional.of(current);
            }
            @Override public void apply(ItemStack ignored, ItemStack selected) {
                if (sourceSide) { row.source = selected.copyWithCount(1); }
                else { row.target = selected.copyWithCount(1); }
                if (!row.source.isEmpty() && !row.target.isEmpty()) {
                    if (!QuickLitematicaContainerReplacements.isReplacementAllowed(row.source, row.target, scope)) {
                        row.target = ItemStack.EMPTY;
                        return;
                    }
                    QuickLitematicaGlobalReplacements.set(row.source, row.target, scope, row.savedSource);
                    rows.removeIf(other -> other != row && ItemStack.areItemsAndComponentsEqual(other.source, row.source));
                    row.savedSource = ItemStack.areItemsAndComponentsEqual(row.source, row.target)
                            ? ItemStack.EMPTY : row.source.copy();
                }
            }
            @Override public void clear(ItemStack ignored) { }
        });
    }

    @Override public void quickcraft$refreshAfterReplacement() { this.initGui(); }

    private static final class Draft {
        private ItemStack source;
        private ItemStack target;
        private ItemStack savedSource;
        private Draft(ItemStack source, ItemStack target) {
            this.source = source.copy();
            this.target = target.copy();
            this.savedSource = source.copy();
        }
    }
}
