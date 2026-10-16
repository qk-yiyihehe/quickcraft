package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WidgetSearchBar.class, remap = false)
public interface MalilibSearchBarAccessor {
    @Accessor("searchBox") GuiTextFieldGeneric quickcraft$getSearchBox();
}
