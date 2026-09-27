package com.yiyihehe.quickcraft.crafting;

/**
 * 普通鼠标合成 ACK 的无状态判定。
 * 这些规则不读取界面、会话或网络状态，便于单独测试并避免执行器继续膨胀。
 */
final class QuickCraftMouseCraftAckRules {
    private static final long MIN_ACK_TIMEOUT_MILLIS = 3_000L;
    static final long MAX_ACK_TIMEOUT_MILLIS = 15_000L;

    private QuickCraftMouseCraftAckRules() {
    }

    static boolean isMissingLockedRecipeTerminal(boolean patternComplete,
                                                  boolean ingredientsAvailable,
                                                  boolean intermediateOutput) {
        return !patternComplete && !ingredientsAvailable && intermediateOutput;
    }

    static boolean isAllowedCraftRemainder(boolean outputActionSent,
                                           boolean occupiedPatternSlot,
                                           boolean matchesPatternIngredient) {
        return outputActionSent && occupiedPatternSlot && !matchesPatternIngredient;
    }

    static boolean materialConsumptionExceedsBatchLimit(int before,
                                                        int after,
                                                        int occurrences,
                                                        int maximumCrafts) {
        return (long) before - after > (long) occurrences * maximumCrafts;
    }

    static int maximumCraftsFromGridCounts(int[] gridCounts, boolean[] requiredSlots) {
        if (gridCounts == null || requiredSlots == null
                || gridCounts.length < requiredSlots.length) {
            return 0;
        }
        int maximum = Integer.MAX_VALUE;
        boolean hasIngredient = false;
        for (int i = 0; i < requiredSlots.length; i++) {
            if (!requiredSlots[i]) {
                continue;
            }
            hasIngredient = true;
            maximum = Math.min(maximum, Math.max(0, gridCounts[i]));
        }
        return hasIngredient && maximum != Integer.MAX_VALUE ? maximum : 0;
    }

    static boolean canFinishBatch(boolean authoritativeFullState,
                                  boolean statsProbeSent,
                                  boolean statsProbePending,
                                  boolean pathConfirmable) {
        if (!pathConfirmable) {
            return false;
        }
        // 探针一旦发出就必须等它返回，防止迟到统计包被下一批误认。
        return statsProbeSent ? !statsProbePending : authoritativeFullState;
    }

    static boolean shouldSendDeferredStatsProbe(boolean awaiting,
                                                boolean statsProbeDeferred,
                                                boolean statsProbePending) {
        return awaiting && statsProbeDeferred && !statsProbePending;
    }

    static boolean shouldResumePickupWait(boolean ingredientsAvailable,
                                          int remainingTicks) {
        return ingredientsAvailable || remainingTicks <= 0;
    }

    /** Revision 回卷感知的比较：candidate 是否在 base 之后的半圈窗口内（revision 限制在 0..32767）。 */
    static boolean isRevisionAfter(int candidate, int base) {
        int distance = candidate - base & 32767;
        return distance > 0 && distance < 16384;
    }

    static boolean canConfirmBarrier(boolean statsProbeReceived,
                                     boolean recipeReadyFullObserved,
                                     boolean fullContainsExpectedOutput,
                                     boolean cursorEmpty,
                                     boolean recipeMatches) {
        return statsProbeReceived && recipeReadyFullObserved
                && fullContainsExpectedOutput && cursorEmpty && recipeMatches;
    }

    static boolean canConfirmManualBatch(boolean authoritativeStateReceived,
                                         boolean cursorEmpty,
                                         boolean patternReady,
                                         boolean expectedOutputPresent) {
        return authoritativeStateReceived && cursorEmpty && patternReady && expectedOutputPresent;
    }

    static boolean canConfirmOutputDrain(boolean authoritativeStateReceived,
                                         boolean cursorEmpty,
                                         boolean outputEmpty,
                                         boolean ingredientsDecreased,
                                         boolean expectedOutputPresent,
                                         boolean authoritativeExpectedOutput) {
        if (!authoritativeStateReceived || !cursorEmpty) {
            return false;
        }
        if (outputEmpty) {
            return authoritativeExpectedOutput;
        }
        return ingredientsDecreased && expectedOutputPresent && authoritativeExpectedOutput;
    }

    static boolean shouldContinueOutputDrain(boolean cursorEmpty,
                                             boolean outputEmpty,
                                             boolean expectedOutputPresent,
                                             boolean authoritativeExpectedOutput,
                                             boolean ingredientsDecreased,
                                             int drainRetries,
                                             int maxDrainRetries) {
        if (!cursorEmpty) {
            return false;
        }
        if (outputEmpty) {
            return authoritativeExpectedOutput;
        }
        if (!expectedOutputPresent || !authoritativeExpectedOutput) {
            return false;
        }
        if (ingredientsDecreased) {
            return true;
        }
        return drainRetries < Math.max(0, maxDrainRetries);
    }

    static boolean canContinueOutputDrainWithIntermediate(boolean cursorEmpty,
                                                          boolean expectedOutputAtDispatch,
                                                          boolean ingredientsDecreased,
                                                          boolean compatibleIntermediateOutput) {
        return cursorEmpty && expectedOutputAtDispatch
                && ingredientsDecreased && compatibleIntermediateOutput;
    }

    static boolean ingredientsDecreased(int[] before, int[] after) {
        if (before == null || after == null || before.length == 0 || before.length != after.length) {
            return false;
        }
        boolean decreased = false;
        for (int i = 0; i < before.length; i++) {
            if (after[i] > before[i]) {
                return false;
            }
            if (after[i] < before[i]) {
                decreased = true;
            }
        }
        return decreased;
    }

    static boolean canConfirmCombinedBatch(boolean authoritativeStateReceived,
                                           boolean cursorEmpty,
                                           boolean terminalOutputCompatible,
                                           boolean authoritativeExpectedOutput,
                                           boolean patternCompatible) {
        return authoritativeStateReceived && cursorEmpty && terminalOutputCompatible
                && authoritativeExpectedOutput && patternCompatible;
    }

    static int plannedOutputThrows(boolean expectedOutput, boolean patternComplete) {
        return expectedOutput && patternComplete ? 1 : 0;
    }

    static int remainingOutputFillSlots(int remaining, int newlyOccupiedResultSlots) {
        return Math.max(0, remaining - Math.max(0, newlyOccupiedResultSlots));
    }

    static boolean shouldEnterOutputSprayMode(boolean alreadySpraying,
                                              int remainingInitialFillSlots,
                                              int currentEmptySlots,
                                              boolean canAcceptOutput) {
        return alreadySpraying
                || remainingInitialFillSlots <= 0
                || currentEmptySlots <= 0
                || !canAcceptOutput;
    }

    static long ackTimeoutMillis(long observedMaxAckNanos) {
        long adaptive = Math.max(
                MIN_ACK_TIMEOUT_MILLIS,
                nanosToMillis(observedMaxAckNanos) * 8L
        );
        return Math.min(MAX_ACK_TIMEOUT_MILLIS, adaptive);
    }

    static boolean canDispatchWithinTick(int dispatched, int configuredLimit) {
        return dispatched < Math.max(1, configuredLimit);
    }

    static boolean canDropTail(boolean awaiting, boolean cursorEmpty) {
        return !awaiting && cursorEmpty;
    }

    static boolean needsCanceledBatchDrain(boolean awaiting,
                                           boolean cursorEmpty,
                                           boolean statsProbePending) {
        return awaiting && cursorEmpty && statsProbePending;
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }
}
