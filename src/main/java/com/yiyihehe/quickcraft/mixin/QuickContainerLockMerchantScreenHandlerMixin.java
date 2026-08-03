package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * 村民补料会直接扫描玩家背包，不走普通 quick move。
 * 这里单独跳过被锁住的付款格。
 */
@Mixin(MerchantMenu.class)
public abstract class QuickContainerLockMerchantScreenHandlerMixin extends AbstractContainerMenu {
    protected QuickContainerLockMerchantScreenHandlerMixin(MenuType<?> type, int syncId) {
        super(type, syncId);
    }

    @Shadow
    public abstract MerchantOffers getOffers();

    @Inject(method = "moveFromInventoryToPaymentSlot", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockMerchantAutofillIntoLockedPayment(int slot, ItemCost stack, CallbackInfo ci) {
        MerchantMenu handler = (MerchantMenu) (Object) this;
        if (stack == null) {
            return;
        }

        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            Slot playerSlot = handler.getSlot(slotId);
            if (QuickContainerLock.isLockedSlot(handler, playerSlot)) {
                continue;
            }

            ItemStack playerStack = playerSlot.getItem();
            if (!playerStack.isEmpty() && stack.test(playerStack)) {
                return;
            }
        }

        ci.cancel();
    }

    /**
     * @author Codex
     * @reason 交易补料必须跳过锁格，否则原版会把锁住的同类付款物一起吃掉。
     */
    @Overwrite
    public void tryMoveItems(int recipeIndex) {
        MerchantMenu handler = (MerchantMenu) (Object) this;
        if (recipeIndex < 0 || this.getOffers().size() <= recipeIndex) {
            return;
        }

        ItemStack firstInput = this.getSlot(0).getItem();
        if (!firstInput.isEmpty()) {
            if (!this.moveItemStackTo(firstInput, 3, 39, true)) {
                return;
            }
            this.getSlot(0).set(firstInput);
        }

        ItemStack secondInput = this.getSlot(1).getItem();
        if (!secondInput.isEmpty()) {
            if (!this.moveItemStackTo(secondInput, 3, 39, true)) {
                return;
            }
            this.getSlot(1).set(secondInput);
        }

        if (this.getSlot(0).getItem().isEmpty() && this.getSlot(1).getItem().isEmpty()) {
            MerchantOffer offer = this.getOffers().get(recipeIndex);
            this.quickcraft$autofillFromUnlockedPlayerSlots(handler, 0, offer.getItemCostA());
            Optional<ItemCost> secondBuyItem = offer.getItemCostB();
            secondBuyItem.ifPresent(item -> this.quickcraft$autofillFromUnlockedPlayerSlots(handler, 1, item));
        }
    }

    private void quickcraft$autofillFromUnlockedPlayerSlots(MerchantMenu handler, int targetSlot, ItemCost tradedItem) {
        for (int slotId = 3; slotId < 39; slotId++) {
            Slot playerSlot = this.getSlot(slotId);
            if (playerSlot instanceof MerchantResultSlot || QuickContainerLock.isLockedSlot(handler, playerSlot)) {
                continue;
            }

            ItemStack playerStack = playerSlot.getItem();
            if (playerStack.isEmpty() || !tradedItem.test(playerStack)) {
                continue;
            }

            ItemStack inputStack = this.getSlot(targetSlot).getItem();
            if (!inputStack.isEmpty() && !ItemStack.isSameItemSameComponents(playerStack, inputStack)) {
                continue;
            }

            int maxCount = playerStack.getMaxStackSize();
            int moveCount = Math.min(maxCount - inputStack.getCount(), playerStack.getCount());
            if (moveCount <= 0) {
                continue;
            }

            ItemStack movedStack = playerStack.copyWithCount(inputStack.getCount() + moveCount);
            playerStack.shrink(moveCount);
            this.getSlot(targetSlot).set(movedStack);
            if (movedStack.getCount() >= maxCount) {
                break;
            }
        }
    }
}
