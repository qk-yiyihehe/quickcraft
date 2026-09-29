package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 26.1.2 投影容器高亮渲染（使用 MaLiLib 无深度管线）。 */
final class QuickLitematicaContainerHighlightAccess {
    private QuickLitematicaContainerHighlightAccess() {
    }

    static void renderHighlights(Minecraft client, List<QuickLitematicaContainerHighlight.ProjectedContainer> positions,
                                 Vec3 cameraPos, int maxDistance, Color4f selectedColor, float verifierAlpha) {
        Color4f color = new Color4f(
                selectedColor.r, selectedColor.g, selectedColor.b, Math.min(selectedColor.a, verifierAlpha)
        );
        try (RenderContext render = new RenderContext(
                () -> "QuickCraft projected container highlight",
                MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH
        )) {
            BufferBuilder buffer = render.getBuilder();
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
                RenderUtils.renderAreaSidesBatched(pos, pos, color, 0.002, buffer);
            }
            MeshData built = buffer.build();
            if (built != null) {
                try (built) {
                    render.upload(built, false);
                    render.drawPost();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render projected containers", e);
        }
    }
}
