package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 读取 malilib GuiBase 里维护的按钮列表。
 * 供原理图验证器插入自定义按钮后统一右移原有按钮。
 */
@Mixin(value = GuiBase.class, remap = false)
public interface GuiBaseAccessor {
    @Accessor("buttons")
    List<ButtonBase> quickcraft$getButtons();
}
