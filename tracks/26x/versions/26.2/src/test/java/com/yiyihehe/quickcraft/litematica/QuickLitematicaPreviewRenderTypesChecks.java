package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

abstract class QuickLitematicaPreviewRenderTypesChecks {
    abstract PreparedRenderType prepared(ScissorState scissor, List<PreparedRenderType.Texture> textures);

    PreparedRenderType prepared(ScissorState scissor) {
        return prepared(scissor, List.of());
    }

    @Test
    void keepsDifferentMaterialsAndTextureBindingsSeparate() {
        var setup = RenderSetup.builder(RenderPipelines.ENTITY_SOLID).createRenderSetup();
        var material = RenderType.create("quickcraft_first", setup);
        var otherMaterial = RenderType.create("quickcraft_second", setup);
        var first = prepared(new ScissorState());
        var other = prepared(new ScissorState());
        var texture = prepared(new ScissorState(), List.of(new PreparedRenderType.Texture("Sampler0", null, null)));
        try (var scope = QuickLitematicaPreviewRenderTypes.beginCapture()) {
            scope.canonicalize(material, first, new Matrix4f());
            assertThat(scope.canonicalize(otherMaterial, other, new Matrix4f())).isSameAs(other);
            assertThat(scope.canonicalize(material, texture, new Matrix4f())).isSameAs(texture);
            assertThat(scope.size()).isEqualTo(3);
            assertThat(scope.reused()).isZero();
        }
    }

    @Test
    void reusesNonconsecutiveMaterialStatesWithEquivalentScissorValues() {
        var firstClip = new ScissorState();
        firstClip.enable(10, 20, 100, 200);
        var sameClip = new ScissorState(firstClip);
        var otherClip = new ScissorState();
        otherClip.enable(10, 21, 100, 200);
        var first = prepared(firstClip);
        var same = prepared(sameClip);
        var other = prepared(otherClip);
        try (var scope = QuickLitematicaPreviewRenderTypes.beginCapture()) {
            assertThat(scope.canonicalize(null, first, new Matrix4f())).isSameAs(first);
            assertThat(scope.canonicalize(null, other, new Matrix4f())).isSameAs(other);
            assertThat(scope.canonicalize(null, same, new Matrix4f())).isSameAs(first);
            assertThat(scope.size()).isEqualTo(2);
            assertThat(scope.reused()).isEqualTo(1);
        }
    }

    @Test
    void retainsDifferentModelTransformsAndDoesNotKeepMutableInputMatrix() {
        var first = prepared(new ScissorState());
        var other = prepared(new ScissorState());
        var input = new Matrix4f();
        try (var scope = QuickLitematicaPreviewRenderTypes.beginCapture()) {
            scope.canonicalize(null, first, input);
            input.translate(1.0F, 0.0F, 0.0F);
            assertThat(scope.canonicalize(null, other, input)).isSameAs(other);
            assertThat(scope.canonicalize(null, prepared(new ScissorState()), new Matrix4f())).isSameAs(first);
            assertThat(scope.size()).isEqualTo(2);
        }
    }

    @Test
    void ignoresStaleCoordinatesWhileScissorIsDisabledAndReleasesStates() {
        var disabled = new ScissorState();
        disabled.enable(1, 2, 3, 4);
        disabled.disable();
        var first = prepared(disabled);
        try (var scope = QuickLitematicaPreviewRenderTypes.beginCapture()) {
            scope.canonicalize(null, first, new Matrix4f());
            assertThat(scope.canonicalize(null, prepared(new ScissorState()), new Matrix4f())).isSameAs(first);
            scope.release();
            assertThat(scope.size()).isZero();
        }
    }
}
