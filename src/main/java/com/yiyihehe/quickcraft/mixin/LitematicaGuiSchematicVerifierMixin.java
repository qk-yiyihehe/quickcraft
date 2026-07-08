package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.VerifierExtension;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Litematica 原理图验证器的容器差异界面扩展。
 *
 * <p>这里只处理 GUI 状态：容器差异分类按钮、底部统计文案，以及配置关闭后从容器分类退回
 * 原版 {@link MismatchType#ALL}。实际容器对比和结果缓存都在 verifier 扩展里完成。</p>
 */
@Mixin(value = GuiSchematicVerifier.class, remap = false)
public abstract class LitematicaGuiSchematicVerifierMixin {
    @Shadow
    private static MismatchType resultMode;

    @Inject(method = "initGui", at = @At("HEAD"))
    private void quickcraft$resetInventoryResultModeWhenDisabled(CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()
                && QuickLitematicaContainerVerifier.isContainerMismatchType(resultMode)) {
            resultMode = MismatchType.ALL;
            return;
        }

        // 容器分类即使当前没有结果也允许停留，避免按钮点下去又被强制跳回 ALL。
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void quickcraft$addInventoryResultModeButtons(CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        GuiSchematicVerifier gui = (GuiSchematicVerifier) (Object) this;
        int x = this.quickcraft$getInventoryButtonX(gui);
        // 42 是 Litematica 1.21-1.21.1 验证器顶部分类按钮行的 y 坐标。
        int y = 42;
        int addedWidth = this.quickcraft$getInventoryButtonsWidth(gui);

        this.quickcraft$shiftButtonsAtOrAfter(gui, x, y, addedWidth);

        for (MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            String label = type.getDisplayname();
            int width = gui.getStringWidth(label) + 10;
            ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
            button.setEnabled(resultMode != type);
            gui.addButton(button, (clickedButton, mouseButton) -> {
                resultMode = type;
                gui.initGui();
            });
            x += width + 4;
        }
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void quickcraft$addInventoryStatusLabel(CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        GuiSchematicVerifier gui = (GuiSchematicVerifier) (Object) this;
        SchematicVerifier verifier = ((LitematicaGuiSchematicVerifierAccessor) gui).quickcraft$getVerifier();

        if (!verifier.isFinished()) {
            return;
        }

        VerifierExtension extension = (VerifierExtension) verifier;
        int wrong = extension.quickcraft$getContainerMismatchCount(QuickLitematicaContainerVerifier.WRONG_FILL);
        int missing = extension.quickcraft$getContainerMismatchCount(QuickLitematicaContainerVerifier.MISSING_FILL);
        int state = extension.quickcraft$getContainerMismatchCount(QuickLitematicaContainerVerifier.WRONG_FILL_STATE);
        int expected = extension.quickcraft$getExpectedContainerCount();
        int checked = extension.quickcraft$getCheckedContainerCount();
        int pending = extension.quickcraft$getPendingContainerCount();
        String label = StringUtils.translate(
                "quickcraft.litematica.verifier.status.container_errors_checked",
                wrong,
                missing,
                state,
                checked,
                expected,
                pending
        );
        gui.addLabel(12, gui.getScreenHeight() - 14, 100, 12, 0xFFF0F0F0, label);
    }

    @Inject(method = "onSelectionChange", at = @At("HEAD"), cancellable = true)
    private void quickcraft$ignoreContainerSectionTitle(GuiSchematicVerifier.BlockMismatchEntry entry, CallbackInfo ci) {
        String title = StringUtils.translate("quickcraft.litematica.verifier.title.container_errors");

        if (entry != null
                && entry.blockMismatch == null
                && entry.mismatchType == null
                && entry.header1 != null
                && entry.header1.contains(title)) {
            ci.cancel();
        }
    }

    private int quickcraft$getInventoryButtonX(GuiSchematicVerifier gui) {
        int x = 12;

        x += this.quickcraft$getButtonWidth(gui, Configs.Generic.ENABLE_DIFFERENT_BLOCKS.getBooleanValue()
                ? MismatchType.ALL.getDisplayname()
                : StringUtils.translate("litematica.gui.label.schematic_verifier_display_type.all_not_ignored")) + 4;
        x += this.quickcraft$getButtonWidth(gui, MismatchType.WRONG_BLOCK.getDisplayname()) + 4;
        x += this.quickcraft$getButtonWidth(gui, MismatchType.WRONG_STATE.getDisplayname()) + 4;
        x += this.quickcraft$getButtonWidth(gui, MismatchType.EXTRA.getDisplayname()) + 4;
        x += this.quickcraft$getButtonWidth(gui, MismatchType.MISSING.getDisplayname()) + 4;
        x += this.quickcraft$getButtonWidth(gui, MismatchType.CORRECT_STATE.getDisplayname()) + 4;

        return x;
    }

    private int quickcraft$getInventoryButtonsWidth(GuiSchematicVerifier gui) {
        int width = 0;

        for (MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            width += this.quickcraft$getButtonWidth(gui, type.getDisplayname()) + 4;
        }

        return width;
    }

    private void quickcraft$shiftButtonsAtOrAfter(GuiSchematicVerifier gui, int x, int y, int offset) {
        if (!Configs.Generic.ENABLE_DIFFERENT_BLOCKS.getBooleanValue()) {
            return;
        }

        List<ButtonBase> buttons = ((GuiBaseAccessor) gui).quickcraft$getButtons();

        for (ButtonBase button : buttons) {
            if (button.getY() == y && button.getX() >= x) {
                button.setX(button.getX() + offset);
            }
        }
    }

    private int quickcraft$getButtonWidth(GuiSchematicVerifier gui, String label) {
        return gui.getStringWidth(label) + 10;
    }
}
