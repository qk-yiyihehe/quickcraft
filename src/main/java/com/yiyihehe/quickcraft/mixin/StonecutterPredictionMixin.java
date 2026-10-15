package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import net.minecraft.screen.StonecutterScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StonecutterScreenHandler.class)
public abstract class StonecutterPredictionMixin {
    @Inject(method = "populateResult", at = @At("RETURN"))
    private void quickcraft$refreshPrediction(CallbackInfo ci) {
        QuickCraftStonecutter.refreshClientPrediction((StonecutterScreenHandler) (Object) this);
    }
}
