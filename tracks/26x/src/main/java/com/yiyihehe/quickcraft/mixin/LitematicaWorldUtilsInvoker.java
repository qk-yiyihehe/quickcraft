package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = WorldUtils.class, remap = false)
public interface LitematicaWorldUtilsInvoker {
    @Invoker("doEasyPlaceAction")
    static InteractionResult quickcraft$doEasyPlaceAction(Minecraft client) {
        throw new AssertionError();
    }
}
