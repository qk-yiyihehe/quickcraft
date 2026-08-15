package com.yiyihehe.quickcraft.mixin;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 保留文件材料表绑定的原理图和子区域集合，使容器列表与原表范围一致。
 */
@Mixin(value = MaterialListSchematic.class, remap = false)
public interface LitematicaMaterialListSchematicAccessor {
    @Accessor("schematic")
    LitematicaSchematic quickcraft$getSchematic();

    @Accessor("regions")
    ImmutableList<String> quickcraft$getRegions();
}
