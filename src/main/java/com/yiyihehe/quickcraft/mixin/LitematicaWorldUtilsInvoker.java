package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = WorldUtils.class, remap = false)
public interface LitematicaWorldUtilsInvoker {
    @Invoker("doEasyPlaceAction")
    static ActionResult quickcraft$doEasyPlaceAction(MinecraftClient client) {
        throw new AssertionError();
    }
}
