package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原理图验证结果单行的容器库存悬浮预览。
 *
 * <p>只有容器差异项会接管 {@code postRenderHovered}。接管后取消原版 hover，
 * 避免方块状态 tooltip 和左右库存对比同时出现。</p>
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
    private void quickcraft$renderInventoryOverlay(int mouseX, int mouseY, boolean selected, DrawContext drawContext, CallbackInfo ci) {
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
                MinecraftClient.getInstance(),
                drawContext
        );
        ci.cancel();
    }
}
