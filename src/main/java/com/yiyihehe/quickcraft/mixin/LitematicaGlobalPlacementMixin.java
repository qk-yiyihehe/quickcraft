package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.SchematicPlacingUtils;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.world.World;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SchematicPlacingUtils.class, remap = false)
public abstract class LitematicaGlobalPlacementMixin {
    @WrapOperation(method = "placeToWorldWithinChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;getSchematic()Lfi/dy/masa/litematica/schematic/LitematicaSchematic;"))
    private static LitematicaSchematic quickcraft$runtimeCopy(SchematicPlacement owner, Operation<LitematicaSchematic> original, World world, ChunkPos chunk,
            SchematicPlacement placement, ReplaceBehavior replace, PasteLayerBehavior layers, boolean notify) {
        return world instanceof WorldSchematic ? QuickLitematicaGlobalReplacements.runtime(owner, original.call(owner)) : original.call(owner);
    }
}
