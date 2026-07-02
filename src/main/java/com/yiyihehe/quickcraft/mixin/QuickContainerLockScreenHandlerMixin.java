package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 在 ScreenHandler 的公共分发链里跳过锁格。
 * 这样原版 Shift、快速转移、回存、交易产物回包等走 insertItem 的路径都会一起生效。
 */
@Mixin(ScreenHandler.class)
public abstract class QuickContainerLockScreenHandlerMixin {
    @Shadow
    public abstract Slot getSlot(int index);

    /**
     * @author Codex
     * @reason 锁格子后，公共 insertItem 分发必须跳过锁空格和锁半组，避免任何 quick move 路径写入锁格。
     */
    @Overwrite
    public boolean insertItem(ItemStack stack, int startIndex, int endIndex, boolean fromLast) {
        boolean inserted = false;
        int slotIndex = fromLast ? endIndex - 1 : startIndex;

        if (stack.isStackable()) {
            while (!stack.isEmpty()) {
                if (fromLast) {
                    if (slotIndex < startIndex) {
                        break;
                    }
                } else if (slotIndex >= endIndex) {
                    break;
                }

                Slot slot = this.getSlot(slotIndex);
                if (!QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)) {
                    ItemStack slotStack = slot.getStack();
                    if (!slotStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, slotStack)) {
                        int mergedCount = slotStack.getCount() + stack.getCount();
                        int maxCount = Math.min(slot.getMaxItemCount(slotStack), stack.getMaxCount());
                        if (mergedCount <= maxCount) {
                            stack.setCount(0);
                            slotStack.setCount(mergedCount);
                            slot.markDirty();
                            inserted = true;
                        } else if (slotStack.getCount() < maxCount) {
                            stack.decrement(maxCount - slotStack.getCount());
                            slotStack.setCount(maxCount);
                            slot.markDirty();
                            inserted = true;
                        }
                    }
                }

                slotIndex += fromLast ? -1 : 1;
            }
        }

        if (!stack.isEmpty()) {
            slotIndex = fromLast ? endIndex - 1 : startIndex;

            while (true) {
                if (fromLast) {
                    if (slotIndex < startIndex) {
                        break;
                    }
                } else if (slotIndex >= endIndex) {
                    break;
                }

                Slot slot = this.getSlot(slotIndex);
                ItemStack slotStack = slot.getStack();
                if (slotStack.isEmpty()
                        && !QuickContainerLock.isLockedSlot((ScreenHandler) (Object) this, slot)
                        && slot.canInsert(stack)) {
                    int maxCount = slot.getMaxItemCount(stack);
                    slot.setStack(stack.split(Math.min(stack.getCount(), maxCount)));
                    slot.markDirty();
                    inserted = true;
                    break;
                }

                slotIndex += fromLast ? -1 : 1;
            }
        }

        return inserted;
    }
}
