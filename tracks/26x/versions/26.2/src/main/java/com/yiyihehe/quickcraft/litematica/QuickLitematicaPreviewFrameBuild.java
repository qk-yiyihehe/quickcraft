package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.LongSupplier;

/** 独立预览帧按原生组顺序续跑；finishPrepare 和上传仍排在全部几何之后。 */
public final class QuickLitematicaPreviewFrameBuild implements AutoCloseable {
    private static final ThreadLocal<QuickLitematicaPreviewFrameBuild> CURRENT = new ThreadLocal<>();
    private final StagedVertexBuffer buffer;
    private final LongSupplier clock;
    private final ArrayDeque<Work> work = new ArrayDeque<>();
    private boolean collecting = true;
    @Nullable private GroupWork active;
    private long steps, total;

    QuickLitematicaPreviewFrameBuild(StagedVertexBuffer buffer) { this(buffer, System::nanoTime); }
    QuickLitematicaPreviewFrameBuild(StagedVertexBuffer buffer, LongSupplier clock) {
        this.buffer = buffer;
        this.clock = clock;
    }

    @Nullable public static QuickLitematicaPreviewFrameBuild current() { return CURRENT.get(); }

    void capture(Runnable action) {
        var previous = CURRENT.get();
        CURRENT.set(this);
        try { action.run(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    void seal() { this.collecting = false; }
    float progress() { return Math.min(0.99F, this.total == 0 ? 0.0F : this.steps / (float) this.total); }

    boolean advance(long budget) {
        long start = this.clock.getAsLong();
        capture(() -> {
            while (!this.work.isEmpty()) {
                Work next = this.work.getFirst();
                if (next.advance()) this.work.removeFirst();
                this.steps++;
                if (this.clock.getAsLong() - start >= budget) break;
            }
        });
        return this.work.isEmpty();
    }

    public <S extends SubmitNode> boolean deferGroup(FeatureRenderer<S> renderer, FeatureFrameContext context,
                                                    List<S> submits, boolean strict) {
        if (!this.collecting || context.stagedVertexBuffer() != this.buffer) return false;
        this.work.addLast(new GroupWork(renderer, context, submits, strict));
        this.total += renderer instanceof ItemFeatureRenderer ? 1L + submits.size() * (long) QuickLitematicaPreviewItemBuildAccess.passes()
                : renderer instanceof ModelFeatureRenderer ? 1L + submits.size() : 2L;
        return true;
    }

    public boolean deferFinish(FeatureRenderer<?> renderer, FeatureFrameContext context) {
        if (!this.collecting || context.stagedVertexBuffer() != this.buffer) return false;
        this.work.addLast(() -> { renderer.finishPrepare(context); return true; });
        this.total++;
        return true;
    }

    public boolean deferUpload(StagedVertexBuffer buffer) {
        if (!this.collecting || buffer != this.buffer) return false;
        this.work.addLast(() -> {
            try (var phase = QuickLitematicaPreviewLog.phase("动态完整帧原生最终上传")) { buffer.upload(); }
            return true;
        });
        this.total++;
        return true;
    }

    public void createdGroup(StagedVertexBuffer buffer, GeometryGroup group) {
        if (buffer == this.buffer && this.active != null && this.active.group == null) this.active.group = group;
    }

    public boolean deferBuild(GeometryRenderer renderer, FeatureFrameContext context) {
        if (this.active == null || this.active.renderer != renderer || context.stagedVertexBuffer() != this.buffer) return false;
        this.active.builder = renderer;
        return true;
    }

    @Nullable public VertexConsumer vertexBuilder(GeometryRenderer renderer, RenderType material) {
        var task = this.active;
        return task == null || task.builder != renderer || task.group == null ? null : task.group.getVertexBuilder(material);
    }

    @Override public void close() { this.work.clear(); this.active = null; }

    public interface GeometryRenderer {
        void quickcraft$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits);
    }

    public interface GeometryGroup {
        VertexConsumer getVertexBuilder(RenderType material);
        default void quickcraft$finish(StagedVertexBuffer buffer) {}
    }

    private interface Work { boolean advance(); }

    private final class GroupWork implements Work {
        private final FeatureRenderer<?> renderer;
        private final FeatureFrameContext context;
        private final List<? extends SubmitNode> submits;
        private final boolean strict;
        @Nullable private GeometryRenderer builder;
        @Nullable private GeometryGroup group;
        private boolean started;
        private int index, pass;

        <S extends SubmitNode> GroupWork(FeatureRenderer<S> renderer, FeatureFrameContext context, List<S> submits, boolean strict) {
            this.renderer = renderer;
            this.context = context;
            this.submits = submits;
            this.strict = strict;
        }

        @Override public boolean advance() {
            active = this;
            try {
                if (!this.started) {
                    this.started = true;
                    @SuppressWarnings("unchecked") var nativeRenderer = (FeatureRenderer<SubmitNode>) this.renderer;
                    @SuppressWarnings("unchecked") var records = (List<SubmitNode>) this.submits;
                    nativeRenderer.prepareGroup(this.context, records, this.strict);
                    if (this.builder == null) return true;
                    if (this.group == null) throw new IllegalStateException("Preview geometry group was not captured");
                    return this.submits.isEmpty();
                }
                if (this.renderer instanceof ItemFeatureRenderer item) {
                    // 26.2 先全量 main/outline，再全量 foil；26.3 由适配器保留单循环。
                    QuickLitematicaPreviewItemBuildAccess.prepare(item,
                            (ItemFeatureRenderer.Submit) this.submits.get(this.index++), this.pass);
                    if (this.index < this.submits.size()) return false;
                    if (++this.pass == QuickLitematicaPreviewItemBuildAccess.passes()) return this.finish();
                    this.index = 0;
                    return false;
                }
                if (this.renderer instanceof ModelFeatureRenderer) {
                    int start = this.index++;
                    this.builder.quickcraft$buildGroup(this.context, this.submits.subList(start, this.index));
                    return this.index == this.submits.size() && this.finish();
                }
                this.builder.quickcraft$buildGroup(this.context, this.submits);
                return this.finish();
            } finally { active = null; }
        }

        private boolean finish() {
            this.group.quickcraft$finish(buffer);
            return true;
        }
    }
}
