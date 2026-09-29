package com.yiyihehe.quickcraft.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.Map;

/** 26.1.2 容器填充状态世界轮廓渲染（使用 ShapeRenderer 与 PoseStack）。 */
final class QuickContainerFillStatusAccess {
    private QuickContainerFillStatusAccess() {
    }

    static void renderWorld(LevelRenderContext context, Minecraft client, Vec3 camera,
                           Map<QuickContainerFillStatus.Target, QuickContainerFillStatus.Marker> markers,
                           double maxDistanceSquared) {
        if (context.poseStack() == null) {
            return;
        }
        context.poseStack().pushPose();
        context.poseStack().translate(-camera.x, -camera.y, -camera.z);
        for (Map.Entry<QuickContainerFillStatus.Target, QuickContainerFillStatus.Marker> entry : markers.entrySet()) {
            AABB box = entry.getKey().box(client.level);
            if (box == null || box.getCenter().distanceToSqr(camera) > maxDistanceSquared) {
                continue;
            }
            QuickContainerFillStatus.Status status = entry.getValue().status;
            AABB outline = new AABB(
                    box.minX - 0.01, box.minY + 0.0125, box.minZ - 0.01,
                    box.maxX + 0.01, box.maxY + 0.01, box.maxZ + 0.01
            );
            int color = 0xF2000000
                    | ((int) (status.red * 255) << 16)
                    | ((int) (status.green * 255) << 8)
                    | (int) (status.blue * 255);
            ShapeRenderer.renderShape(context.poseStack(),
                    context.bufferSource().getBuffer(RenderTypes.linesTranslucent()),
                    Shapes.create(outline), 0, 0, 0, color, client.getWindow().getAppropriateLineWidth());
        }
        context.poseStack().popPose();
    }
}
