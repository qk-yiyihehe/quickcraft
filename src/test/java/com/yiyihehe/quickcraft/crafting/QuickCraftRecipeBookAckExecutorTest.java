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
    void batchCannotFinishBeforeStatisticsProbeReturns() {
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(false, true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.canFinishBatch(true, true)).isTrue();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldCheckBatchTerminal(true)).isFalse();
        assertThat(QuickCraftRecipeBookAckExecutor.shouldCheckBatchTerminal(false)).isTrue();
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
