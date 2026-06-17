package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 HandledScreen 的左上角 GUI 偏移。
 * 供整理、按钮定位和槽位覆盖层把槽位坐标换算到屏幕坐标。
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int quickcraft$getGuiLeft();

    @Accessor("y")
    int quickcraft$getGuiTop();
}
