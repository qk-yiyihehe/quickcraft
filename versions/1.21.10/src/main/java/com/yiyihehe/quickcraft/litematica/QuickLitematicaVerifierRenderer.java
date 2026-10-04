package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.InventoryOverlayKind;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.SlotPosition;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.getInventoryOverlaySlotPosition;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerOverlayLayout.getInventoryType;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.GhostItemDraw;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoAlignment;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Set;

public final class QuickLitematicaVerifierRenderer {
    private static final int MISSING_SLOT_GHOST_MASK = 0xD0E2FF;

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
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.status() != QuickLitematicaContainerVerifier.SlotMismatchStatus.MISSING || mismatch.expectedStack().isEmpty()) {
                continue;
            }

            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawGhostItem(drawContext, mc, mismatch.expectedStack(), x, y, 0, 0, QuickLitematicaVerifierPalette.ghostItemAlpha());
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
        for (GhostItemDraw item : items) {
            drawGhostItem(context, client, item.stack(), item.x(), item.y(), guiLeft, guiTop, alpha);
        }
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
        if (stack.isEmpty()) {
            return;
        }

        context.drawItem(stack, x, y);
        context.drawStackOverlay(client.textRenderer, stack, x, y);
        int maskAlpha = Math.round((1.0F - Math.max(0.0F, Math.min(1.0F, alpha))) * 255.0F);
        context.fill(x, y, x + 16, y + 16, (maskAlpha << 24) | (MISSING_SLOT_GHOST_MASK & 0x00FFFFFF));
    }

    public static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
        return client != null;
    }

    public static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
    }

    private static void drawOutline(DrawContext drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlay.InventoryProperties getInventoryProperties(InventoryOverlayKind type, int size) {
        return InventoryOverlay.getInventoryPropsTempNew(InventoryOverlayType.valueOf(type.name()), size);
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
        InventoryOverlay.renderInventoryBackgroundNew(context, InventoryOverlayType.valueOf(type.name()),
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
        InventoryOverlay.renderInventoryStacksNew(context, InventoryOverlayType.valueOf(type.name()),
                inventory, x, y, slotsPerRow, 0, inventory.size(), disabledSlots, client);
    }
}
