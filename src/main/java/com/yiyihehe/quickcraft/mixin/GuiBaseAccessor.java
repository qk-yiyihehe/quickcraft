package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * malilib 配置界面按钮列表的 accessor。
 *
 * <p>{@link GuiBase} 不走 Minecraft remap，字段访问必须 {@code remap = false}。
 * QuickCraft 在 Litematica 原理图验证器里插入按钮后，需要读取原按钮列表并调整布局。</p>
 */
@Mixin(value = GuiBase.class, remap = false)
public interface GuiBaseAccessor {
    @Accessor("buttons")
    List<ButtonBase> quickcraft$getButtons();
}
