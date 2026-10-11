package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemFeatureRenderer.class)
public interface LitematicaPreviewItemFeatureInvoker {
    @Invoker("prepareSubmit")
    void quickcraft$prepareSubmit(ItemFeatureRenderer.Submit submit, boolean foil);
}
