package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.Container;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 26.2+ 投影容器高亮渲染（使用原版 Gizmos 体系）。 */
final class QuickLitematicaContainerHighlightAccess {
    private QuickLitematicaContainerHighlightAccess() {
    }

    static void renderHighlights(Minecraft client, List<QuickLitematicaContainerHighlight.ProjectedContainer> positions,
                                 Vec3 cameraPos, int maxDistance, Color4f selectedColor, float verifierAlpha) {
        int color = Math.round(Math.min(selectedColor.a, verifierAlpha) * 255.0f) << 24
                | Math.round(selectedColor.r * 255.0f) << 16
                | Math.round(selectedColor.g * 255.0f) << 8
                | Math.round(selectedColor.b * 255.0f);
        try (Gizmos.TemporaryCollection ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            for (QuickLitematicaContainerHighlight.ProjectedContainer container : positions) {
                BlockPos pos = container.pos();
                if (Math.abs(pos.getX() - cameraPos.x) > maxDistance
                        || Math.abs(pos.getZ() - cameraPos.z) > maxDistance
                        || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)
                        || !client.level.hasChunkAt(pos)
                        || client.level.getBlockState(pos).getBlock() != container.block()
                        || !(client.level.getBlockEntity(pos) instanceof Container)) {
                    continue;
                }
                Gizmos.cuboid(new AABB(pos).inflate(0.002), GizmoStyle.fill(color)).setAlwaysOnTop();
            }
        }
    }
}
