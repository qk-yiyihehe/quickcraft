package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SchematicPlacement.class, remap = false)
public interface LitematicaSchematicPlacementAccessor {
    @Accessor("verifier")
    void quickcraft$setVerifier(SchematicVerifier verifier);
}
