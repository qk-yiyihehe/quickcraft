package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原理图验证结果列表的单条记录上追加容器内容悬浮预览。
 * 选中容器错填项时，复用 QuickLitematicaContainerVerifier 画左右库存对比。
 */
@Mixin(value = WidgetSchematicVerificationResult.class, remap = false)
public abstract class LitematicaWidgetSchematicVerificationResultMixin
        extends WidgetListEntrySortable<GuiSchematicVerifier.BlockMismatchEntry> {
    @Shadow
    @Final
    private GuiSchematicVerifier.BlockMismatchEntry mismatchEntry;

    public LitematicaWidgetSchematicVerificationResultMixin(
            int x,
            int y,
            int width,
            int height,
            @Nullable GuiSchematicVerifier.BlockMismatchEntry entry,
            int listIndex
    ) {
        super(x, y, width, height, entry, listIndex);
    }

    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true)
    private void quickcraft$renderInventoryOverlay(
            GuiContext drawContext,
            int mouseX,
            int mouseY,
            boolean selected,
            CallbackInfo ci
    ) {
        if (this.mismatchEntry == null
                || this.mismatchEntry.blockMismatch == null
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(this.mismatchEntry.blockMismatch.mismatchType)) {
            return;
        }

        BlockMismatchExtension extension =
                (BlockMismatchExtension) this.mismatchEntry.blockMismatch;

        if (extension.quickcraft$getContainerMismatch() == null) {
            return;
        }

        QuickLitematicaContainerVerifier.renderInventoryPair(
                extension.quickcraft$getContainerMismatch(),
                this.mismatchEntry.blockMismatch.stateExpected,
                this.mismatchEntry.blockMismatch.stateFound,
                extension.quickcraft$getExpectedDisabledSlots(),
                extension.quickcraft$getFoundDisabledSlots(),
                mouseX,
                mouseY,
                Minecraft.getInstance(),
                drawContext
        );
        ci.cancel();
    }
}
