package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gl.GlGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GlGpuBuffer.class)
public interface LitematicaPreviewGlBufferAccessor {
    @Accessor("id") int quickcraft$getId();
    @Invoker("ensureAllocated") void quickcraft$ensureAllocated();
}
