package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 创造模式槽位点击的原版调用入口。
 *
 * <p>{@link CreativeInventoryScreen#onMouseClick(Slot, int, int, SlotActionType)}
 * 是受保护方法。QuickCraft 需要复用它处理创造栏包装槽、删除槽等特殊规则，
 * 不能直接退化成普通生存背包点击。</p>
 */
@Mixin(CreativeInventoryScreen.class)
public interface CreativeInventoryScreenInvoker {
    @Invoker("onMouseClick")
    void quickcraft$invokeOnMouseClick(Slot slot, int slotId, int button, SlotActionType actionType);
}
