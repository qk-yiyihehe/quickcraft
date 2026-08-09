package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Collection;

/**
 * JEI 的转移预检会把任意不可取出的背包格视为处理器损坏，并隐藏整个“+”按钮。
 * 这里只从 JEI 的候选来源和容量计算中排除 QC 锁格，实际锁状态与其它容器操作不变。
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.transfer.BasicRecipeTransferHandler", remap = false)
public abstract class JeiRecipeTransferMixin {
    @ModifyVariable(
            method = "getInventoryState",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1,
            require = 0,
            remap = false
    )
    private static Collection<Slot> quickcraft$excludeLockedPlayerSlots(Collection<Slot> inventorySlots) {
        return inventorySlots.stream()
                .filter(slot -> !(slot.container instanceof Inventory)
                        || !QuickContainerLock.isLockedSlot(slot))
                .toList();
    }
}
