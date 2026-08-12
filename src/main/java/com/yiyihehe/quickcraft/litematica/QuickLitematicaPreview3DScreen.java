package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Litematica 原理图的全屏 3D 查看、图片导出与文件内预览图编辑界面。
 * 网格、动态实体和缓存生命周期仍由 QuickLitematicaPreview3D.Manager 统一管理。
 */
public final class QuickLitematicaPreview3DScreen extends Screen {
    private static final int PANEL_WIDTH = 150;
    private static final int MARGIN = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int COLOR_AREA_SIZE = 96;
    private static final int HUE_BAR_WIDTH = 12;
    private static final int COLOR_PICKER_GAP = 4;
    private static final int COLOR_PICKER_PADDING = 4;
    private static final int COLOR_PICKER_WIDTH = COLOR_PICKER_PADDING * 2 + COLOR_AREA_SIZE + COLOR_PICKER_GAP + HUE_BAR_WIDTH;
    private static final int COLOR_PICKER_HEIGHT = COLOR_PICKER_PADDING * 2 + COLOR_AREA_SIZE;
    private static final int[] EXPORT_RESOLUTIONS = {512, 1024, 2048, 4096, 8192};

    private final Screen parent;
    private final String displayName;
    private final QuickLitematicaPreview3D.Manager manager;
    private final boolean closeManagerOnExit;
    private int resolutionIndex = 2;
    private boolean waitingForRecommendedResolution = true;
    private Background background = Background.TRANSPARENT;
    private int customBackgroundColor = 0xFF3A7BD5;
    private float customHue = 0.6F;
    private float customSaturation = 0.73F;
    private float customBrightness = 0.84F;
    private int viewX;
    private int viewY;
    private int viewSize;
    private Button resolutionButton;
    private Button backgroundButton;
    private Button colorButton;
    private Button exportButton;
    private Button copyButton;
    @Nullable
    private Button currentViewPreviewButton;
    @Nullable
    private Button selectPreviewImageButton;
    @Nullable
    private Button removePreviewImageButton;
    private int viewSectionTitleY;
    private int previewSectionTitleY = -1;
    private int exportSectionTitleY;
    private boolean snapshotActionInProgress;
    private Component status = Component.empty();

    QuickLitematicaPreview3DScreen(
            Screen parent,
            String displayName,
            QuickLitematicaPreview3D.Manager manager
    ) {
        this(parent, displayName, manager, false);
    }

    QuickLitematicaPreview3DScreen(
            Screen parent,
            String displayName,
            QuickLitematicaPreview3D.Manager manager,
            boolean closeManagerOnExit
    ) {
        super(Component.translatable("quickcraft.litematica.preview_3d.fullscreen_title"));
        this.parent = parent;
        this.displayName = displayName;
        this.manager = manager;
        this.closeManagerOnExit = closeManagerOnExit;
    }

