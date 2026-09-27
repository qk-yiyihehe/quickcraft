package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = GuiSchematicBrowserBase.class, remap = false)
public abstract class LitematicaGuiSchematicBrowserBaseMixin
        extends GuiListBase<WidgetFileBrowserBase.DirectoryEntry, WidgetDirectoryEntry, WidgetSchematicBrowser> {
    @Unique
    private QuickLitematicaPreview3D.Manager quickcraft$preview3DManager;

    protected LitematicaGuiSchematicBrowserBaseMixin(int listX, int listY) {
        super(listX, listY);
    }

    @Override
    public void initGui() {
        this.quickcraft$preview3DManager = QuickLitematicaPreview3D.init(
                (GuiSchematicBrowserBase) (Object) this,
                this::quickcraft$refreshPreviewMetadata
        );
        super.initGui();
    }

    @Unique
    private void quickcraft$refreshPreviewMetadata() {
        WidgetSchematicBrowser widget = this.getListWidget();
        if (widget != null) {
            widget.clearSchematicMetadataCache();
        }
    }

    @Override
    protected void closeGui(boolean showParent) {
        QuickLitematicaPreview3D.close((GuiSchematicBrowserBase) (Object) this);
        super.closeGui(showParent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY)) {
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseReleased(click.x(), click.y(), click.button())) {
            return true;
        }

        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseClicked(click.x(), click.y(), click.button())) {
            return true;
        }

        return super.mouseClicked(click, doubled);
    }
}
