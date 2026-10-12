package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaPreviewItemFeatureInvoker;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class QuickLitematicaPreviewFrameBuildTest {
    @Test
    void resumesItemPassesInNativeOrderAndUploadsOnlyAfterFinish() {
        var buffer = mock(StagedVertexBuffer.class);
        var context = mock(FeatureFrameContext.class);
        when(context.stagedVertexBuffer()).thenReturn(buffer);
        var clock = new AtomicLong();
        var build = new QuickLitematicaPreviewFrameBuild(buffer, clock::getAndIncrement);
        var events = new ArrayList<String>();
        var a = mock(ItemFeatureRenderer.Submit.class);
        var b = mock(ItemFeatureRenderer.Submit.class);
        var item = spy(new ItemRenderer(build, buffer, events, List.of(a, b)));
        // 用显式夹具交接替代原生 final 方法上的 Mixin，避免真实注入绕过夹具。
        doAnswer(call -> { item.buildGroup(context, List.of(a, b)); return null; })
                .when(item).prepareGroup(context, List.of(a, b), true);
        doAnswer(call -> { events.add("upload"); return null; }).when(buffer).upload();
        build.capture(() -> {
            assertThat(build.deferGroup(item, context, List.of(a, b), true)).isTrue();
            assertThat(build.deferFinish(item, context)).isTrue();
            assertThat(build.deferUpload(buffer)).isTrue();
        });
        build.seal();
        assertThat(events).isEmpty();
        int slices = 0;
        while (!build.advance(1)) {
            assertThat(QuickLitematicaPreviewFrameBuild.current()).isNull();
            assertThat(events).doesNotContain("upload");
            assertThat(build.progress()).isLessThan(1.0F);
            assertThat(++slices).isLessThan(20);
        }
        var expected = new ArrayList<String>(List.of("prepare", "main0", "main1"));
        if (QuickLitematicaPreviewItemBuildAccess.passes() == 2) expected.addAll(List.of("foil0", "foil1"));
        expected.addAll(List.of("group-finish", "finish", "upload"));
        assertThat(events).containsExactlyElementsOf(expected);
        assertThat(QuickLitematicaPreviewFrameBuild.current()).isNull();
        build.close();
    }

    @Test
    void modelSlicesKeepTheOriginalGroupAndCancelDropsUnexecutedWork() {
        var buffer = mock(StagedVertexBuffer.class);
        var context = mock(FeatureFrameContext.class);
        when(context.stagedVertexBuffer()).thenReturn(buffer);
        var clock = new AtomicLong();
        var build = new QuickLitematicaPreviewFrameBuild(buffer, clock::getAndIncrement);
        var a = mock(ModelFeatureRenderer.Submit.class);
        var b = mock(ModelFeatureRenderer.Submit.class);
        var visited = new ArrayList<SubmitNode>();
        var model = new ModelRenderer(build, buffer, visited);
        build.capture(() -> build.deferGroup(model, context, List.of(a, b), false));
        build.seal();
        assertThat(build.advance(1)).isFalse();
        assertThat(visited).isEmpty();
        assertThat(build.advance(1)).isFalse();
        assertThat(visited).containsExactly(a);
        build.close();
        assertThat(build.advance(1)).isTrue();
        assertThat(visited).containsExactly(a);
        assertThat(QuickLitematicaPreviewFrameBuild.current()).isNull();
    }

    @Test
    void scopeRestoresAfterFailureAndIgnoresAnotherDispatchersBuffer() {
        var buffer = mock(StagedVertexBuffer.class);
        var context = mock(FeatureFrameContext.class);
        when(context.stagedVertexBuffer()).thenReturn(mock(StagedVertexBuffer.class));
        var build = new QuickLitematicaPreviewFrameBuild(buffer);
        build.capture(() -> assertThat(build.deferUpload(context.stagedVertexBuffer())).isFalse());
        try {
            build.capture(() -> { throw new IllegalStateException("failure"); });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("failure");
        }
        assertThat(QuickLitematicaPreviewFrameBuild.current()).isNull();
        assertThat(build.advance(1)).isTrue();
        build.close();
    }

    // 模拟原生 Mixin 的交接点，检查续跑顺序；注入是否生效另由游戏验证。
    private static final class ItemRenderer extends ItemFeatureRenderer
            implements QuickLitematicaPreviewFrameBuild.GeometryRenderer, LitematicaPreviewItemFeatureInvoker {
        private final QuickLitematicaPreviewFrameBuild build;
        private final StagedVertexBuffer buffer;
        private final List<String> events;
        private final List<Submit> records;

        ItemRenderer(QuickLitematicaPreviewFrameBuild build, StagedVertexBuffer buffer, List<String> events, List<Submit> records) {
            this.build = build;
            this.buffer = buffer;
            this.events = events;
            this.records = records;
        }
        @Override protected void buildGroup(FeatureFrameContext context, List<Submit> submits) {
            this.events.add("prepare");
            this.build.createdGroup(this.buffer, new QuickLitematicaPreviewFrameBuild.GeometryGroup() {
                @Override public com.mojang.blaze3d.vertex.VertexConsumer getVertexBuilder(net.minecraft.client.renderer.rendertype.RenderType material) { throw new AssertionError(); }
                @Override public void quickcraft$finish(StagedVertexBuffer buffer) { events.add("group-finish"); }
            });
            this.build.createdGroup(this.buffer, material -> { throw new AssertionError("Nested group replaced the active group"); });
            assertThat(this.build.deferBuild(this, context)).isTrue();
        }
        @Override public void quickcraft$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits) { throw new AssertionError(); }
        public void quickcraft$prepareSubmit(Submit submit, boolean foil) {
            this.events.add((foil ? "foil" : "main") + this.records.indexOf(submit));
        }
        public void quickcraft$prepareSubmit(Submit submit) { this.quickcraft$prepareSubmit(submit, false); }
        @Override public void finishPrepare(FeatureFrameContext context) { this.events.add("finish"); }
    }

    private static final class ModelRenderer extends ModelFeatureRenderer implements QuickLitematicaPreviewFrameBuild.GeometryRenderer {
        private final QuickLitematicaPreviewFrameBuild build;
        private final StagedVertexBuffer buffer;
        private final List<SubmitNode> visited;
        ModelRenderer(QuickLitematicaPreviewFrameBuild build, StagedVertexBuffer buffer, List<SubmitNode> visited) {
            this.build = build;
            this.buffer = buffer;
            this.visited = visited;
        }
        @Override protected void buildGroup(FeatureFrameContext context, List<Submit<?>> submits) {
            this.build.createdGroup(this.buffer, material -> { throw new AssertionError(); });
            assertThat(this.build.deferBuild(this, context)).isTrue();
        }
        @Override public void quickcraft$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits) { this.visited.addAll(submits); }
    }
}
