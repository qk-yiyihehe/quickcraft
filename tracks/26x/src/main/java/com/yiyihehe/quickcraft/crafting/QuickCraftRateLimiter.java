package com.yiyihehe.quickcraft.crafting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class QuickCraftRateLimiter {
    private boolean scheduled;
    private boolean wakePending;
    private long nextDispatchNanos;

    boolean isReady(int perSecond, long nowNanos) {
        return perSecond == 0 || (perSecond > 0 && (!scheduled || nowNanos - nextDispatchNanos >= 0L));
    }

    void recordDispatch(int perSecond, long nowNanos) {
        if (perSecond <= 0) {
            return;
        }
        long interval = (1_000_000_000L + perSecond - 1L) / perSecond;
        // 小幅调度抖动沿用原节拍；错过整轮后重新计时，避免停顿后集中补发。
        nextDispatchNanos = scheduled && nowNanos - nextDispatchNanos < interval
                ? nextDispatchNanos + interval
                : nowNanos + interval;
        scheduled = true;
    }

    void resumeWhenReady(int perSecond, Executor clientThread, Runnable resume) {
        if (perSecond <= 0 || !scheduled || wakePending) {
            return;
        }
        wakePending = true;
        long delay = Math.max(0L, nextDispatchNanos - System.nanoTime());
        // 计时器只向客户端主线程排队；回调须重新检查会话，不能在后台操作容器。
        CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS, clientThread).execute(() -> {
            wakePending = false;
            resume.run();
        });
    }
}
