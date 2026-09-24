package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickContainerFillStatus;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.10 已移除 Fabric WorldRenderEvents，在线框世界渲染完成后补画容器填充状态。 */
@Mixin(WorldRenderer.class)
public abstract class QuickContainerFillStatusWorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$renderFillStatus(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f viewMatrix,
            Matrix4f projectionMatrix,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean renderSky,
            CallbackInfo ci
    ) {
        QuickContainerFillStatus.renderWorld(MinecraftClient.getInstance(), camera);
    }
}
