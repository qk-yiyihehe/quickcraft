package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 调用创造背包界面被保护的 onMouseClick。
 * 供 QuickCraft 复用原版逻辑模拟创造模式槽位点击。
 */
@Mixin(CreativeInventoryScreen.class)
public interface CreativeInventoryScreenInvoker {
    @Invoker("onMouseClick")
    void quickcraft$invokeOnMouseClick(Slot slot, int slotId, int button, SlotActionType actionType);
}
