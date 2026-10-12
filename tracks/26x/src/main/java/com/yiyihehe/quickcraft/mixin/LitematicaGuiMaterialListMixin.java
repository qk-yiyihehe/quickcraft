package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialButtons;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class LitematicaGuiMaterialListMixin {
    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addMaterialButtons(CallbackInfo ci) {
        QuickLitematicaMaterialButtons.add((GuiMaterialList) (Object) this);
    }
}
