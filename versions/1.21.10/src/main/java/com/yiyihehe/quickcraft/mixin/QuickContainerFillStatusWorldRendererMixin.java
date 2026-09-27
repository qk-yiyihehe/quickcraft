package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickContainerFillStatus;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerHighlight;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
//#if MC>=12111
//$$ import net.minecraft.client.util.memory.ObjectAllocator;
//#else
import net.minecraft.client.util.ObjectAllocator;
//#endif
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 新版无 Fabric 世界渲染事件，在线框世界渲染完成后补画容器填充状态。
 * render 末尾已弹出相机旋转矩阵；不临时恢复它，状态框会随视角漂移。
 */
@Mixin(WorldRenderer.class)
public abstract class QuickContainerFillStatusWorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$renderFillStatus(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            //#if MC>=12111
            //$$ Matrix4f basicProjectionMatrix,
            //#else
            Matrix4f viewMatrix,
            //#endif
            Matrix4f projectionMatrix,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean renderSky,
            CallbackInfo ci
    ) {
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            modelView.mul(positionMatrix);
            QuickContainerFillStatus.renderWorld(MinecraftClient.getInstance(), camera);
            //#if MC<12111
            QuickLitematicaContainerHighlight.renderWorld(MinecraftClient.getInstance(), camera);
            //#endif
        } finally {
            modelView.popMatrix();
        }
    }
}
