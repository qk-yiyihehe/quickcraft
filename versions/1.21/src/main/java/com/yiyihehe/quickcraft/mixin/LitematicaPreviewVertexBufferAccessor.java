package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexBuffer.class)
public interface LitematicaPreviewVertexBufferAccessor {
    @Accessor("vertexBufferId") int quickcraft$getVertexBufferId();
    @Accessor("indexBufferId") int quickcraft$getIndexBufferId();
    @Accessor("indexBufferId") void quickcraft$setIndexBufferId(int id);
    @Accessor("indexType") void quickcraft$setIndexType(VertexFormat.IndexType type);
    @Accessor("indexCount") void quickcraft$setIndexCount(int count);
}
