package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gl.GlGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlGpuBuffer.class)
public interface LitematicaPreviewGlBufferAccessor {
    @Accessor("id") int quickcraft$getId();
}
