package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMemory;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public abstract class LitematicaContainerMemoryPlacementMixin {
    @Inject(method = "markChunksForRebuild(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;)V", at = @At("HEAD"))
    private void quickcraft$refreshContainersBeforeRebuild(SchematicPlacement placement, CallbackInfo ci) {
        QuickLitematicaContainerMemory.update(placement);
    }

    @Inject(method = "addSchematicPlacement", at = @At("HEAD"))
    private void quickcraft$applySavedContainers(SchematicPlacement placement, boolean printMessages, CallbackInfo ci) {
        QuickLitematicaContainerMemory.update(placement);
    }
}
