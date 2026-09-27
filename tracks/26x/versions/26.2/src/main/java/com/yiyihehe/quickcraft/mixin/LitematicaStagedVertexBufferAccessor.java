package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 管理 26.2 投影实体缓存的 CPU 暂存内存和 GPU 容量上限。
 */
@Mixin(StagedVertexBuffer.class)
public interface LitematicaStagedVertexBufferAccessor {
    @Accessor("stagingBuffer")
    ByteBufferBuilder quickcraft$getStagingBuffer();

    @Accessor("currentVertexBuffer")
    @Nullable GpuBuffer quickcraft$getCurrentVertexBuffer();

    @Accessor("currentIndexBuffer")
    @Nullable GpuBuffer quickcraft$getCurrentIndexBuffer();
}
