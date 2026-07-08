package com.yiyihehe.quickcraft.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 创造模式包装槽的底层槽位 accessor。
 *
 * <p>{@code CreativeInventoryScreen$CreativeSlot} 会把真实槽位包一层用于创造栏显示。
 * QuickCraft 判断玩家库存位置时必须回到被包装的 {@link Slot}，否则锁格和批量操作会误判槽位归属。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen$CreativeSlot")
public interface CreativeSlotAccessor {
    @Accessor("slot")
    Slot quickcraft$getWrappedSlot();
}
