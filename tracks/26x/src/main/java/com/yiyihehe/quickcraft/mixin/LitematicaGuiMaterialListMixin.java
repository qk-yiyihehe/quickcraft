package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialButtons;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class LitematicaGuiMaterialListMixin implements com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialNavigation.NavigationScreen {
    @org.spongepowered.asm.mixin.Unique
    private com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialNavigation quickcraft$navigation;

    @org.spongepowered.asm.mixin.Unique
    private int quickcraft$bottomMargin = 40;

    @Override
    public void quickcraft$setBottomMargin(int margin) { this.quickcraft$bottomMargin = margin; }

    @Override
    public com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialNavigation quickcraft$getNavigation() { return this.quickcraft$navigation; }

    @Override
    public void quickcraft$setNavigation(com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialNavigation navigation) { this.quickcraft$navigation = navigation; }

    @Inject(method = "getBrowserHeight", at = @At("HEAD"), cancellable = true, remap = false)
    private void quickcraft$materialBrowserHeight(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        if (this.quickcraft$navigation != null) {
            var gui = (GuiMaterialList) (Object) this;
            cir.setReturnValue(Math.max(0, gui.getScreenHeight() - ((MalilibGuiListAccessor) this).quickcraft$getListY() - this.quickcraft$bottomMargin));
        }
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void quickcraft$addMaterialButtons(CallbackInfo ci) {
        QuickLitematicaMaterialButtons.add((GuiMaterialList) (Object) this);
    }
}
