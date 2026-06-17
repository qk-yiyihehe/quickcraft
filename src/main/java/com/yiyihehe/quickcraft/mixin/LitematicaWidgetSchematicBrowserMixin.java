package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.interfaces.IDirectoryCache;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

@Mixin(value = WidgetSchematicBrowser.class, remap = false)
public abstract class LitematicaWidgetSchematicBrowserMixin extends WidgetFileBrowserBase {
    @Shadow
    @Final
    protected GuiSchematicBrowserBase parent;

    @Shadow
    @Final
    protected int infoWidth;

    @Shadow
    @Final
    protected int infoHeight;

    protected LitematicaWidgetSchematicBrowserMixin(
            int x,
            int y,
            int width,
            int height,
            IDirectoryCache cache,
            String browserContext,
            Path defaultDirectory,
            @Nullable ISelectionListener<DirectoryEntry> selectionListener
    ) {
        super(x, y, width, height, cache, browserContext, defaultDirectory, selectionListener, Icons.FILE_ICON_LITEMATIC);
    }

    @Inject(method = "drawSelectedSchematicInfo", at = @At("TAIL"), remap = false)
    private void quickcraft$draw3DPreview(@Nullable DirectoryEntry entry, DrawContext drawContext, CallbackInfo ci) {
        int infoX = this.posX + this.totalWidth - this.infoWidth;
        int infoY = this.posY;
        int height = Math.min(this.infoHeight, this.parent.getMaxInfoHeight());
        int size = Math.min(this.infoWidth - 32, Math.max(48, height - 152));
        int x = infoX + (this.infoWidth - size) / 2;
        int y = infoY + height - size - 8;

        QuickLitematicaPreview3D.render(this.parent, entry, drawContext, x, y, size);
    }
}
