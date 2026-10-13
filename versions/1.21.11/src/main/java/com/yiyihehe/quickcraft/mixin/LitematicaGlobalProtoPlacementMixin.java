package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.WorldPlacingUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 新版虚影在工作线程生成 ProtoChunk，真实世界粘贴仍使用原来的放置入口。 */
@Mixin(value = WorldPlacingUtils.class, remap = false)
public abstract class LitematicaGlobalProtoPlacementMixin {
    @WrapOperation(method = "placeToProtoChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;getSchematic()Lfi/dy/masa/litematica/schematic/LitematicaSchematic;"))
    private static LitematicaSchematic quickcraft$runtimeCopy(SchematicPlacement owner, Operation<LitematicaSchematic> original) {
        return QuickLitematicaGlobalReplacements.runtime(owner, original.call(owner));
    }
}
