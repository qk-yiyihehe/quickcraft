package com.yiyihehe.quickcraft.litematica;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** GPU 读回提交成功后由回调释放；提交失败由调用方释放，同一组资源只关闭一次。 */
final class QuickLitematicaPreviewReadback implements AutoCloseable {
    private final Runnable closeTarget;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable closeBuffer;

    QuickLitematicaPreviewReadback(Runnable closeTarget) {
        this.closeTarget = closeTarget;
    }

    void buffer(Runnable closeBuffer) {
        this.closeBuffer = closeBuffer;
    }

    void failed(Throwable failure, Consumer<Throwable> callback) {
        try {
            this.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
        callback.accept(failure);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            try {
                if (this.closeBuffer != null) this.closeBuffer.run();
            } finally {
                this.closeTarget.run();
            }
        }
    }
}
