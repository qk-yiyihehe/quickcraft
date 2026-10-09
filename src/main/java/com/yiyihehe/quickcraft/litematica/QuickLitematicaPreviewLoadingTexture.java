package com.yiyihehe.quickcraft.litematica;

final class QuickLitematicaPreviewLoadingTexture {
    private static final long REDRAW_NANOS = 250_000_000L;
    private View renderedView;
    private long reuseStartedAt;
    private boolean reusing;

    boolean canReuse(View view, boolean uploading, long now) {
        if (!uploading || !view.equals(this.renderedView)) return false;
        // GPU 重画可能阻塞；从下一帧开始计时，避免耗尽间隔后立即再次重画。
        if (!this.reusing) {
            this.reusing = true;
            this.reuseStartedAt = now;
        }
        return now - this.reuseStartedAt < REDRAW_NANOS;
    }

    void rendered(View view) {
        this.renderedView = view;
        this.reusing = false;
    }

    void clear() {
        this.renderedView = null;
        this.reusing = false;
    }

    record View(QuickLitematicaPreview3D.Preview preview, int x, int y, int size,
                float dragX, float dragY, double angle, float pitch, float scale) {}
}
