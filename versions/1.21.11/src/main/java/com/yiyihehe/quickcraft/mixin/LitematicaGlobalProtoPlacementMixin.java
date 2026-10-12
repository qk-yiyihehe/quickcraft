package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.WorldPlacingUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 新版虚影在工作线程生成 ProtoChunk，真实世界粘贴仍使用原来的放置入口。 */
@Mixin(value = WorldPlacingUtils.class, remap = false)
public abstract class LitematicaGlobalProtoPlacementMixin {
    @Redirect(method = "placeToProtoChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;getSchematic()Lfi/dy/masa/litematica/schematic/LitematicaSchematic;"))
    private static LitematicaSchematic quickcraft$runtimeCopy(SchematicPlacement owner) {
        return QuickLitematicaGlobalReplacements.runtime(owner);
    }
}
