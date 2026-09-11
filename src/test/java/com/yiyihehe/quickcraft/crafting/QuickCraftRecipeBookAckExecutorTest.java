package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftRecipeBookAckExecutorTest {
    @Test
    void outputBatchPlansExactlyOneCtrlThrow() {
        assertThat(QuickCraftRecipeBookAckExecutor.plannedOutputThrows(true, true)).isEqualTo(1);
        assertThat(QuickCraftRecipeBookAckExecutor.plannedOutputThrows(true, false)).isZero();
        assertThat(QuickCraftRecipeBookAckExecutor.plannedOutputThrows(false, true)).isZero();
    }

    @Test
    void outputSprayModeLatchesAfterInitialFillPhase() {
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                false, 2, 2, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.remainingOutputFillSlots(2, 1)).isEqualTo(1);
        assertThat(QuickCraftRecipeBookAckExecutor.remainingOutputFillSlots(1, 1)).isZero();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                false, 0, 3, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                true, 0, 9, true)).isTrue();
    }

    @Test
    void fullInventoryStartsOutputSprayEvenWhenOutputCouldStack() {
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                false, 0, 0, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                false, 2, 0, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldEnterOutputSprayMode(
                false, 2, 2, false)).isTrue();
    }

    @Test
    void intermediateRecipeOutputStillStopsAsMissingLockedRecipeIngredients() {
        assertThat(QuickCraftRecipeBookAckExecutor.isMissingLockedRecipeTerminal(
                false, false, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.isMissingLockedRecipeTerminal(
                false, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.isMissingLockedRecipeTerminal(
                true, false, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.isMissingLockedRecipeTerminal(
                false, false, false)).isFalse();
    }

    @Test
    void tailFillUsesEvenShareWithoutOverfillingRecipeSlots() {
        assertThat(QuickCraftRecipeBookInventory.tailItemsPerSlot(64, 3, 64)).isEqualTo(21);
        assertThat(QuickCraftRecipeBookInventory.tailItemsPerSlot(64, 3, 1)).isEqualTo(1);
        assertThat(QuickCraftRecipeBookInventory.tailItemsPerSlot(2, 3, 64)).isZero();
    }

    @Test
    void completePatternQuickMoveCannotMakeRepeatedIngredientsUneven() {
        assertThat(QuickCraftRecipeBookInventory.canQuickTopUpCompleteGroup(1, 10, 32)).isTrue();
        assertThat(QuickCraftRecipeBookInventory.canQuickTopUpCompleteGroup(3, 96, 96)).isTrue();
        assertThat(QuickCraftRecipeBookInventory.canQuickTopUpCompleteGroup(3, 64, 96)).isFalse();
    }

    @Test
    void fullSourceStackCannotQuickMoveIntoPartiallyFilledGridSlot() {
        assertThat(QuickCraftRecipeBookInventory.canQuickMoveWholeStackToGridSlot(
                64, 1, 64, 1, 1)).isFalse();
        assertThat(QuickCraftRecipeBookInventory.canQuickMoveWholeStackToGridSlot(
                63, 1, 64, 1, 1)).isTrue();
    }

    @Test
    void repeatedIngredientRemainderIsRebalancedAfterOneSlotRunsOut() {
        var moves = QuickCraftRecipeBookInventory.planGridTailBalance(new int[]{32, 32, 0});
        assertThat(moves).containsExactly(
                new QuickCraftRecipeBookInventory.GridBalanceMove(0, 2, 10),
                new QuickCraftRecipeBookInventory.GridBalanceMove(1, 2, 11));
    }

    @Test
    void materialLedgerUsesRefilledGridAsCtrlThrowCraftLimit() {
        boolean[] honeyBottleSlots = {
                true, true, false,
                true, true, false,
                false, false, false
        };

        assertThat(QuickCraftRecipeBookAckExecutor.maximumCraftsFromGridCounts(
                new int[]{1, 1, 0, 1, 1, 0, 0, 0, 0}, honeyBottleSlots)).isEqualTo(1);
        assertThat(QuickCraftRecipeBookAckExecutor.maximumCraftsFromGridCounts(
                new int[]{16, 16, 0, 16, 16, 0, 0, 0, 0}, honeyBottleSlots)).isEqualTo(16);
    }

    @Test
    void materialLedgerUsesActualPreClickCountInsteadOfStaleAuthoritativeCount() {
        assertThat(QuickCraftRecipeBookAckExecutor.materialConsumptionExceedsBatchLimit(
                273, 231, 1, 26)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.materialConsumptionExceedsBatchLimit(
                257, 231, 1, 26)).isFalse();
    }

    @Test
    void materialLedgerStillRejectsRealOverconsumptionAndAllowsPickup() {
        assertThat(QuickCraftRecipeBookAckExecutor.materialConsumptionExceedsBatchLimit(
                258, 231, 1, 26)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.materialConsumptionExceedsBatchLimit(
                231, 273, 1, 26)).isFalse();
    }

    @Test
    void tailCursorRemainderWaitsForAuthoritativeBarrierBeforeParking() {
        assertThat(QuickCraftRecipeBookAckExecutor.shouldDeferCursorParking(true, false)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldDeferCursorParking(true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldDeferCursorParking(false, false)).isFalse();
    }

    @Test
    void completedOutputBatchAcceptsOnlyRemainderInUsedRecipeSlot() {
        assertThat(QuickCraftRecipeBookAckExecutor.isAllowedCraftRemainder(
                true, true, false)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.isAllowedCraftRemainder(
                false, true, false)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.isAllowedCraftRemainder(
                true, false, false)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.isAllowedCraftRemainder(
                true, true, true)).isFalse();
    }

    @Test
    void authoritativeFullStateCanFinishBeforeDeferredProbeIsSent() {
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(
                true, false, false, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(
                false, false, false, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(
                true, false, false, false)).isFalse();
    }

    @Test
    void sentStatisticsProbeCannotBeBypassedByLaterFullUpdate() {
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(
                true, true, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(
                false, true, false, true)).isTrue();
    }

    @Test
    void deferredStatisticsProbeIsSentOnlyWhileBatchStillWaits() {
        assertThat(QuickCraftRecipeBookAckExecutor.shouldSendDeferredStatsProbe(
                true, true, false)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldSendDeferredStatsProbe(
                false, true, false)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldSendDeferredStatsProbe(
                true, false, false)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldSendDeferredStatsProbe(
                true, true, true)).isFalse();
    }

    @Test
    void authoritativeFullAckRequiresStrictFinalOutputDrainState() {
        assertThat(QuickCraftRecipeBookAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftRecipeBookAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, true, true, true, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftRecipeBookAckExecutor.BatchPath.MANUAL_COMBINED,
                true, true, true, true, true, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftRecipeBookAckExecutor.BatchPath.MANUAL_REFILL,
                true, true, true, true, true, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftRecipeBookAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, false, true, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canUseAuthoritativeFullAck(
                QuickCraftRecipeBookAckExecutor.BatchPath.OUTPUT_DRAIN,
                true, true, true, true, false, true, true)).isFalse();
    }

    @Test
    void statisticsOrderedAckMakesCurrentHandlerStateAuthoritative() {
        assertThat(QuickCraftRecipeBookAckExecutor.canConfirmManualBatch(
                true, true, true, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.canConfirmManualBatch(
                false, true, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canConfirmCombinedBatch(
                true, true, true, true, true)).isTrue();
    }

    @Test
    void canceledAwaitingBatchKeepsLatePacketDrainIsolation() {
        assertThat(QuickCraftRecipeBookAckExecutor.needsCanceledBatchDrain(true, true, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.needsCanceledBatchDrain(false, true, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.needsCanceledBatchDrain(true, false, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.needsCanceledBatchDrain(true, true, false)).isFalse();
    }

    @Test
    void canceledCtrlThrowBatchReleasesOnlyAfterAuthoritativeEmptyOutput() {
        QuickCraftRecipeBookAckExecutor.OutputAckSequence sequence =
                new QuickCraftRecipeBookAckExecutor.OutputAckSequence();
        sequence.reset(5);

        assertThat(QuickCraftRecipeBookAckExecutor.canReleaseCanceledDrain(sequence, false, 5)).isFalse();
        sequence.observe(6, true, false, true);
        assertThat(QuickCraftRecipeBookAckExecutor.canReleaseCanceledDrain(sequence, false, 6)).isTrue();
    }
}
