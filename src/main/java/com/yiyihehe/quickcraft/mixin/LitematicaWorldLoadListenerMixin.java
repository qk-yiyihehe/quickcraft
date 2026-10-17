package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionPersistence;
import fi.dy.masa.litematica.event.WorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldLoadListener.class, remap = false)
public abstract class LitematicaWorldLoadListenerMixin {
    @Inject(method = "onWorldLoadPre", at = @At("RETURN"))
    private void quickcraft$saveSessionHuds(ClientWorld before, ClientWorld after, MinecraftClient client, CallbackInfo ci) {
        if (before != null && before != after) {
            QuickLitematicaProjectionPersistence.beforeWorldChange(before.getRegistryKey().toString(), after == null);
        }
    }

    @Inject(method = "onWorldLoadPost", at = @At("RETURN"))
    private void quickcraft$restoreSessionHuds(ClientWorld before, ClientWorld after, MinecraftClient client, CallbackInfo ci) {
        if (after != null && before != after) {
            QuickLitematicaProjectionPersistence.afterWorldChange(after.getRegistryKey().toString());
        }
    }
}
