package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.village.TradedItem;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
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
@Mixin(MerchantScreenHandler.class)
public abstract class QuickContainerLockMerchantScreenHandlerMixin extends ScreenHandler {
    protected QuickContainerLockMerchantScreenHandlerMixin(ScreenHandlerType<?> type, int syncId) {
        super(type, syncId);
    }

    @Shadow
    public abstract TradeOfferList getRecipes();

    @Inject(method = "autofill", at = @At("HEAD"), cancellable = true)
    private void quickcraft$blockMerchantAutofillIntoLockedPayment(int slot, TradedItem stack, CallbackInfo ci) {
        MerchantScreenHandler handler = (MerchantScreenHandler) (Object) this;
        if (stack == null) {
            return;
        }

        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            Slot playerSlot = handler.getSlot(slotId);
            if (QuickContainerLock.isLockedSlot(handler, playerSlot)) {
                continue;
            }

            ItemStack playerStack = playerSlot.getStack();
            if (!playerStack.isEmpty() && stack.matches(playerStack)) {
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
    public void switchTo(int recipeIndex) {
        MerchantScreenHandler handler = (MerchantScreenHandler) (Object) this;
        if (recipeIndex < 0 || this.getRecipes().size() <= recipeIndex) {
            return;
        }

        ItemStack firstInput = this.getSlot(0).getStack();
        if (!firstInput.isEmpty()) {
            if (!this.insertItem(firstInput, 3, 39, true)) {
                return;
            }
            this.getSlot(0).setStack(firstInput);
        }

        ItemStack secondInput = this.getSlot(1).getStack();
        if (!secondInput.isEmpty()) {
            if (!this.insertItem(secondInput, 3, 39, true)) {
                return;
            }
            this.getSlot(1).setStack(secondInput);
        }

        if (this.getSlot(0).getStack().isEmpty() && this.getSlot(1).getStack().isEmpty()) {
            TradeOffer offer = this.getRecipes().get(recipeIndex);
            this.quickcraft$autofillFromUnlockedPlayerSlots(handler, 0, offer.getDisplayedFirstBuyItem());
            Optional<TradedItem> secondBuyItem = offer.getSecondBuyItem();
            secondBuyItem.ifPresent(item -> this.quickcraft$autofillFromUnlockedPlayerSlots(handler, 1, item));
        }
    }

    private void quickcraft$autofillFromUnlockedPlayerSlots(MerchantScreenHandler handler, int targetSlot, ItemStack template) {
        for (int slotId = 3; slotId < 39; slotId++) {
            Slot playerSlot = this.getSlot(slotId);
            if (playerSlot instanceof TradeOutputSlot || QuickContainerLock.isLockedSlot(handler, playerSlot)) {
                continue;
            }

            ItemStack playerStack = playerSlot.getStack();
            if (playerStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(playerStack.copyWithCount(1), template.copyWithCount(1))) {
                continue;
            }

            ItemStack inputStack = this.getSlot(targetSlot).getStack();
            if (!inputStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(playerStack, inputStack)) {
                continue;
            }

            int maxCount = playerStack.getMaxCount();
            int moveCount = Math.min(maxCount - inputStack.getCount(), playerStack.getCount());
            if (moveCount <= 0) {
                continue;
            }

            ItemStack movedStack = playerStack.copyWithCount(inputStack.getCount() + moveCount);
            playerStack.decrement(moveCount);
            this.getSlot(targetSlot).setStack(movedStack);
            if (movedStack.getCount() >= maxCount) {
                break;
            }
        }
    }

    private void quickcraft$autofillFromUnlockedPlayerSlots(MerchantScreenHandler handler, int targetSlot, TradedItem tradedItem) {
        for (int slotId = 3; slotId < 39; slotId++) {
            Slot playerSlot = this.getSlot(slotId);
            if (playerSlot instanceof TradeOutputSlot || QuickContainerLock.isLockedSlot(handler, playerSlot)) {
                continue;
            }

            ItemStack playerStack = playerSlot.getStack();
            if (playerStack.isEmpty() || !tradedItem.matches(playerStack)) {
                continue;
            }

            ItemStack inputStack = this.getSlot(targetSlot).getStack();
            if (!inputStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(playerStack, inputStack)) {
                continue;
            }

            int maxCount = playerStack.getMaxCount();
            int moveCount = Math.min(maxCount - inputStack.getCount(), playerStack.getCount());
            if (moveCount <= 0) {
                continue;
            }

            ItemStack movedStack = playerStack.copyWithCount(inputStack.getCount() + moveCount);
            playerStack.decrement(moveCount);
            this.getSlot(targetSlot).setStack(movedStack);
            if (movedStack.getCount() >= maxCount) {
                break;
            }
        }
    }
}
