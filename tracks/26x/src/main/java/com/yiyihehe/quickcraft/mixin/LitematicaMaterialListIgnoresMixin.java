package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialIgnores;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.util.data.ItemType;
import java.util.List;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaterialListBase.class, remap = false)
public abstract class LitematicaMaterialListIgnoresMixin implements QuickLitematicaMaterialIgnores.IgnoredMaterials {
    @Shadow @Final protected Set<MaterialListEntry> ignored;

    @Override
    public Set<MaterialListEntry> quickcraft$getIgnoredMaterials() { return this.ignored; }


    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setMaterialListEntries", at = @At("HEAD"), argsOnly = true, order = 900)
    private List<MaterialListEntry> quickcraft$originalEditorRows(List<MaterialListEntry> entries) {
        return com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials.editorEntries((MaterialListBase) (Object) this, entries);
    }

    @Inject(method = "setMaterialListEntries", at = @At("HEAD"))
    private void quickcraft$restoreIgnored(List<MaterialListEntry> entries, CallbackInfo ci) {
        QuickLitematicaMaterialIgnores.restore((MaterialListBase) (Object) this, entries);
    }

    @Inject(method = "ignoreEntry", at = @At("TAIL"))
    private void quickcraft$saveIgnored(MaterialListEntry entry, CallbackInfo ci) {
        QuickLitematicaMaterialIgnores.add(QuickLitematicaMaterialLists.getPersistentContextKey(
                (MaterialListBase) (Object) this), new ItemType(entry.getStack(), true, true).toString());
    }

    @Inject(method = "clearIgnored", at = @At("TAIL"))
    private void quickcraft$clearIgnored(CallbackInfo ci) {
        QuickLitematicaMaterialIgnores.clear(QuickLitematicaMaterialLists.getPersistentContextKey((MaterialListBase) (Object) this));
    }
}
