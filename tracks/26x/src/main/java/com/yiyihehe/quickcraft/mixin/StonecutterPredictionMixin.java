package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import net.minecraft.world.inventory.StonecutterMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StonecutterMenu.class)
public abstract class StonecutterPredictionMixin {
    @Inject(method = "setupResultSlot", at = @At("RETURN"))
    private void quickcraft$refreshPrediction(CallbackInfo ci) {
        QuickCraftStonecutter.refreshClientPrediction((StonecutterMenu) (Object) this);
    }
}
