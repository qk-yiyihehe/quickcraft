package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.1 keeps the pipeline and resolved texture bindings in RenderType.state.
 * The cached preview renderer needs that exact setup when drawing its retained VBOs.
 */
@Mixin(RenderType.class)
public interface RenderLayerAccessor {
    @Accessor("state")
    RenderSetup quickcraft$getRenderSetup();
}
