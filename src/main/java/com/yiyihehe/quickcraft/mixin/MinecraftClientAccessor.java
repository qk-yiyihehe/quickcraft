package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 临时切换 MinecraftClient 当前帧缓冲。
 * 让幽灵物品先绘制到额外缓冲，再整体按透明度回贴。
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("framebuffer")
    void quickcraft$setFramebuffer(Framebuffer framebuffer);
}
