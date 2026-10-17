package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(value = InfoHud.class, remap = false)
public interface LitematicaInfoHudAccessor {
    @Accessor("renderers")
    List<IInfoHudRenderer> quickcraft$getRenderers();
}
