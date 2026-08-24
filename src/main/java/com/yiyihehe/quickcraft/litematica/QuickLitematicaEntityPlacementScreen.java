package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

/** 原版 54 格箱子选择器；背包和容器预览均直接复用原版纹理与物品渲染。 */
final class QuickLitematicaEntityPlacementScreen extends Screen {
    private static final Identifier CHEST_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final Identifier HOPPER_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/hopper.png");
    private static final int COLUMNS = 9;
    private static final int ROWS = 6;
    private static final int SLOT_SIZE = 18;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 222;
    private static final int HOPPER_CROP_X = 38;
    private static final int HOPPER_CROP_Y = 12;
    private static final int HOPPER_CROP_WIDTH = 100;
    private static final int HOPPER_CROP_HEIGHT = 32;

    private final List<QuickLitematicaEntityPlacement.Candidate> candidates;
    private QuickLitematicaEntityPlacement.PlacementEvaluation evaluation;
    private final Map<Entity, QuickLitematicaEntityPlacement.ExcessDisplay> excessDisplays = new IdentityHashMap<>();
    private int evaluationRefreshTicks;

    QuickLitematicaEntityPlacementScreen(
            List<QuickLitematicaEntityPlacement.Candidate> candidates
    ) {
        super(Component.translatable("quickcraft.entity_placement.title"));
        this.candidates = candidates;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.evaluation == null || --this.evaluationRefreshTicks <= 0) {
            this.evaluation = QuickLitematicaEntityPlacement.evaluatePlacement(this.minecraft, this.candidates);
            this.evaluationRefreshTicks = 4;
            this.excessDisplays.clear();
            for (Entity entity : this.evaluation.excessEntities()) {
                this.excessDisplays.put(entity,
                        QuickLitematicaEntityPlacement.createExcessDisplay(entity, this.candidates));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        boolean serverAvailable = QuickLitematicaEntityPlacement.isServerAvailable();
        context.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
                left, top, 0, 0, PANEL_WIDTH, PANEL_HEIGHT, 256, 256);
        context.centeredText(this.font, this.title, this.width / 2, top + 6, 0xFFFFFFFF);

        int noticeY = top - 13;
        if (!serverAvailable) {
            context.centeredText(this.font,
                    Component.translatable("quickcraft.entity_placement.server_unavailable"),
                    this.width / 2, noticeY, 0xFFFF5555);
            noticeY -= 13;
        }

        if (this.evaluation == null) {
            this.evaluation = QuickLitematicaEntityPlacement.evaluatePlacement(this.minecraft, this.candidates);
        }
        QuickLitematicaEntityPlacement.PlacementEvaluation evaluation = this.evaluation;
        Map<QuickLitematicaEntityPlacement.Candidate, QuickLitematicaEntityPlacement.PlacementStatus> statuses =
                evaluation.statuses();
        int excessCount = evaluation.excessEntities().size();
        if (excessCount > 0) {
            context.text(this.font,
                    Component.translatable("quickcraft.entity_placement.excess", excessCount),
                    left + 8, noticeY, 0xFFD070D0);
        }

        QuickLitematicaEntityPlacement.Candidate hoveredCandidate = null;
        QuickLitematicaEntityPlacement.PlacementStatus hoveredStatus =
                QuickLitematicaEntityPlacement.PlacementStatus.UNPLACED;
        for (int index = 0; index < Math.min(candidates.size(), COLUMNS * ROWS); index++) {
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            int x = left + 8 + column * SLOT_SIZE;
            int y = top + 18 + row * SLOT_SIZE;
            QuickLitematicaEntityPlacement.Candidate candidate = candidates.get(index);
            QuickLitematicaEntityPlacement.PlacementStatus status = statuses.getOrDefault(
                    candidate, QuickLitematicaEntityPlacement.PlacementStatus.UNPLACED);
            boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            context.fill(x, y, x + 16, y + 16, getSlotColor(status, hovered));
            context.item(candidate.material(), x, y);
            if (hovered) {
                hoveredCandidate = candidate;
                hoveredStatus = status;
            }
        }

        QuickLitematicaEntityPlacement.ExcessDisplay hoveredExcess = null;
        int excessStart = Math.min(candidates.size(), COLUMNS * ROWS);
        for (int index = 0; index < evaluation.excessEntities().size()
                && excessStart + index < COLUMNS * ROWS; index++) {
            int displayIndex = excessStart + index;
            int column = displayIndex % COLUMNS;
            int row = displayIndex / COLUMNS;
            int x = left + 8 + column * SLOT_SIZE;
            int y = top + 18 + row * SLOT_SIZE;
            QuickLitematicaEntityPlacement.ExcessDisplay excess = this.excessDisplays.computeIfAbsent(
                    evaluation.excessEntities().get(index),
                    entity -> QuickLitematicaEntityPlacement.createExcessDisplay(entity, this.candidates));
            context.fill(x, y, x + 16, y + 16, 0xB06A3D9A);
            if (!excess.stack().isEmpty()) {
                context.item(excess.stack(), x, y);
                context.itemDecorations(this.font, excess.stack(), x, y);
            }
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hoveredExcess = excess;
            }
        }

