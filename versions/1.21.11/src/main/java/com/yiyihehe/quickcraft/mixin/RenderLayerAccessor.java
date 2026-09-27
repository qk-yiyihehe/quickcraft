package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.11 stores layer texture bindings in RenderSetup instead of exposing the
 * old RenderSystem shader-texture state used by the preview cache.
 */
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {
    @Accessor("renderSetup")
    RenderSetup quickcraft$getRenderSetup();
}
