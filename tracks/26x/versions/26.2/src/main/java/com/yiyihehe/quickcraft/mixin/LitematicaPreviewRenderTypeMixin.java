package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewRenderTypes;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderType.class)
public class LitematicaPreviewRenderTypeMixin {
    @Inject(method = "prepare", at = @At("RETURN"), cancellable = true)
    private void quickcraft$capturePreviewMaterial(CallbackInfoReturnable<PreparedRenderType> cir) {
        cir.setReturnValue(QuickLitematicaPreviewRenderTypes.capture((RenderType) (Object) this, cir.getReturnValue()));
    }
}
