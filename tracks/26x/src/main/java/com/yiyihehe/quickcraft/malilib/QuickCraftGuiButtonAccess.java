package com.yiyihehe.quickcraft.malilib;

import fi.dy.masa.malilib.gui.button.ButtonBase;

import java.util.List;

/** 由 GuiBaseAccessor 注入，避免 Litematica 业务层直接依赖 mixin 包。 */
public interface QuickCraftGuiButtonAccess {
    List<ButtonBase> quickcraft$getButtons();
}
