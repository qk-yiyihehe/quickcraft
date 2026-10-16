package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 读取 malilib GUI 控件，供验证器和材料列表统一布局。
 */
@Mixin(value = GuiBase.class, remap = false)
public interface GuiBaseAccessor extends QuickCraftGuiButtonAccess {
    @Accessor("widgets")
    List<WidgetBase> quickcraft$getWidgets();

    @Override
    @Accessor("buttons")
    List<ButtonBase> quickcraft$getButtons();
}
