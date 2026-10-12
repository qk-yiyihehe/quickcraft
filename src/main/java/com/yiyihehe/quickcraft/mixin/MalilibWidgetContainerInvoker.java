package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 让材料行 mixin 能调用 malilib 父容器的受保护按钮注册方法。 */
@Mixin(value = WidgetContainer.class, remap = false)
public interface MalilibWidgetContainerInvoker {
    @Invoker("addButton")
    <T extends ButtonBase> T quickcraft$addButton(T button, IButtonActionListener listener);
}
