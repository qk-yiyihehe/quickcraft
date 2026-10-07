package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewBatchCompatibilityTest {
    @Test
    void preservesOpaqueModelSubmissionsAndRejectsDifferentMaterialsAcrossBatches() {
        var compatibility = new QuickLitematicaPreviewDynamicBackend.BatchCompatibility();
        var setup = RenderSetup.builder(RenderPipelines.ENTITY_SOLID).createRenderSetup();
        var material = RenderType.create("quickcraft_batch_test", setup);
        var first = new ModelFeatureRenderer.Submit<>(material, new PoseStack().last(), null, 1, 0, 0, -1, null, null);
        var second = new ModelFeatureRenderer.Submit<>(material, new PoseStack().last(), null, 2, 0, 0, -1, null, null);
        var batch = new SubmitNodeStorage();
        batch.order(0).solid.submit(first);
        batch.order(0).solid.submit(second);
        assertThat(compatibility.accepts(batch)).isTrue();
        var restored = new java.util.ArrayList<net.minecraft.client.renderer.feature.submit.SubmitNode>();
        batch.order(0).solid.sortInto((submit, strictlyOrdered) -> restored.add(submit));
        assertThat(restored).containsExactly(first, second);
        var otherMaterial = RenderType.create("quickcraft_other_material", setup);
        var next = new SubmitNodeStorage();
        next.order(0).solid.submit(new ModelFeatureRenderer.Submit<>(otherMaterial, new PoseStack().last(), null, 3, 0, 0, -1, null, null));
        assertThat(compatibility.accepts(next)).isFalse();
        assertThat(next.order(0).solid.isEmpty()).isFalse();
    }

    @Test
    void retainsWholeFrameForTranslucentOrOrderedSubmissions() {
        var compatibility = new QuickLitematicaPreviewDynamicBackend.BatchCompatibility();
        assertThat(compatibility.accepts(new SubmitNodeStorage())).isTrue();
        // 仅检查提交阶段，不执行模型或创建 GPU 资源。
        var model = new ModelFeatureRenderer.Submit<>(null, new PoseStack().last(), null, 0, 0, 0, -1, null, null);
        var translucent = new SubmitNodeStorage();
        translucent.order(0).translucentModels.submit(model);
        assertThat(compatibility.accepts(translucent)).isFalse();
        var ordered = new SubmitNodeStorage();
        ordered.order(1).solid.submit(model);
        assertThat(compatibility.accepts(ordered)).isFalse();
        assertThat(ordered.order(1).solid.isEmpty()).isFalse();
        var restored = new java.util.ArrayList<net.minecraft.client.renderer.feature.submit.SubmitNode>();
        ordered.order(1).solid.sortInto((submit, strictlyOrdered) -> restored.add(submit));
        assertThat(restored).containsExactly(model);
    }
}
