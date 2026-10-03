package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 临时切换 Minecraft 当前帧缓冲。
 * 让幽灵物品先绘制到额外缓冲，再整体按透明度回贴。
 */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void quickcraft$setFramebuffer(RenderTarget framebuffer);
}
