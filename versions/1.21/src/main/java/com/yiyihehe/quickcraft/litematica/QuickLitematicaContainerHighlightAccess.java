package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;

class QuickLitematicaContainerHighlightAccess {
    QuickLitematicaContainerHighlightAccess() {
    }

    static void registerWorldRenderer() {
        WorldRenderEvents.LAST.register(context ->
                QuickLitematicaContainerHighlight.renderWorld(
                        MinecraftClient.getInstance(),
                        context.camera().getPos()
                )
        );
    }

    static Vec3d getCameraPos(Camera camera) {
        return camera.getPos();
    }

    static Map<BlockPos, NbtCompound> getBlockEntities(LitematicaSchematic schematic, String regionName) {
        return schematic.getBlockEntityMapForRegion(regionName);
    }

    static String getBlockEntityId(NbtCompound nbt) {
        return nbt.getString("id");
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

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        try {
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            for (QuickLitematicaContainerHighlight.ProjectedContainer container : positions) {
                if (!QuickLitematicaContainerHighlight.shouldRenderContainer(client, container, cameraPos, maxDistance)) {
                    continue;
                }
                BlockPos pos = container.pos();
                RenderUtils.renderAreaSidesBatched(pos, pos, color, 0.002, buffer, client);
            }
            BuiltBuffer built = buffer.endNullable();
            if (built != null) {
                try (built) {
                    BufferRenderer.drawWithGlobalProgram(built);
                }
            }
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }
}
