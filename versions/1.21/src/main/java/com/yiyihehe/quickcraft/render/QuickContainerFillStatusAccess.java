package com.yiyihehe.quickcraft.render;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * 1.21 容器填充状态世界轮廓渲染（WorldRenderEvents.AFTER_ENTITIES + WorldRenderer.drawBox）。
 */
final class QuickContainerFillStatusAccess {
    private QuickContainerFillStatusAccess() {
    }

    static void registerWorldRenderer() {
        WorldRenderEvents.AFTER_ENTITIES.register(QuickContainerFillStatusAccess::renderWorld);
    }

    static void renderWorld(MinecraftClient client, Camera camera) {
    }

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuickContainerFillStatus.isAvailable(client) || context.consumers() == null || QuickContainerFillStatus.MARKERS.isEmpty()) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        context.matrixStack().push();
        context.matrixStack().translate(-camera.x, -camera.y, -camera.z);
        for (Map.Entry<QuickContainerFillStatus.Target, QuickContainerFillStatus.Marker> entry : QuickContainerFillStatus.MARKERS.entrySet()) {
            Box box = entry.getKey().box(client.world);
            if (box == null || box.getCenter().squaredDistanceTo(camera) > QuickContainerFillStatus.MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }
            QuickContainerFillStatus.Status status = entry.getValue().status;
            Box outline = new Box(
                    box.minX - 0.01, box.minY + 0.0125, box.minZ - 0.01,
                    box.maxX + 0.01, box.maxY + 0.01, box.maxZ + 0.01
            );
            WorldRenderer.drawBox(
                    context.matrixStack(),
                    context.consumers().getBuffer(RenderLayer.getLines()),
                    outline,
                    status.red, status.green, status.blue, 0.95F
            );
        }
        context.matrixStack().pop();
    }
}
