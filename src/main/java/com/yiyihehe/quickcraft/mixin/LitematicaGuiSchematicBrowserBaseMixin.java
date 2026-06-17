package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
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
        this.quickcraft$preview3DManager = QuickLitematicaPreview3D.init((GuiSchematicBrowserBase) (Object) this);
        super.initGui();
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int mouseButton) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseReleased(mouseX, mouseY, mouseButton)) {
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (this.quickcraft$preview3DManager != null
                && this.quickcraft$preview3DManager.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }
}
