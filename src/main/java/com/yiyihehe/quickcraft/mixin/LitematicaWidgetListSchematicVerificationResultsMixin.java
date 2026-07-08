package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierPalette;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.VerifierExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier.BlockMismatchEntry;
import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicVerificationResults;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.util.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原理图验证结果列表的容器差异分组入口。
 *
 * <p>列表内容在 {@code refreshBrowserEntries} 里组装，QuickCraft 赶在
 * {@code reCreateListEntryWidgets} 前插入容器分组，这样后续 widget 创建仍由 Litematica 原列表负责。
 * 目标调用点失效时，验证结果仍存在，但 ALL 页面不会显示容器差异分组。</p>
 */
@Mixin(value = WidgetListSchematicVerificationResults.class, remap = false)
public abstract class LitematicaWidgetListSchematicVerificationResultsMixin
        extends WidgetListBase<BlockMismatchEntry, WidgetSchematicVerificationResult> {
    @Shadow
    @Final
    private GuiSchematicVerifier guiSchematicVerifier;

    @Shadow
    protected abstract void addEntriesForType(SchematicVerifier.MismatchType type);

    public LitematicaWidgetListSchematicVerificationResultsMixin(
            int x,
            int y,
            int width,
            int height,
            @Nullable ISelectionListener<BlockMismatchEntry> selectionListener
    ) {
        super(x, y, width, height, selectionListener);
    }

    @Override
    protected boolean shouldRenderHoverStuff() {
        return true;
    }

    @Inject(
            method = "refreshBrowserEntries",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/gui/widgets/WidgetListBase;reCreateListEntryWidgets()V"),
            require = 0
    )
    private void quickcraft$addInventoryEntries(CallbackInfo ci) {
        if (!QuickLitematicaContainerVerifier.isEnabled()) {
            return;
        }

        SchematicVerifier.MismatchType resultMode = this.guiSchematicVerifier.getResultMode();
        if (resultMode != SchematicVerifier.MismatchType.ALL) {
            return;
        }

        if (!this.quickcraft$hasContainerMismatches()) {
            return;
        }

        String title = QuickLitematicaVerifierPalette.formatSectionTitle(
                StringUtils.translate("quickcraft.litematica.verifier.title.container_errors")
        );
        this.listContents.add(new BlockMismatchEntry(title, ""));

        for (SchematicVerifier.MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            this.addEntriesForType(type);
        }
    }

    private boolean quickcraft$hasContainerMismatches() {
        VerifierExtension extension =
                (VerifierExtension) ((LitematicaGuiSchematicVerifierAccessor) this.guiSchematicVerifier).quickcraft$getVerifier();

        for (SchematicVerifier.MismatchType type : QuickLitematicaContainerVerifier.getContainerMismatchTypes()) {
            if (extension.quickcraft$getContainerMismatchCount(type) > 0) {
                return true;
            }
        }

        return false;
    }
}
