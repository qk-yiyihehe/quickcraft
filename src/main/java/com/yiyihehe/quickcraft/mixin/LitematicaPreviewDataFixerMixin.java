package com.yiyihehe.quickcraft.mixin;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixerUpper;
import com.mojang.serialization.Dynamic;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只观测当前 QC 预览线程范围内的真实 DFU 调用，不计整个原理图读取为 DFU。 */
@Mixin(value = DataFixerUpper.class, remap = false)
public abstract class LitematicaPreviewDataFixerMixin {
    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void quickcraft$beginPreviewDfu(DSL.TypeReference type, Dynamic<?> input, int from, int to,
                                          CallbackInfoReturnable<Dynamic<?>> cir) {
        if (QuickLitematicaPreviewLog.enabled() && QuickLitematicaPreviewLog.current() != null) {
            QuickLitematicaPreviewLog.beginDfu(type.typeName(), from, to);
        }
    }

    @Inject(method = "update", at = @At("RETURN"), remap = false)
    private void quickcraft$endPreviewDfu(DSL.TypeReference type, Dynamic<?> input, int from, int to,
                                        CallbackInfoReturnable<Dynamic<?>> cir) {
        QuickLitematicaPreviewLog.endDfu();
    }
}
