package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.malilib.gui.interfaces.IDirectoryCache;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Function;
import java.util.Map;

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

    @Shadow
    @Final
    protected Map<File, SchematicMetadata> cachedMetadata;

    protected LitematicaWidgetSchematicBrowserMixin(
            int x,
            int y,
            int width,
            int height,
            IDirectoryCache cache,
            String browserContext,
            File defaultDirectory,
            @Nullable ISelectionListener<DirectoryEntry> selectionListener
    ) {
        super(x, y, width, height, cache, browserContext, defaultDirectory, selectionListener, Icons.FILE_ICON_LITEMATIC);
    }

    @Inject(method = "drawSelectedSchematicInfo", at = @At("TAIL"), remap = false)
    private void quickcraft$draw3DPreview(@Nullable DirectoryEntry entry, DrawContext drawContext, CallbackInfo ci) {
        int infoX = this.posX + this.totalWidth - this.infoWidth;
        int infoY = this.posY;
		int height = Math.min(this.infoHeight, this.parent.getMaxInfoHeight());
		int size = Math.max(1, Math.min(this.infoWidth - 32, Math.max(48, height - 152)));
        int x = infoX + (this.infoWidth - size) / 2;
        int y = infoY + height - size - 8;

        SchematicMetadata metadata = entry == null ? null : this.cachedMetadata.get(entry.getFullPath());
        int[] previewPixels = metadata == null ? null : metadata.getPreviewImagePixelData();
        int previewSize = previewPixels == null ? 0 : (int) Math.sqrt(previewPixels.length);
        boolean hasEmbeddedPreview = previewPixels != null
                && previewPixels.length > 0
                && previewSize * previewSize == previewPixels.length;
        QuickLitematicaPreview3D.render(this.parent, entry, hasEmbeddedPreview, drawContext, x, y, size);
    }

    @Redirect(
            method = "drawSelectedSchematicInfo",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIII)V"
            )
    )
    private void quickcraft$skipVanillaPreviewWhen3DEnabled(
            DrawContext drawContext,
            Function<Identifier, RenderLayer> renderLayers,
            Identifier texture,
            int x,
            int y,
            float u,
            float v,
            int width,
            int height,
            int textureWidth,
            int textureHeight
    ) {
        if (QuickLitematicaPreview3D.is3DPreviewAvailable()
                && QuickCraftConfigs.shouldReplaceLitematicaPreviewWith3D()) {
            return;
        }

        drawContext.drawTexture(renderLayers, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    @Redirect(
            method = "drawSelectedSchematicInfo",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/render/RenderUtils;drawOutlinedBox(IIIIII)V",
                    ordinal = 1
            ),
            remap = false
    )
    private void quickcraft$skipVanillaPreviewBoxWhen3DEnabled(
            int x,
            int y,
            int width,
            int height,
            int fillColor,
            int borderColor
    ) {
        if (QuickLitematicaPreview3D.is3DPreviewAvailable()
                && QuickCraftConfigs.shouldReplaceLitematicaPreviewWith3D()) {
            return;
        }

        RenderUtils.drawOutlinedBox(x, y, width, height, fillColor, borderColor);
    }
}
