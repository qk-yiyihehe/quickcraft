package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialRow;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialGui.Context;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WidgetMaterialListEntry.class, remap = false)
public abstract class LitematicaWidgetMaterialListEntryMixin {
    @Unique private QuickLitematicaMaterialRow quickcraft$row;
    @Inject(method = "<init>", at = @At("TAIL"))
    private void quickcraft$create(int x,int y,int width,int height,boolean odd,MaterialListBase list,
            MaterialListEntry entry,int index,WidgetListMaterialList widget,CallbackInfo ci) {
        this.quickcraft$row = new QuickLitematicaMaterialRow(x,y,width,height,odd,list,entry,index,widget,
                (MalilibWidgetContainerInvoker) this);
    }
    @Inject(method = "onMouseClickedImpl", at = @At("HEAD"), cancellable = true)
    private void quickcraft$select(net.minecraft.client.input.MouseButtonEvent click,boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (this.quickcraft$row != null && this.quickcraft$row.select((int)click.x(),(int)click.y(),click.button())) { cir.setReturnValue(true); }
    }
    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$draw(fi.dy.masa.malilib.render.GuiContext context,int x,int y,boolean selected, CallbackInfo ci) {
        if (this.quickcraft$row != null) { this.quickcraft$row.draw(x,y,selected,new Context(context)); }
    }
}
