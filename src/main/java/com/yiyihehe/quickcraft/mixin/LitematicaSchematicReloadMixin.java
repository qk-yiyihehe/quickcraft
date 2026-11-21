package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMemory;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaSchematicReloadMixin {
    @Inject(method = "readFromFile()Z", at = @At("RETURN"))
    private void quickcraft$refreshReplacementSource(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            QuickLitematicaContainerMemory.schematicReloaded((LitematicaSchematic) (Object) this);
        }
    }
}
