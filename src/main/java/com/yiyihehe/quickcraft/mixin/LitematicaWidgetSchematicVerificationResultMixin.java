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
    private void quickcraft$renderInventoryOverlay(int mouseX, int mouseY, boolean selected, DrawContext drawContext, CallbackInfo ci) {
        GuiSchematicVerifier.BlockMismatchEntry mismatchEntry = this.mismatchEntry;
        if (mismatchEntry == null) {
            return;
        }
        var blockMismatch = mismatchEntry.blockMismatch;
        if (blockMismatch == null
                || !QuickLitematicaContainerVerifier.isContainerMismatchType(blockMismatch.mismatchType)) {
            return;
        }
        BlockMismatchExtension extension = (BlockMismatchExtension) blockMismatch;
        var containerMismatch = extension.quickcraft$getContainerMismatch();
        if (containerMismatch == null) {
            return;
        }

        QuickLitematicaContainerVerifier.renderInventoryPair(
                containerMismatch,
                blockMismatch.stateExpected,
                blockMismatch.stateFound,
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