    @Override
    protected void init() {
        int panelX = this.width - PANEL_WIDTH + 10;
        int buttonWidth = PANEL_WIDTH - 20;
        int halfWidth = (buttonWidth - 4) / 2;
        boolean compact = this.height < 320;
        int rowStep = compact ? 20 : 24;
        int sectionStep = compact ? 18 : this.height < 340 ? 24 : 30;
        int labelStep = compact ? 10 : 14;
        int y = compact ? 20 : 28;

        this.viewSectionTitleY = y;
        y += labelStep;
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.isometric"),
                panelX, y, buttonWidth,
                () -> this.manager.setPreset(45.0, 32.0)
        ));
        y += rowStep;
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.front"),
                panelX, y, halfWidth,
                () -> this.manager.setPreset(0.0, 0.0)
        ));
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.back"),
                panelX + halfWidth + 4, y, halfWidth,
                () -> this.manager.setPreset(180.0, 0.0)
        ));
        y += rowStep;
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.left"),
                panelX, y, halfWidth,
                () -> this.manager.setPreset(-90.0, 0.0)
        ));
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.right"),
                panelX + halfWidth + 4, y, halfWidth,
                () -> this.manager.setPreset(90.0, 0.0)
        ));
        y += rowStep;
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.top"),
                panelX, y, halfWidth,
                () -> this.manager.setPreset(0.0, 85.0)
        ));
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.preset.bottom"),
                panelX + halfWidth + 4, y, halfWidth,
                () -> this.manager.setPreset(0.0, -85.0)
        ));

        y += sectionStep;
        if (this.manager.hasFilePreviewTarget()) {
            this.previewSectionTitleY = y;
            y += labelStep;
            this.currentViewPreviewButton = this.addRenderableWidget(this.button(
                    Component.translatable("quickcraft.litematica.preview_3d.generate_from_current_view"),
                    panelX, y, buttonWidth,
                    this::saveCurrentViewAsPreview
            ));
            y += rowStep;
            this.selectPreviewImageButton = this.addRenderableWidget(this.button(
                    Component.translatable("quickcraft.litematica.preview_3d.select_local_image"),
                    panelX, y, halfWidth,
                    this::selectPreviewImage
            ));
            this.removePreviewImageButton = this.addRenderableWidget(this.button(
                    Component.translatable("quickcraft.litematica.preview_3d.remove_preview_image"),
                    panelX + halfWidth + 4, y, halfWidth,
                    this::removePreviewImage
            ));
            y += sectionStep;
        }

        this.exportSectionTitleY = y;
        y += labelStep;
        this.resolutionButton = this.addRenderableWidget(this.button(
                this.resolutionText(),
                panelX, y, buttonWidth,
                this::cycleResolution
        ));
        y += rowStep;
        int backgroundButtonWidth = buttonWidth - 28;
        this.backgroundButton = this.addRenderableWidget(this.button(
                this.backgroundText(),
                panelX, y, backgroundButtonWidth,
                this::cycleBackground
        ));
        this.colorButton = this.addRenderableWidget(this.button(
                Component.empty(),
                panelX + backgroundButtonWidth + 4, y, 24,
                this::openColorPicker
        ));
        this.updateColorButtonState();
        y += rowStep;
        this.exportButton = this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.export_png"),
                panelX, y, halfWidth,
                this::exportPng
        ));
        this.copyButton = this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.copy_image"),
                panelX + halfWidth + 4, y, halfWidth,
                this::copyImage
        ));
        int bottomY = this.height - MARGIN - BUTTON_HEIGHT;
        this.addRenderableWidget(this.button(
                Component.translatable("quickcraft.litematica.preview_3d.output_folder"),
                panelX, bottomY, halfWidth,
                this::openOutputFolder
        ));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(panelX + halfWidth + 4, bottomY, halfWidth, BUTTON_HEIGHT)
                .build());

        this.setSnapshotButtonsActive(true);
        this.updateViewport();
    }

    private Button button(Component text, int x, int y, int width, Runnable action) {
        return Button.builder(text, button -> action.run())
                .bounds(x, y, width, BUTTON_HEIGHT)
                .build();
    }

    private void updateViewport() {
        int availableWidth = Math.max(1, this.width - PANEL_WIDTH);
        this.viewSize = Math.max(1, Math.min(this.height - MARGIN * 2, availableWidth - MARGIN * 2));
        this.viewX = Math.max(0, (availableWidth - this.viewSize) / 2);
        this.viewY = Math.max(0, (this.height - this.viewSize) / 2);
    }

    private void cycleResolution() {
        this.waitingForRecommendedResolution = false;
        this.resolutionIndex = (this.resolutionIndex + 1) % EXPORT_RESOLUTIONS.length;
        this.resolutionButton.setMessage(this.resolutionText());
    }

    private void updateRecommendedResolution() {
        if (!this.waitingForRecommendedResolution) {
            return;
        }

        int recommended = this.manager.recommendedExportResolution();
        if (recommended == 0) {
            return;
        }
        for (int i = 0; i < EXPORT_RESOLUTIONS.length; i++) {
            if (EXPORT_RESOLUTIONS[i] == recommended) {
                this.resolutionIndex = i;
                this.resolutionButton.setMessage(this.resolutionText());
                break;
            }
        }
        this.waitingForRecommendedResolution = false;
    }

    private Component resolutionText() {
        int resolution = EXPORT_RESOLUTIONS[this.resolutionIndex];
        String name = resolution < 1024 ? Integer.toString(resolution) : resolution / 1024 + "K";
        return Component.translatable("quickcraft.litematica.preview_3d.resolution", name);
    }

    private void cycleBackground() {
        this.background = this.background.next();
        this.backgroundButton.setMessage(this.backgroundText());
        this.updateColorButtonState();
    }

    private Component backgroundText() {
        return Component.translatable(
                "quickcraft.litematica.preview_3d.background",
                Component.translatable(this.background.translationKey)
        );
    }

    private int backgroundColor() {
        return this.background == Background.CUSTOM ? this.customBackgroundColor : this.background.color;
    }

    private void updateColorButtonState() {
        boolean custom = this.background == Background.CUSTOM;
        this.colorButton.visible = custom;
        this.colorButton.active = custom;
    }

    private void openColorPicker() {
        this.minecraft.gui.setScreen(new ColorPickerScreen(this));
    }

    private void exportPng() {
        this.setSnapshotButtonsActive(false);
        this.status = Component.translatable("quickcraft.litematica.preview_3d.exporting");
        this.manager.exportPng(EXPORT_RESOLUTIONS[this.resolutionIndex], this.backgroundColor(), message -> {
            this.status = message;
            this.setSnapshotButtonsActive(true);
        });
    }

    private void copyImage() {
        this.setSnapshotButtonsActive(false);
        this.status = Component.translatable("quickcraft.litematica.preview_3d.copying");
        this.manager.copyImage(EXPORT_RESOLUTIONS[this.resolutionIndex], this.backgroundColor(), message -> {
            this.status = message;
            this.setSnapshotButtonsActive(true);
        });
    }

    private void saveCurrentViewAsPreview() {
        this.setSnapshotButtonsActive(false);
        this.status = Component.translatable("quickcraft.litematica.preview_3d.preview_writing");
        this.manager.saveCurrentViewAsPreview(this.backgroundColor(), message -> {
            this.status = message;
            this.setSnapshotButtonsActive(true);
        });
    }

    private void selectPreviewImage() {
        this.setSnapshotButtonsActive(false);
        this.status = Component.translatable("quickcraft.litematica.preview_3d.selecting_image");
        this.manager.selectPreviewImage(message -> {
            this.status = message;
            this.setSnapshotButtonsActive(true);
        });
    }

    private void removePreviewImage() {
        this.setSnapshotButtonsActive(false);
        this.status = Component.translatable("quickcraft.litematica.preview_3d.preview_removing");
        this.manager.removePreviewImage(message -> {
            this.status = message;
            this.setSnapshotButtonsActive(true);
        });
    }

    private void setSnapshotButtonsActive(boolean active) {
        this.snapshotActionInProgress = !active;
        this.exportButton.active = active;
        this.copyButton.active = active;
        boolean canEditPreview = active && this.manager.canEditPreviewImage();
        if (this.currentViewPreviewButton != null) {
            this.currentViewPreviewButton.active = canEditPreview;
        }
        if (this.selectPreviewImageButton != null) {
            this.selectPreviewImageButton.active = canEditPreview;
        }
        if (this.removePreviewImageButton != null) {
            this.removePreviewImageButton.active = active && this.manager.canRemovePreviewImage();
        }
    }

    private void openOutputFolder() {
        try {
            Files.createDirectories(this.manager.outputDirectory());
            Util.getPlatform().openFile(this.manager.outputDirectory().toFile());
        } catch (IOException e) {
            this.status = Component.translatable("quickcraft.litematica.preview_3d.open_folder_failed");
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        this.manager.renderFullscreen(context, this.viewX, this.viewY, this.viewSize);
        this.updateRecommendedResolution();
        if (!this.snapshotActionInProgress) {
            boolean canEditPreview = this.manager.canEditPreviewImage();
            if (this.currentViewPreviewButton != null) {
                this.currentViewPreviewButton.active = canEditPreview;
            }
            if (this.selectPreviewImageButton != null) {
                this.selectPreviewImageButton.active = canEditPreview;
            }
            if (this.removePreviewImageButton != null) {
                this.removePreviewImageButton.active = this.manager.canRemovePreviewImage();
            }
        }

        int panelCenter = this.width - PANEL_WIDTH / 2;
        context.centeredText(this.font, this.title, panelCenter, 10, 0xFFFFFFFF);
        context.centeredText(this.font, Component.translatable("quickcraft.litematica.preview_3d.section.view"),
                panelCenter, this.viewSectionTitleY, 0xFFE0E0E0);
        if (this.previewSectionTitleY >= 0) {
            context.centeredText(this.font, Component.translatable("quickcraft.litematica.preview_3d.section.preview_image"),
                    panelCenter, this.previewSectionTitleY, 0xFFE0E0E0);
        }
        context.centeredText(this.font, Component.translatable("quickcraft.litematica.preview_3d.section.export"),
                panelCenter, this.exportSectionTitleY, 0xFFE0E0E0);
        context.centeredText(this.font, this.displayName, this.viewX + this.viewSize / 2, 8, 0xFFE0E0E0);

        int statusWidth = Math.max(1, this.viewSize - 16);
        int statusCenter = this.viewX + this.viewSize / 2;
        List<FormattedCharSequence> lines = this.font.split(this.status, statusWidth);
        if (!lines.isEmpty()) {
            int lineStep = this.font.lineHeight + 2;
            int statusY = this.viewY + this.viewSize - 6 - lines.size() * lineStep;
            context.fill(this.viewX + 4, statusY - 3, this.viewX + this.viewSize - 4,
                    this.viewY + this.viewSize - 4, 0x90000000);
            for (FormattedCharSequence line : lines) {
                context.centeredText(this.font, line, statusCenter, statusY, 0xFFDDDDDD);
                statusY += lineStep;
            }
        }
        this.drawCustomColorSwatch(context);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return this.manager.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return this.manager.mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY)
                || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return this.manager.mouseReleased(click.x(), click.y(), click.button())
                || super.mouseReleased(click);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return this.manager.mouseClicked(click.x(), click.y(), click.button())
                || super.mouseClicked(click, doubled);
    }

    @Override
    public void onClose() {
        if (this.closeManagerOnExit) {
            this.manager.close();
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawCustomColorSwatch(GuiGraphicsExtractor context) {
        if (this.background != Background.CUSTOM) {
            return;
        }

        int swatchX = this.colorButton.getX() + 6;
        int swatchY = this.colorButton.getY() + 4;
        context.fill(swatchX, swatchY, swatchX + 12, swatchY + 12, this.customBackgroundColor);
        context.outline(swatchX, swatchY, 12, 12, 0xFFFFFFFF);
    }

    private static final class ColorPickerScreen extends Screen {
        private final QuickLitematicaPreview3DScreen parent;
        private int dragArea = -1;

        private ColorPickerScreen(QuickLitematicaPreview3DScreen parent) {
            super(Component.translatable("quickcraft.litematica.preview_3d.color_picker_title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int buttonWidth = 120;
            int buttonY = this.pickerY() + COLOR_PICKER_HEIGHT + 8;
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                    .bounds((this.width - buttonWidth) / 2, buttonY, buttonWidth, BUTTON_HEIGHT)
                    .build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            super.extractRenderState(context, mouseX, mouseY, delta);
            context.centeredText(this.font, this.title, this.width / 2, this.pickerY() - 16, 0xFFFFFFFF);
            this.drawPicker(context);
        }

        private void drawPicker(GuiGraphicsExtractor context) {
            int pickerX = this.pickerX();
            int pickerY = this.pickerY();
            int colorX = pickerX + COLOR_PICKER_PADDING;
            int colorY = pickerY + COLOR_PICKER_PADDING;
            int hueX = colorX + COLOR_AREA_SIZE + COLOR_PICKER_GAP;
            context.fill(pickerX, pickerY, pickerX + COLOR_PICKER_WIDTH, pickerY + COLOR_PICKER_HEIGHT, 0xEE101010);

            int cell = 4;
            for (int y = 0; y < COLOR_AREA_SIZE; y += cell) {
                float brightness = 1.0F - y / (float) (COLOR_AREA_SIZE - 1);
                for (int x = 0; x < COLOR_AREA_SIZE; x += cell) {
                    float saturation = x / (float) (COLOR_AREA_SIZE - 1);
                    context.fill(
                            colorX + x,
                            colorY + y,
                            colorX + Math.min(x + cell, COLOR_AREA_SIZE),
                            colorY + Math.min(y + cell, COLOR_AREA_SIZE),
                            0xFF000000 | Mth.hsvToRgb(this.parent.customHue, saturation, brightness)
                    );
                }
            }

            for (int y = 0; y < COLOR_AREA_SIZE; y += 2) {
                int color = 0xFF000000 | Mth.hsvToRgb(y / (float) (COLOR_AREA_SIZE - 1), 1.0F, 1.0F);
                context.fill(hueX, colorY + y, hueX + HUE_BAR_WIDTH, colorY + Math.min(y + 2, COLOR_AREA_SIZE), color);
            }

            int markerX = colorX + Math.round(this.parent.customSaturation * (COLOR_AREA_SIZE - 1));
            int markerY = colorY + Math.round((1.0F - this.parent.customBrightness) * (COLOR_AREA_SIZE - 1));
            context.outline(markerX - 2, markerY - 2, 5, 5, this.parent.customBrightness > 0.5F ? 0xFF000000 : 0xFFFFFFFF);
            int hueMarkerY = colorY + Math.round(this.parent.customHue * (COLOR_AREA_SIZE - 1));
            context.outline(hueX - 1, hueMarkerY - 1, HUE_BAR_WIDTH + 2, 3, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            if (click.button() == 0 && this.startPicking(click.x(), click.y())) {
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
            if (click.button() == 0 && this.dragArea >= 0) {
                this.updatePickedColor(click.x(), click.y(), this.dragArea);
                return true;
            }
            return super.mouseDragged(click, deltaX, deltaY);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent click) {
            if (click.button() == 0 && this.dragArea >= 0) {
                this.dragArea = -1;
                return true;
            }
            return super.mouseReleased(click);
        }

        private boolean startPicking(double mouseX, double mouseY) {
            int colorX = this.pickerX() + COLOR_PICKER_PADDING;
            int colorY = this.pickerY() + COLOR_PICKER_PADDING;
            if (mouseX >= colorX && mouseX < colorX + COLOR_AREA_SIZE
                    && mouseY >= colorY && mouseY < colorY + COLOR_AREA_SIZE) {
                this.dragArea = 0;
            } else if (mouseX >= colorX + COLOR_AREA_SIZE + COLOR_PICKER_GAP
                    && mouseX < colorX + COLOR_AREA_SIZE + COLOR_PICKER_GAP + HUE_BAR_WIDTH
                    && mouseY >= colorY && mouseY < colorY + COLOR_AREA_SIZE) {
                this.dragArea = 1;
            } else {
                return false;
            }
            this.updatePickedColor(mouseX, mouseY, this.dragArea);
            return true;
        }

        private void updatePickedColor(double mouseX, double mouseY, int area) {
            int colorX = this.pickerX() + COLOR_PICKER_PADDING;
            int colorY = this.pickerY() + COLOR_PICKER_PADDING;
            if (area == 0) {
                this.parent.customSaturation = Mth.clamp((float) (mouseX - colorX) / (COLOR_AREA_SIZE - 1), 0.0F, 1.0F);
                this.parent.customBrightness = 1.0F - Mth.clamp((float) (mouseY - colorY) / (COLOR_AREA_SIZE - 1), 0.0F, 1.0F);
            } else {
                this.parent.customHue = Mth.clamp((float) (mouseY - colorY) / (COLOR_AREA_SIZE - 1), 0.0F, 1.0F);
            }
            this.parent.customBackgroundColor = 0xFF000000 | Mth.hsvToRgb(
                    this.parent.customHue,
                    this.parent.customSaturation,
                    this.parent.customBrightness
            );
        }

        private int pickerX() {
            return (this.width - COLOR_PICKER_WIDTH) / 2;
        }

        private int pickerY() {
            return Math.max(28, (this.height - COLOR_PICKER_HEIGHT - 32) / 2);
        }

        @Override
        public void onClose() {
            this.minecraft.gui.setScreen(this.parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private enum Background {
        TRANSPARENT("quickcraft.litematica.preview_3d.background.transparent", 0x00000000),
        BLACK("quickcraft.litematica.preview_3d.background.black", 0xFF000000),
        WHITE("quickcraft.litematica.preview_3d.background.white", 0xFFFFFFFF),
        CUSTOM("quickcraft.litematica.preview_3d.background.custom", 0xFF3A7BD5);

        private final String translationKey;
        private final int color;

        Background(String translationKey, int color) {
            this.translationKey = translationKey;
            this.color = color;
        }

        private Background next() {
            Background[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }
}
