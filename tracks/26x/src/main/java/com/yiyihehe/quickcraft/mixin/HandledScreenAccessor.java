package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 AbstractContainerScreen 的左上角 GUI 偏移。
 * 供整理、按钮定位和槽位覆盖层把槽位坐标换算到屏幕坐标。
 */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("leftPos")
    int quickcraft$getGuiLeft();

    @Accessor("topPos")
    int quickcraft$getGuiTop();
}
