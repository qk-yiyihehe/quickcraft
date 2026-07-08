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
 * 村民交易自动补料的锁格适配。
 *
 * <p>{@link MerchantScreenHandler} 的补料逻辑会直接扫描玩家背包，不完全依赖普通
 * {@link ScreenHandler#insertItem(ItemStack, int, int, boolean)} 分发链。这里单独处理
 * {@code autofill} 和 {@code switchTo}，避免锁住的同类付款物被交易界面拿走。</p>
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
     * @reason 1.21-1.21.1 的 MerchantScreenHandler.switchTo 会直接把玩家背包物品搬到付款槽；
     *         需要完整复刻该流程并跳过锁格，Redirect 无法覆盖两个付款槽和双材料交易的全部分支。
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
