package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewCancellation;
import fi.dy.masa.litematica.schematic.conversion.SchematicConversionMaps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 上游转换捕获 Exception 并保留原记录，在其返回处退出，避免取消被逐条吞掉。 */
@Mixin(value = SchematicConversionMaps.class, remap = false)
public abstract class LitematicaPreviewConversionMixin {
    @Inject(method = {"updateBlockName", "updateBlockStates", "updateBlockEntity", "updateEntity"},
            at = @At("RETURN"), remap = false)
    private static void quickcraft$checkPreviewCancellation(CallbackInfoReturnable<?> cir) {
        QuickLitematicaPreviewCancellation.check();
    }
}
