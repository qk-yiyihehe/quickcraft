package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionPersistence;
import fi.dy.masa.litematica.event.WorldLoadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldLoadListener.class, remap = false)
public abstract class LitematicaWorldLoadListenerMixin {
    @Inject(method = "onWorldLoadPre", at = @At("RETURN"))
    private void quickcraft$saveSessionHuds(ClientLevel before, ClientLevel after, Minecraft client, CallbackInfo ci) {
        if (before != null && before != after) {
            QuickLitematicaProjectionPersistence.beforeWorldChange(before.dimension().toString(), after == null);
        }
    }

    @Inject(method = "onWorldLoadPost", at = @At("RETURN"))
    private void quickcraft$restoreSessionHuds(ClientLevel before, ClientLevel after, Minecraft client, CallbackInfo ci) {
        if (after != null && before != after) {
            QuickLitematicaProjectionPersistence.afterWorldChange(after.dimension().toString());
        }
    }
}
