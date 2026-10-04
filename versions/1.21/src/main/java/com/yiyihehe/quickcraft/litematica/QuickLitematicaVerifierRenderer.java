package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.InventoryOverlayKind;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.SlotPosition;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.getInventoryOverlaySlotPosition;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.getInventoryType;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.GhostItemDraw;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoAlignment;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class QuickLitematicaVerifierRenderer {
    private QuickLitematicaVerifierRenderer() {
    }

    public static void renderInventoryPair(
            ContainerMismatch mismatch,
            BlockState expectedState,
            BlockState foundState,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            int mouseX,
            int mouseY,
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        if (mismatch == null) {
            return;
        }
        Pair<Inventory, Inventory> inventories = mismatch.inventories();
        if (inventories == null) {
            return;
        }

        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.LEFT, 0, mismatch.type(), inventories.getLeft(), expectedState, expectedDisabledSlots, List.of(), false, mouseX, mouseY, mc, drawContext);
        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.RIGHT, 0, mismatch.type(), inventories.getRight(), foundState, foundDisabledSlots, mismatch.slotMismatches(), true, mouseX, mouseY, mc, drawContext);
    }

    private static void renderInventoryOverlay(
            BlockInfoAlignment alignment,
            LeftRight side,
            int offY,
            MismatchType mismatchType,
            Inventory inventory,
            BlockState state,
            Set<Integer> disabledSlots,
            List<SlotMismatch> slotMismatches,
            boolean renderGhostStacks,
            int mouseX,
            int mouseY,
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        if (inventory == null) {
            return;
        }

        InventoryOverlayKind type = getInventoryType(inventory, state);
        InventoryOverlay.InventoryProperties props = getInventoryProperties(type, inventory.size());
        int xInv = 0;
        int yInv = 0;

        switch (alignment) {
            case CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowHeight() / 2 - props.height - offY;
            }
            case TOP_CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = offY;
            }
        }

        if (side == LeftRight.LEFT) {
            xInv -= props.width / 2 + 4;
        } else if (side == LeftRight.RIGHT) {
            xInv += props.width / 2 + 4;
        }

        renderInventoryBackground(drawContext, type, xInv, yInv, props.slotsPerRow, props.totalSlots, mc);
        drawSlotHighlights(drawContext, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        renderInventoryStacks(drawContext, type, inventory, xInv + props.slotOffsetX, yInv + props.slotOffsetY,
                props.slotsPerRow, disabledSlots, mc);

        if (renderGhostStacks) {
            drawMissingGhostStacks(drawContext, mc, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        }
    }

    private static void drawSlotHighlights(
            DrawContext drawContext,
            InventoryOverlayKind type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawContext.fill(x, y, x + 16, y + 16, mismatch.status().fillColor());
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static void drawMissingGhostStacks(
            DrawContext drawContext,
            MinecraftClient mc,
            InventoryOverlayKind type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        List<GhostItemDraw> ghostItems = new ArrayList<>();
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.status() != SlotMismatchStatus.MISSING || mismatch.expectedStack().isEmpty()) {
                continue;
            }

            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            ghostItems.add(new GhostItemDraw(mismatch.expectedStack(), pos.x(), pos.y()));
        }

        drawGhostItems(drawContext, mc, ghostItems, 0, 0, QuickLitematicaVerifierPalette.ghostItemAlpha());

        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.status() != SlotMismatchStatus.MISSING || mismatch.expectedStack().isEmpty()) {
                continue;
            }

            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawContext.fill(x, y, x + 16, y + 16, mismatch.status().ghostMaskColor());
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    public static void drawGhostItems(
            DrawContext context,
            MinecraftClient client,
            List<GhostItemDraw> items,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        GhostItemBuffer.drawGhostItems(context, client, items, guiLeft, guiTop, alpha);
    }

    public static void drawGhostItem(
            DrawContext context,
            MinecraftClient client,
            ItemStack stack,
            int x,
            int y,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        GhostItemBuffer.drawGhostItems(context, client, List.of(new GhostItemDraw(stack, x, y)), guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
        return GhostItemBuffer.beginHandledScreenGhostRender(context, client);
    }

    public static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
        GhostItemBuffer.endHandledScreenGhostRender(context, guiLeft, guiTop, alpha);
    }

    private static void drawOutline(DrawContext drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlay.InventoryProperties getInventoryProperties(InventoryOverlayKind type, int size) {
        return InventoryOverlay.getInventoryPropsTemp(InventoryOverlay.InventoryRenderType.valueOf(type.name()), size);
    }

    private static void renderInventoryBackground(
            DrawContext context,
            InventoryOverlayKind type,
            int x,
            int y,
            int slotsPerRow,
            int totalSlots,
            MinecraftClient client
    ) {
        RenderUtils.color(1f, 1f, 1f, 1f);
        InventoryOverlay.renderInventoryBackground(InventoryOverlay.InventoryRenderType.valueOf(type.name()),
                x, y, slotsPerRow, totalSlots, client);
    }

    private static void renderInventoryStacks(
            DrawContext context,
            InventoryOverlayKind type,
            Inventory inventory,
            int x,
            int y,
            int slotsPerRow,
            Set<Integer> disabledSlots,
            MinecraftClient client
    ) {
        InventoryOverlay.renderInventoryStacks(InventoryOverlay.InventoryRenderType.valueOf(type.name()),
                inventory, x, y, slotsPerRow, 0, inventory.size(), disabledSlots, client, context);
    }

    private static final class GhostItemBuffer {
        private static Framebuffer framebuffer;
        private static int previousFramebuffer;

        private GhostItemBuffer() {
        }

        private static void drawGhostItems(
                DrawContext context,
                MinecraftClient client,
                List<GhostItemDraw> items,
                int guiLeft,
                int guiTop,
                float alpha
        ) {
            if (items.isEmpty()) {
                return;
            }

            if (!beginHandledScreenGhostRender(context, client)) {
                return;
            }

            for (GhostItemDraw item : items) {
                if (item.stack().isEmpty()) {
                    continue;
                }

                context.drawItem(item.stack(), item.x(), item.y());
                context.drawItemInSlot(client.textRenderer, item.stack(), item.x(), item.y());
            }
            endHandledScreenGhostRender(context, guiLeft, guiTop, alpha);
        }

        private static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
            framebuffer = getFramebuffer();
            previousFramebuffer = GlStateManager.getBoundFramebuffer();
            framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
            framebuffer.beginWrite(false);
            return true;
        }

        private static void endHandledScreenGhostRender(
                DrawContext context,
                int guiLeft,
                int guiTop,
                float alpha
        ) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
            drawFramebuffer(context, framebuffer);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        private static Framebuffer getFramebuffer() {
            MinecraftClient client = MinecraftClient.getInstance();
            Window window = client.getWindow();
            int framebufferWidth = window.getFramebufferWidth();
            int framebufferHeight = window.getFramebufferHeight();

            if (framebuffer == null) {
                framebuffer = new SimpleFramebuffer(framebufferWidth, framebufferHeight, true, MinecraftClient.IS_SYSTEM_MAC);
                framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            } else if (framebuffer.textureWidth != framebufferWidth || framebuffer.textureHeight != framebufferHeight) {
                framebuffer.resize(framebufferWidth, framebufferHeight, MinecraftClient.IS_SYSTEM_MAC);
                framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            }

            return framebuffer;
        }

        private static void drawFramebuffer(DrawContext context, Framebuffer ghostFramebuffer) {
            RenderSystem.setShaderTexture(0, ghostFramebuffer.getColorAttachment());
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            Matrix4f projection = new Matrix4f()
                    .setOrtho(
                            0.0F,
                            context.getScaledWindowWidth(),
                            context.getScaledWindowHeight(),
                            0.0F,
                            1000.0F,
                            21000.0F
                    );
            RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);

            Matrix4f positionMatrix = context.getMatrices().peek().getPositionMatrix();
            BufferBuilder bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
            float width = context.getScaledWindowWidth();
            float height = context.getScaledWindowHeight();
            bufferBuilder.vertex(positionMatrix, 0.0F, 0.0F, 0.0F).texture(0.0F, 1.0F);
            bufferBuilder.vertex(positionMatrix, 0.0F, height, 0.0F).texture(0.0F, 0.0F);
            bufferBuilder.vertex(positionMatrix, width, height, 0.0F).texture(1.0F, 0.0F);
            bufferBuilder.vertex(positionMatrix, width, 0.0F, 0.0F).texture(1.0F, 1.0F);
            BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
