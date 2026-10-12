package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.SchematicPlacingUtils;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = SchematicPlacingUtils.class, remap = false)
public abstract class LitematicaGlobalPlacementMixin {
    @Redirect(method = "placeToWorldWithinChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;getSchematic()Lfi/dy/masa/litematica/schematic/LitematicaSchematic;"))
    private static LitematicaSchematic quickcraft$runtimeCopy(SchematicPlacement owner, Level world, ChunkPos chunk,
            SchematicPlacement placement, ReplaceBehavior replace, PasteLayerBehavior layers, boolean notify) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.runtime(owner) : owner.getSchematic();
    }
}
