package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewQueuedBuild;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.BlockFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.ParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FeatureRenderDispatcher.class)
public abstract class LitematicaPreviewQueuedDispatcherMixin implements QuickLitematicaPreviewQueuedBuild.DispatcherBridge {
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;
    @Shadow @Final private ModelManager modelManager;
    @Shadow @Final private MultiBufferSource.BufferSource bufferSource;
    @Shadow @Final private AtlasManager atlasManager;
    @Shadow @Final private OutlineBufferSource outlineBufferSource;
    @Shadow @Final private MultiBufferSource.BufferSource crumblingBufferSource;
    @Shadow @Final private Font font;
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final private ShadowFeatureRenderer shadowFeatureRenderer;
    @Shadow @Final private FlameFeatureRenderer flameFeatureRenderer;
    @Shadow @Final private ModelFeatureRenderer modelFeatureRenderer;
    @Shadow @Final private ModelPartFeatureRenderer modelPartFeatureRenderer;
    @Shadow @Final private NameTagFeatureRenderer nameTagFeatureRenderer;
    @Shadow @Final private TextFeatureRenderer textFeatureRenderer;
    @Shadow @Final private LeashFeatureRenderer leashFeatureRenderer;
    @Shadow @Final private ItemFeatureRenderer itemFeatureRenderer;
    @Shadow @Final private CustomFeatureRenderer customFeatureRenderer;
    @Shadow @Final private BlockFeatureRenderer blockFeatureRenderer;
    @Shadow @Final private ParticleFeatureRenderer particleFeatureRenderer;

    @Override
    public void quickcraft$queuePreviewPhases(Consumer<Runnable> submit) {
        for (SubmitNodeCollection collection : this.submitNodeStorage.getSubmitsPerOrder().values()) {
            submit.accept(() -> this.modelFeatureRenderer.renderSolid(collection, this.bufferSource, this.outlineBufferSource, this.crumblingBufferSource));
            submit.accept(() -> this.modelPartFeatureRenderer.renderSolid(collection, this.bufferSource, this.outlineBufferSource, this.crumblingBufferSource));
            submit.accept(() -> this.flameFeatureRenderer.renderSolid(collection, this.bufferSource, this.atlasManager));
            submit.accept(() -> this.leashFeatureRenderer.renderSolid(collection, this.bufferSource));
            submit.accept(() -> this.itemFeatureRenderer.renderSolid(collection, this.bufferSource, this.outlineBufferSource));
            submit.accept(() -> this.blockFeatureRenderer
                .renderSolid(collection, this.bufferSource, this.modelManager.getBlockStateModelSet(), this.outlineBufferSource, this.gameRenderState.optionsRenderState));
            submit.accept(() -> this.customFeatureRenderer.renderSolid(collection, this.bufferSource));
            submit.accept(() -> this.particleFeatureRenderer.renderSolid(collection));
        }


        for (SubmitNodeCollection collection : this.submitNodeStorage.getSubmitsPerOrder().values()) {
            submit.accept(() -> this.shadowFeatureRenderer.renderTranslucent(collection, this.bufferSource));
            submit.accept(() -> this.modelFeatureRenderer.renderTranslucent(collection, this.bufferSource, this.outlineBufferSource, this.crumblingBufferSource));
            submit.accept(() -> this.modelPartFeatureRenderer.renderTranslucent(collection, this.bufferSource, this.outlineBufferSource, this.crumblingBufferSource));
            submit.accept(() -> this.nameTagFeatureRenderer.renderTranslucent(collection, this.bufferSource, this.font));
            submit.accept(() -> this.textFeatureRenderer.renderTranslucent(collection, this.bufferSource));
            submit.accept(() -> this.itemFeatureRenderer.renderTranslucent(collection, this.bufferSource, this.outlineBufferSource));
            submit.accept(() -> this.blockFeatureRenderer
                .renderTranslucent(
                    collection,
                    this.bufferSource,
                    this.modelManager.getBlockStateModelSet(),
                    this.outlineBufferSource,
                    this.crumblingBufferSource,
                    this.gameRenderState.optionsRenderState
                ));
            submit.accept(() -> this.customFeatureRenderer.renderTranslucent(collection, this.bufferSource));
        }


        for (SubmitNodeCollection collection : this.submitNodeStorage.getSubmitsPerOrder().values()) {
            submit.accept(() -> this.particleFeatureRenderer.renderTranslucent(collection));
        }


        submit.accept(() -> this.submitNodeStorage.clear());
    }
}
