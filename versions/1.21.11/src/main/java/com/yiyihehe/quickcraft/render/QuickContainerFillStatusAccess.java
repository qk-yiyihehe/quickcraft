package com.yiyihehe.quickcraft.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

import java.util.Map;

/**
 * 1.21.11 容器填充状态世界轮廓渲染（WorldRenderer.render 注入 + VertexRendering.drawOutline）。
 */
final class QuickContainerFillStatusAccess {
    private QuickContainerFillStatusAccess() {
    }

    static void registerWorldRenderer() {
    }

    static void renderWorld(MinecraftClient client, Camera camera) {
        if (!QuickContainerFillStatus.isAvailable(client) || QuickContainerFillStatus.MARKERS.isEmpty()) {
            return;
        }
        Vec3d cameraPos = camera.getCameraPos();
        MatrixStack matrices = new MatrixStack();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        for (Map.Entry<QuickContainerFillStatus.Target, QuickContainerFillStatus.Marker> entry : QuickContainerFillStatus.MARKERS.entrySet()) {
            Box box = entry.getKey().box(client.world);
            if (box == null || box.getCenter().squaredDistanceTo(cameraPos) > QuickContainerFillStatus.MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }
            QuickContainerFillStatus.Status status = entry.getValue().status;
            Box outline = new Box(
                    box.minX - 0.01, box.minY + 0.0125, box.minZ - 0.01,
                    box.maxX + 0.01, box.maxY + 0.01, box.maxZ + 0.01
            );
            VertexRendering.drawOutline(matrices, consumers.getBuffer(RenderLayers.lines()),
                    VoxelShapes.cuboid(outline), 0, 0, 0,
                    ColorHelper.fromFloats(0.95F, status.red, status.green, status.blue),
                    client.getWindow().getMinimumLineWidth());
        }
        matrices.pop();
        consumers.draw(RenderLayers.lines());
    }
}
