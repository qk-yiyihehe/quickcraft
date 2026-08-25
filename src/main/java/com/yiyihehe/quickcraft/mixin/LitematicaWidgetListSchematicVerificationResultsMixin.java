package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.gui.GuiSchematicVerifier.BlockMismatchEntry;
import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicVerificationResults;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 让原理图验证结果列表支持渲染容器错填条目的悬停详情。
 */
@Mixin(value = WidgetListSchematicVerificationResults.class, remap = false)
public abstract class LitematicaWidgetListSchematicVerificationResultsMixin
        extends WidgetListBase<BlockMismatchEntry, WidgetSchematicVerificationResult> {
    public LitematicaWidgetListSchematicVerificationResultsMixin(
            int x,
            int y,
            int width,
            int height,
            @Nullable ISelectionListener<BlockMismatchEntry> selectionListener
    ) {
        super(x, y, width, height, selectionListener);
    }

    @Override
    protected boolean shouldRenderHoverStuff() {
        return true;
    }
}
