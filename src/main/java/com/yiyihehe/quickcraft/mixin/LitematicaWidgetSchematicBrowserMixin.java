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
//#if MC>=12111
//$$ import fi.dy.masa.malilib.render.GuiContext;
//#else
import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12108
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

//#if MC>=12105
//$$ import java.nio.file.Path;
//#else
import java.io.File;
//#endif
//#if MC>=12103 && MC<12108
//$$ import java.util.function.Function;
//#endif
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
    //#if MC>=12105
    //$$ protected Map<Path, SchematicMetadata> cachedMetadata;
    //#else
    protected Map<File, SchematicMetadata> cachedMetadata;
    //#endif

    protected LitematicaWidgetSchematicBrowserMixin(
            int x,
            int y,
            int width,
            int height,
            IDirectoryCache cache,
            String browserContext,
            //#if MC>=12105
            //$$ Path defaultDirectory,
            //#else
            File defaultDirectory,
            //#endif
            @Nullable ISelectionListener<DirectoryEntry> selectionListener
    ) {
        super(x, y, width, height, cache, browserContext, defaultDirectory, selectionListener, Icons.FILE_ICON_LITEMATIC);
    }

    @Inject(method = "drawSelectedSchematicInfo", at = @At("TAIL"), remap = false)
    //#if MC>=12111
    //$$ private void quickcraft$draw3DPreview(GuiContext drawContext, @Nullable DirectoryEntry entry, CallbackInfo ci) {
    //#elseif MC>=12108
    //$$ private void quickcraft$draw3DPreview(DrawContext drawContext, @Nullable DirectoryEntry entry, CallbackInfo ci) {
    //#else
    private void quickcraft$draw3DPreview(@Nullable DirectoryEntry entry, DrawContext drawContext, CallbackInfo ci) {
    //#endif
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
                    //#if MC>=12111
                    //$$ target = "Lfi/dy/masa/malilib/render/GuiContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V"
                    //#elseif MC>=12108
                    //$$ target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V"
                    //#elseif MC>=12103
                    //$$ target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIII)V"
                    //#else
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"
                    //#endif
            )
    )
    private void quickcraft$skipVanillaPreviewWhen3DEnabled(
            //#if MC>=12111
            //$$ GuiContext drawContext,
            //#else
            DrawContext drawContext,
            //#endif
            //#if MC>=12108
            //$$ RenderPipeline renderPipeline,
            //#elseif MC>=12103
            //$$ Function<Identifier, RenderLayer> renderLayers,
            //#endif
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

        //#if MC>=12108
        //$$ drawContext.drawTexture(renderPipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight);
        //#elseif MC>=12103
        //$$ drawContext.drawTexture(renderLayers, texture, x, y, u, v, width, height, textureWidth, textureHeight);
        //#else
        drawContext.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        //#endif
    }

    @Redirect(
            method = "drawSelectedSchematicInfo",
            at = @At(
                    value = "INVOKE",
                    //#if MC>=12111
                    //$$ target = "Lfi/dy/masa/malilib/render/RenderUtils;drawOutlinedBox(Lfi/dy/masa/malilib/render/GuiContext;IIIIII)V",
                    //#elseif MC>=12108
                    //$$ target = "Lfi/dy/masa/malilib/render/RenderUtils;drawOutlinedBox(Lnet/minecraft/client/gui/DrawContext;IIIIII)V",
                    //#else
                    target = "Lfi/dy/masa/malilib/render/RenderUtils;drawOutlinedBox(IIIIII)V",
                    //#endif
                    ordinal = 1
            ),
            remap = false
    )
    private void quickcraft$skipVanillaPreviewBoxWhen3DEnabled(
            //#if MC>=12111
            //$$ GuiContext drawContext,
            //#elseif MC>=12108
            //$$ DrawContext drawContext,
            //#endif
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

        //#if MC>=12108
        //$$ RenderUtils.drawOutlinedBox(drawContext, x, y, width, height, fillColor, borderColor);
        //#else
        RenderUtils.drawOutlinedBox(x, y, width, height, fillColor, borderColor);
        //#endif
    }
}
