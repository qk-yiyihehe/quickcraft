package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = WidgetListBase.class, remap = false)
public interface MalilibWidgetListAccessor {
    @Accessor("listWidgets")
    List<? extends WidgetListEntryBase<?>> quickcraft$getVisibleEntries();
}
