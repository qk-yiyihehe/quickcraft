package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftMouseCraftAckExecutorTest {
    @Test
    void outputBatchPlansExactlyOneCtrlThrow() {
        assertThat(QuickCraftMouseCraftAckRules.plannedOutputThrows(true, true)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftAckRules.plannedOutputThrows(true, false)).isZero();
        assertThat(QuickCraftMouseCraftAckRules.plannedOutputThrows(false, true)).isZero();
    }

    @Test
    void outputSprayModeLatchesAfterInitialFillPhase() {
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                false, 2, 2, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.remainingOutputFillSlots(2, 1)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftAckRules.remainingOutputFillSlots(1, 1)).isZero();
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                false, 0, 3, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                true, 0, 9, true)).isTrue();
    }

    @Test
    void fullInventoryStartsOutputSprayEvenWhenOutputCouldStack() {
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                false, 0, 0, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                false, 2, 0, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode(
                false, 2, 2, false)).isTrue();
    }

    @Test
    void intermediateRecipeOutputStillStopsAsMissingLockedRecipeIngredients() {
        assertThat(QuickCraftMouseCraftAckRules.isMissingLockedRecipeTerminal(
                false, false, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.isMissingLockedRecipeTerminal(
                false, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.isMissingLockedRecipeTerminal(
                true, false, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.isMissingLockedRecipeTerminal(
                false, false, false)).isFalse();
    }

    @Test
    void tailFillUsesEvenShareWithoutOverfillingRecipeSlots() {
        assertThat(QuickCraftMouseCraftInventory.tailItemsPerSlot(64, 3, 64)).isEqualTo(21);
        assertThat(QuickCraftMouseCraftInventory.tailItemsPerSlot(64, 3, 1)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftInventory.tailItemsPerSlot(2, 3, 64)).isZero();
    }

    @Test
    void sampleRefillRequiresOneItemToRemainInEachSourceStack() {
        assertThat(QuickCraftMouseCraftInventory.usableIngredientCount(1, false)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftInventory.usableIngredientCount(64, false)).isEqualTo(64);
        assertThat(QuickCraftMouseCraftInventory.ingredientPickupButton(false)).isZero();
        assertThat(QuickCraftMouseCraftInventory.usableIngredientCount(1, true)).isZero();
        assertThat(QuickCraftMouseCraftInventory.usableIngredientCount(2, true)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftInventory.ingredientPickupButton(true)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftInventory.maximumRetainedHalfPickup(64)).isEqualTo(32);
        assertThat(QuickCraftMouseCraftInventory.maximumRetainedHalfPickup(16)).isEqualTo(8);
    }

    @Test
    void retainedHalfStackRefillFillsLowestRecipeSlotsUpToCapacity() {
        assertThat(QuickCraftMouseCraftInventory.retainedItemsPerSlot(32, 3, 64)).isEqualTo(10);
        assertThat(QuickCraftMouseCraftInventory.retainedItemsPerSlot(32, 3, 4)).isEqualTo(4);
        assertThat(QuickCraftMouseCraftInventory.retainedItemsPerSlot(1, 1, 64)).isEqualTo(1);
    }

    @Test
    void completePatternQuickMoveCannotMakeRepeatedIngredientsUneven() {
        assertThat(QuickCraftMouseCraftInventory.canQuickTopUpCompleteGroup(1, 10, 32)).isTrue();
        assertThat(QuickCraftMouseCraftInventory.canQuickTopUpCompleteGroup(3, 96, 96)).isTrue();
        assertThat(QuickCraftMouseCraftInventory.canQuickTopUpCompleteGroup(3, 64, 96)).isFalse();
    }

    @Test
    void fullSourceStackCannotQuickMoveIntoPartiallyFilledGridSlot() {
        assertThat(QuickCraftMouseCraftInventory.canQuickMoveWholeStackToGridSlot(
                64, 1, 64, 1, 1)).isFalse();
        assertThat(QuickCraftMouseCraftInventory.canQuickMoveWholeStackToGridSlot(
                63, 1, 64, 1, 1)).isTrue();
    }

    @Test
    void repeatedIngredientRemainderIsRebalancedAfterOneSlotRunsOut() {
        var moves = QuickCraftMouseCraftInventory.planGridTailBalance(new int[]{32, 32, 0});
        assertThat(moves).containsExactly(
                new QuickCraftMouseCraftInventory.GridBalanceMove(0, 2, 10),
                new QuickCraftMouseCraftInventory.GridBalanceMove(1, 2, 11));
    }

    @Test
    void materialLedgerUsesRefilledGridAsCtrlThrowCraftLimit() {
        boolean[] honeyBottleSlots = {
                true, true, false,
                true, true, false,
                false, false, false
        };

        assertThat(QuickCraftMouseCraftAckRules.maximumCraftsFromGridCounts(
                new int[]{1, 1, 0, 1, 1, 0, 0, 0, 0}, honeyBottleSlots)).isEqualTo(1);
        assertThat(QuickCraftMouseCraftAckRules.maximumCraftsFromGridCounts(
                new int[]{16, 16, 0, 16, 16, 0, 0, 0, 0}, honeyBottleSlots)).isEqualTo(16);
    }

    @Test
    void materialLedgerUsesActualPreClickCountInsteadOfStaleAuthoritativeCount() {
        assertThat(QuickCraftMouseCraftAckRules.materialConsumptionExceedsBatchLimit(
                273, 231, 1, 26)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.materialConsumptionExceedsBatchLimit(
                257, 231, 1, 26)).isFalse();
    }

    @Test
    void materialLedgerStillRejectsRealOverconsumptionAndAllowsPickup() {
        assertThat(QuickCraftMouseCraftAckRules.materialConsumptionExceedsBatchLimit(
                258, 231, 1, 26)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.materialConsumptionExceedsBatchLimit(
                231, 273, 1, 26)).isFalse();
    }

    @Test
    void completedOutputBatchAcceptsOnlyRemainderInUsedRecipeSlot() {
        assertThat(QuickCraftMouseCraftAckRules.isAllowedCraftRemainder(
                true, true, false)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.isAllowedCraftRemainder(
                false, true, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.isAllowedCraftRemainder(
                true, false, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.isAllowedCraftRemainder(
                true, true, true)).isFalse();
    }

    @Test
    void authoritativeFullStateCanFinishBeforeDeferredProbeIsSent() {
        assertThat(QuickCraftMouseCraftAckRules.canFinishBatch(
                true, false, false, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.canFinishBatch(
                false, false, false, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canFinishBatch(
                true, false, false, false)).isFalse();
    }

    @Test
    void sentStatisticsProbeCannotBeBypassedByLaterFullUpdate() {
        assertThat(QuickCraftMouseCraftAckRules.canFinishBatch(
                true, true, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canFinishBatch(
                false, true, false, true)).isTrue();
    }

    @Test
    void deferredStatisticsProbeIsSentOnlyWhileBatchStillWaits() {
        assertThat(QuickCraftMouseCraftAckRules.shouldSendDeferredStatsProbe(
                true, true, false)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.shouldSendDeferredStatsProbe(
                false, true, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.shouldSendDeferredStatsProbe(
                true, false, false)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.shouldSendDeferredStatsProbe(
                true, true, true)).isFalse();
    }

    @Test
    void authoritativeFullAckRequiresStrictFinalOutputDrainState() {
        assertThat(QuickCraftMouseCraftAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftMouseCraftAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, true, true, true, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftMouseCraftAckExecutor.BatchPath.MANUAL_COMBINED,
                true, true, true, true, true, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftMouseCraftAckExecutor.BatchPath.MANUAL_REFILL,
                true, true, true, true, true, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftMouseCraftAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, false, true, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftMouseCraftAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, true, false, true, true)).isFalse();
    }

    @Test
    void statisticsOrderedAckMakesCurrentHandlerStateAuthoritative() {
        assertThat(QuickCraftMouseCraftAckRules.canConfirmManualBatch(
                true, true, true, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.canConfirmManualBatch(
                false, true, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.canConfirmCombinedBatch(
                true, true, true, true, true)).isTrue();
    }

    @Test
    void canceledAwaitingBatchKeepsLatePacketDrainIsolation() {
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(true, true, true)).isTrue();
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(false, true, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(true, false, true)).isFalse();
        assertThat(QuickCraftMouseCraftAckRules.needsCanceledBatchDrain(true, true, false)).isFalse();
    }

    @Test
    void canceledCtrlThrowBatchReleasesOnlyAfterAuthoritativeEmptyOutput() {
        QuickCraftMouseCraftAckExecutor.OutputAckSequence sequence =
                new QuickCraftMouseCraftAckExecutor.OutputAckSequence();
        sequence.reset(5);

        assertThat(QuickCraftMouseCraftAckExecutor.canReleaseCanceledDrain(sequence, false, 5)).isFalse();
        sequence.observe(6, true, false, true);
        assertThat(QuickCraftMouseCraftAckExecutor.canReleaseCanceledDrain(sequence, false, 6)).isTrue();
    }
}