        renderPlayerInventory(context, left, top, mouseX, mouseY);
        if (hoveredCandidate != null) {
            renderContainerPreview(context, hoveredCandidate, mouseX, mouseY);
            List<Component> tooltip = hoveredCandidate.getTooltip(hoveredStatus);
            context.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
        } else if (hoveredExcess != null) {
            context.setTooltipForNextFrame(this.font,
                    Component.translatable("quickcraft.entity_placement.excess_entity"), mouseX, mouseY);
        }

        if (candidates.isEmpty()) {
            Component message = Component.translatable("quickcraft.entity_placement.empty");
            context.text(this.font, message,
                    (this.width - this.font.width(message)) / 2, top + 86, 0xFF404040, false);
        }
    }

    private void renderPlayerInventory(GuiGraphicsExtractor context, int left, int top, int mouseX, int mouseY) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        ItemStack hovered = ItemStack.EMPTY;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int slot = 9 + row * 9 + column;
                int x = left + 8 + column * SLOT_SIZE;
                int y = top + 139 + row * SLOT_SIZE;
                ItemStack stack = this.minecraft.player.getInventory().getItem(slot);
                context.item(stack, x, y);
                context.itemDecorations(this.font, stack, x, y);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    hovered = stack;
                }
            }
        }
        for (int column = 0; column < 9; column++) {
            int x = left + 8 + column * SLOT_SIZE;
            int y = top + 197;
            ItemStack stack = this.minecraft.player.getInventory().getItem(column);
            context.item(stack, x, y);
            context.itemDecorations(this.font, stack, x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hovered = stack;
            }
        }
        if (!hovered.isEmpty()) {
            context.setTooltipForNextFrame(this.font, hovered, mouseX, mouseY);
        }
    }

    private void renderContainerPreview(
            GuiGraphicsExtractor context,
            QuickLitematicaEntityPlacement.Candidate candidate,
            int mouseX,
            int mouseY
    ) {
        QuickLitematicaEntityPlacement.ContainerPreview preview = candidate.getContainerPreview();
        if (preview == null) {
            return;
        }
        int width = preview.type() == QuickLitematicaEntityPlacement.ContainerPreviewType.HOPPER
                ? HOPPER_CROP_WIDTH : 176;
        int height = preview.type() == QuickLitematicaEntityPlacement.ContainerPreviewType.HOPPER
                ? HOPPER_CROP_HEIGHT : (preview.size() / 9) * 18 + 17;
        int x = mouseX + 8;
        if (x + width > this.width) {
            x = mouseX - width - 8;
        }
        int yBelow = mouseY + 32;
        int y = yBelow + height <= this.height
                ? yBelow
                : Math.max(4, mouseY - height - 16);
        if (preview.type() == QuickLitematicaEntityPlacement.ContainerPreviewType.HOPPER) {
            context.blit(RenderPipelines.GUI_TEXTURED, HOPPER_TEXTURE, x, y,
                    HOPPER_CROP_X, HOPPER_CROP_Y, HOPPER_CROP_WIDTH, HOPPER_CROP_HEIGHT, 256, 256);
            List<ItemStack> stacks = candidate.getStoredStacks(this.minecraft, preview.size());
            for (int slot = 0; slot < stacks.size(); slot++) {
                drawStack(context, stacks.get(slot), x + 6 + slot * SLOT_SIZE, y + 8);
            }
        } else {
            int rows = preview.size() / 9;
            context.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
                    x, y, 0, 0, width, rows * 18 + 17, 256, 256);
            List<ItemStack> stacks = candidate.getStoredStacks(this.minecraft, preview.size());
            for (int slot = 0; slot < stacks.size(); slot++) {
                drawStack(context, stacks.get(slot), x + 8 + (slot % 9) * SLOT_SIZE,
                        y + 18 + (slot / 9) * SLOT_SIZE);
            }
        }
    }

    private void drawStack(GuiGraphicsExtractor context, ItemStack stack, int x, int y) {
        context.item(stack, x, y);
        context.itemDecorations(this.font, stack, x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button != 0 || !QuickLitematicaEntityPlacement.isServerAvailable()) {
            return super.mouseClicked(click, doubled);
        }
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int column = ((int) mouseX - left - 8) / SLOT_SIZE;
        int row = ((int) mouseY - top - 18) / SLOT_SIZE;
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            return super.mouseClicked(click, doubled);
        }
        int index = row * COLUMNS + column;
        if (index < candidates.size()
                && mouseX < left + 8 + column * SLOT_SIZE + 16
                && mouseY < top + 18 + row * SLOT_SIZE + 16) {
            if (QuickLitematicaEntityPlacement.requestPlacement(
                    this.minecraft, candidates.get(index), this.candidates)) {
                this.onClose();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private static int getSlotColor(QuickLitematicaEntityPlacement.PlacementStatus status, boolean hovered) {
        int color = switch (status) {
            case MATCHED -> 0xB03A7D44;
            case MISMATCHED -> 0xB09B641E;
            case WRONG -> 0xB08A3535;
            case UNPLACED -> 0x00000000;
        };
        return hovered && color != 0 ? brighten(color) : color;
    }

    private static int brighten(int color) {
        int alpha = color & 0xFF000000;
        int red = Math.min(255, ((color >>> 16) & 255) + 35);
        int green = Math.min(255, ((color >>> 8) & 255) + 35);
        int blue = Math.min(255, (color & 255) + 35);
        return alpha | red << 16 | green << 8 | blue;
    }
}
