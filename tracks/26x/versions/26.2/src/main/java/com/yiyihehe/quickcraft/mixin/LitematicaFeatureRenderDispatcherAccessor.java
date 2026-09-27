package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 为 26.2 的投影实体缓存替换独立暂存缓冲，避免占用原版每帧共享缓冲。
 */
@Mixin(FeatureRenderDispatcher.class)
public interface LitematicaFeatureRenderDispatcherAccessor {
    @Mutable
    @Accessor("stagedVertexBuffer")
    void quickcraft$setStagedVertexBuffer(StagedVertexBuffer stagedVertexBuffer);
}
