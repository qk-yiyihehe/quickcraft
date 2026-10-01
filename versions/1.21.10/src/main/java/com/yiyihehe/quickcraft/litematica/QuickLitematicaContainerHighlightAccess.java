package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;

class QuickLitematicaContainerHighlightAccess {
    QuickLitematicaContainerHighlightAccess() {
    }

    static void registerWorldRenderer() {
        // Handled via QuickContainerFillStatusWorldRendererMixin on 1.21.10
    }

    static Vec3d getCameraPos(Camera camera) {
        return camera.getPos();
    }

    static Map<BlockPos, NbtCompound> getBlockEntities(LitematicaSchematic schematic, String regionName) {
        return schematic.getBlockEntityMapForRegion(regionName);
    }

    static String getBlockEntityId(NbtCompound nbt) {
        return nbt.getString("id").orElse("");
    }

    static void renderHighlights(MinecraftClient client,
                                 List<QuickLitematicaContainerHighlight.ProjectedContainer> positions,
                                 Vec3d cameraPos,
                                 int maxDistance) {
        Color4f selectedColor = QuickCraftConfigs.ProjectionTools.PROJECTION_CONTAINER_HIGHLIGHT_COLOR.getColor();
        float verifierAlpha = (float) Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_ALPHA.getDoubleValue();
        Color4f color = new Color4f(
                selectedColor.r, selectedColor.g, selectedColor.b, Math.min(selectedColor.a, verifierAlpha)
        );

        try (RenderContext render = new RenderContext(
                () -> "QuickCraft projected container highlight",
                MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH
        )) {
            BufferBuilder buffer = render.getBuilder();
            for (QuickLitematicaContainerHighlight.ProjectedContainer container : positions) {
                if (!QuickLitematicaContainerHighlight.shouldRenderContainer(client, container, cameraPos, maxDistance)) {
                    continue;
                }
                BlockPos pos = container.pos();
                RenderUtils.renderAreaSidesBatched(pos, pos, color, 0.002, buffer);
            }
            BuiltBuffer built = buffer.endNullable();
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
