package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取材料表实际绑定的投影，避免依赖可能已切换的全局选中投影。
 */
@Mixin(value = MaterialListPlacement.class, remap = false)
public interface LitematicaMaterialListPlacementAccessor {
    @Accessor("placement")
    SchematicPlacement quickcraft$getPlacement();
}
