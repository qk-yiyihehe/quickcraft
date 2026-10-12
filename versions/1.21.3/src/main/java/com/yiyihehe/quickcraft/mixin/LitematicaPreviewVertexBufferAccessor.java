package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexBuffer.class)
public interface LitematicaPreviewVertexBufferAccessor {
    @Accessor("vertexBuffer") net.minecraft.client.gl.GpuBuffer quickcraft$getVertexBuffer();
    @Accessor("indexBuffer") net.minecraft.client.gl.GpuBuffer quickcraft$getIndexBuffer();
    @Accessor("indexBuffer") void quickcraft$setIndexBuffer(net.minecraft.client.gl.GpuBuffer buffer);
    @Accessor("indexType") void quickcraft$setIndexType(VertexFormat.IndexType type);
    @Accessor("indexCount") void quickcraft$setIndexCount(int count);
}
