package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMemory;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaterialListSchematic.class, remap = false)
public abstract class LitematicaProjectionFileMaterialsMixin {
    @Inject(method = "reCreateMaterialList", at = @At("HEAD"))
    private void quickcraft$applyBlocks(CallbackInfo ci) {
        MaterialListBase list = (MaterialListBase) (Object) this;
        QuickLitematicaContainerMemory.update(QuickLitematicaProjectionMaterials.schematic(list),
                QuickLitematicaProjectionMaterials.regions(list), QuickLitematicaMaterialLists.getReplacementContextKey(list));
    }

    @Inject(method = "reCreateMaterialList", at = @At("TAIL"))
    private void quickcraft$restoreOriginalRows(CallbackInfo ci) {
        QuickLitematicaProjectionMaterials.refreshFileList((MaterialListBase) (Object) this);
    }
}
