package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LitematicaSchematic.class, remap = false)
public interface LitematicaSchematicBlocksAccessor {
    @Accessor("blockContainers")
    Map<String, LitematicaBlockStateContainer> quickcraft$getBlockContainers();
}
