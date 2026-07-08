package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 处理器界面左上角坐标的通用 accessor。
 *
 * <p>{@link HandledScreen} 的槽位坐标是相对 GUI 背景的，QuickCraft 的按钮、
 * 锁格覆盖层和鼠标命中测试都需要转换到屏幕坐标。字段名变化时最明显的症状是覆盖层整体偏移。</p>
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int quickcraft$getGuiLeft();

    @Accessor("y")
    int quickcraft$getGuiTop();
}
