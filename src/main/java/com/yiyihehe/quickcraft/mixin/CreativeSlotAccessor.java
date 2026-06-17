package com.yiyihehe.quickcraft.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 拿到创造背包包装槽里真正的底层槽位。
 * 供 QuickCraft 在创造模式界面识别真实库存槽。
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen$CreativeSlot")
public interface CreativeSlotAccessor {
    @Accessor("slot")
    Slot quickcraft$getWrappedSlot();
}
