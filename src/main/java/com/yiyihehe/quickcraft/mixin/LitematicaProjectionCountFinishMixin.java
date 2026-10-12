package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TaskCountBlocksBase.class, remap = false)
public abstract class LitematicaProjectionCountFinishMixin {
    @Inject(method = "onStop", at = @At("HEAD"))
    private void quickcraft$prepareEditor(CallbackInfo ci) {
        if ((Object) this instanceof QuickLitematicaProjectionMaterials.OriginalCountSource source) { source.quickcraft$prepareOriginalRows(); }
    }
}
