package com.yiyihehe.quickcraft.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** 26.2+ 容器填充状态世界轮廓渲染（使用原版 Gizmos 体系）。 */
final class QuickContainerFillStatusAccess {
    private QuickContainerFillStatusAccess() {
    }

    static void renderWorld(LevelRenderContext context, Minecraft client, Vec3 camera,
                           Map<QuickContainerFillStatus.Target, QuickContainerFillStatus.Marker> markers,
                           double maxDistanceSquared) {
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
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
                Gizmos.cuboid(outline, GizmoStyle.stroke(color, client.getWindow().getAppropriateLineWidth()));
            }
        }
    }
}
