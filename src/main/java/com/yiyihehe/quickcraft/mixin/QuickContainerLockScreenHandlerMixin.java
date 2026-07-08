package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版物品分发链里的锁格跳过层。
 *
 * <p>{@link ScreenHandler#insertItem(ItemStack, int, int, boolean)} 先尝试合并已有堆叠，
 * 再尝试填入空槽。这里分别重定向两个 {@link Slot#getStack()} 调用点：合并阶段返回空堆叠，
 * 填空阶段返回屏障占位，让锁格同时避开“合并”和“填空”。ordinal 失效时通常表现为
 * shift 点击或自动补料仍会进入锁格。</p>
 */
@Mixin(ScreenHandler.class)
public abstract class QuickContainerLockScreenHandlerMixin {
    @Unique
    private static final ItemStack QUICKCRAFT_LOCKED_SLOT_PLACEHOLDER = new ItemStack(Items.BARRIER);

    @Redirect(
            method = "insertItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 0
            )
    )
    private ItemStack quickcraft$skipLockedSlotMerge(Slot slot) {
        if (QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)) {
            return ItemStack.EMPTY;
        }

        return slot.getStack();
    }

    @Redirect(
            method = "insertItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;",
                    ordinal = 1
            )
    )
    private ItemStack quickcraft$skipLockedSlotFill(Slot slot) {
        if (QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)) {
            return QUICKCRAFT_LOCKED_SLOT_PLACEHOLDER;
        }

        return slot.getStack();
    }
}
