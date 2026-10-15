package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftRateLimiterTest {
    @Test
    void configuredRateLimitsDispatchesOverASecond() {
        for (int rate : new int[]{1, 20, 40, 50, 100}) {
            QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
            int dispatched = 0;
            for (long now = 0L; now < 1_000_000_000L; now += 1_000_000L) {
                if (limiter.isReady(rate, now)) {
                    limiter.recordDispatch(rate, now);
                    dispatched++;
                }
            }
            assertThat(dispatched).isEqualTo(rate);
        }
    }

    @Test
    void twentyPerSecondSpacesBatchesAcrossTicks() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        assertThat(limiter.isReady(20, 0L)).isTrue();
        limiter.recordDispatch(20, 0L);
        assertThat(limiter.isReady(20, 49_999_999L)).isFalse();
        assertThat(limiter.isReady(20, 50_000_000L)).isTrue();
    }

    @Test
    void frameJitterKeepsTheOriginalCadenceWithoutWaitingAnotherTick() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        limiter.recordDispatch(20, 0L);
        assertThat(limiter.isReady(20, 55_000_000L)).isTrue();
        limiter.recordDispatch(20, 55_000_000L);
        assertThat(limiter.isReady(20, 55_000_000L)).isFalse();
        assertThat(limiter.isReady(20, 99_999_999L)).isFalse();
        assertThat(limiter.isReady(20, 100_000_000L)).isTrue();
    }

    @Test
    void fiftyPerSecondIncludesLocalDispatchAndAckTimeInTheInterval() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        limiter.recordDispatch(50, 0L);
        // 7ms 本地发送、11ms ACK 后，仅剩 2ms 限速等待。
        assertThat(limiter.isReady(50, 18_000_000L)).isFalse();
        assertThat(limiter.isReady(50, 20_000_000L)).isTrue();
    }

    @Test
    void slowerServerAcknowledgementsRemainTheEffectiveLimit() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        limiter.recordDispatch(40, 0L);
        for (long now = 100_000_000L; now <= 1_000_000_000L; now += 100_000_000L) {
            assertThat(limiter.isReady(40, now)).isTrue();
            limiter.recordDispatch(40, now);
        }
    }

    @Test
    void longWaitCannotProduceTwoConsecutiveCatchUpBatches() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        limiter.recordDispatch(20, 0L);
        long now = 10_000_000_000L;
        assertThat(limiter.isReady(20, now)).isTrue();
        limiter.recordDispatch(20, now);
        assertThat(limiter.isReady(20, now)).isFalse();
        assertThat(limiter.isReady(20, now + 49_999_999L)).isFalse();
        assertThat(limiter.isReady(20, now + 50_000_000L)).isTrue();
    }

    @Test
    void zeroDisablesTheRateLimitEvenWithAnExistingDeadline() {
        QuickCraftRateLimiter limiter = new QuickCraftRateLimiter();
        limiter.recordDispatch(20, 0L);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.isReady(0, 1L)).isTrue();
            limiter.recordDispatch(0, 1L);
        }
    }
}
