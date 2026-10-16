package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = GuiListBase.class, remap = false)
public interface MalilibGuiListAccessor {
    @Invoker("getListWidget") WidgetListBase<?, ?> quickcraft$getListWidget();
    @Invoker("setListPosition") void quickcraft$setListPosition(int x, int y);
    @Invoker("reCreateListWidget") void quickcraft$reCreateListWidget();
    @Invoker("getListY") int quickcraft$getListY();
    @Invoker("getBrowserWidth") int quickcraft$getBrowserWidth();
}
